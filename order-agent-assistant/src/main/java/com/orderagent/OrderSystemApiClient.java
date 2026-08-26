package com.orderagent;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 订单系统的内部 API 客户端。
 *
 * 架构关键：agent 不再直连数据库，所有数据读写都走 order-system 的 /internal/** 接口——
 * 业务规则（状态机/库存/退款事件）只存在于业务系统一份，agent 只是"调接口的决策者"。
 * 服务间鉴权：请求头带 X-Internal-Key（与 order-system 的 internal-api.key 一致）。
 */
@Component
public class OrderSystemApiClient {

    private final String baseUrl;
    private final String internalKey;
    private final HttpClient http;
    private final ObjectMapper json = new ObjectMapper();

    /** Spring 注入：真实 HttpClient */
    @Autowired
    public OrderSystemApiClient(
            @Value("${order-system.base-url}") String baseUrl,
            @Value("${order-system.internal-key}") String internalKey) {
        this(baseUrl, internalKey, HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build());
    }

    /** 包级可见：测试注入假 HttpClient，绕开真实网络 */
    OrderSystemApiClient(String baseUrl, String internalKey, HttpClient http) {
        this.baseUrl = baseUrl;
        this.internalKey = internalKey;
        this.http = http;
    }

    /** 查订单，返回给模型看的描述串 */
    public String queryOrder(String orderNo) {
        try {
            JsonNode data = call("GET", "/internal/order/byOrderNo", "orderNo", orderNo);
            return "订单号：" + text(data, "orderNo")
                    + "，金额：" + text(data, "totalAmount")
                    + "，状态：" + text(data, "status")
                    + "，收货人：" + text(data, "receiverName")
                    + "，收货电话：" + text(data, "receiverPhone")
                    + "，收货地址：" + text(data, "receiverAddress")
                    + "，下单时间：" + text(data, "createTime");
        } catch (ApiException e) {
            return ToolErrors.fail(e.errorCode(), e.getMessage());
        } catch (Exception e) {
            return ToolErrors.fail("UNKNOWN", "查询订单时发生内部错误");
        }
    }

    /** 取消订单，返回是否触发退款 */
    public String cancelOrder(String orderNo) {
        try {
            JsonNode data = call("POST", "/internal/order/cancel", "orderNo", orderNo);
            boolean refunded = data.path("refundTriggered").asBoolean();
            String no = data.path("orderNo").asText();
            return refunded
                    ? "已取消订单 " + no + "，并已触发退款通知"
                    : "已取消订单 " + no + "（待支付订单，无需退款）";
        } catch (ApiException e) {
            return ToolErrors.fail(e.errorCode(), e.getMessage());
        } catch (Exception e) {
            return ToolErrors.fail("UNKNOWN", "取消订单时发生内部错误");
        }
    }

    /** 按商品 id 查库存，返回给模型看的描述串 */
    public String queryProductStock(long productId) {
        try {
            JsonNode data = call("GET", "/internal/product/stock", "productId", String.valueOf(productId));
            return "商品：" + text(data, "name")
                    + "，单价：" + text(data, "price")
                    + "，库存：" + text(data, "stock")
                    + "，" + ("1".equals(text(data, "status")) ? "在售" : "已下架");
        } catch (ApiException e) {
            return ToolErrors.fail(e.errorCode(), e.getMessage());
        } catch (Exception e) {
            return ToolErrors.fail("UNKNOWN", "查询库存时发生内部错误");
        }
    }

    /** 修改收货地址，返回给模型看的描述串 */
    public String updateOrderAddress(String orderNo, String address) {
        try {
            JsonNode data = call("POST", "/internal/order/updateAddress",
                    Map.of("orderNo", orderNo, "address", address));
            return "已更新订单 " + text(data, "orderNo")
                    + " 的收货地址为：" + text(data, "receiverAddress");
        } catch (ApiException e) {
            return ToolErrors.fail(e.errorCode(), e.getMessage());
        } catch (Exception e) {
            return ToolErrors.fail("UNKNOWN", "修改收货地址时发生内部错误");
        }
    }

    /** 按商品名模糊搜索，返回匹配商品的 id/名称/库存，让模型先按名找到 id */
    public String queryProductSearch(String keyword) {
        try {
            JsonNode data = call("GET", "/internal/product/search", "name", keyword);
            if (!data.isArray() || data.size() == 0) {
                return "没有找到名称含「" + keyword + "」的商品";
            }
            StringBuilder sb = new StringBuilder();
            for (JsonNode p : data) {
                sb.append("id=").append(text(p, "productId"))
                  .append(" 名称=").append(text(p, "name"))
                  .append(" 单价=").append(text(p, "price"))
                  .append(" 库存=").append(text(p, "stock"))
                  .append(" ").append("1".equals(text(p, "status")) ? "在售" : "已下架")
                  .append("\n");
            }
            return sb.toString().trim();
        } catch (ApiException e) {
            return ToolErrors.fail(e.errorCode(), e.getMessage());
        } catch (Exception e) {
            return ToolErrors.fail("UNKNOWN", "搜索商品时发生内部错误");
        }
    }

    /**
     * 统一发请求。出错点按性质分类（顺序即排查顺序）：
     *   1) 网络层：连不上 / 超时 / 被中断；
     *   2) HTTP 状态码：4xx 是请求错（重试无用），5xx 是服务挂（重试可能有用）；
     *   3) body 不是 JSON（服务返回了脏数据）；
     *   4) 业务层：HTTP 200 但 body.code != 200（如"订单不存在"，最常见的失败）。
     * 每类都抛 ApiException（分类号 + 人话），由公共方法翻译成给模型的结构化 JSON。
     */
    private JsonNode call(String method, String path, String paramName, String paramValue) throws Exception {
        return call(method, path, Map.of(paramName, paramValue));
    }

    private JsonNode call(String method, String path, Map<String, String> params) throws Exception {
        String query = params.entrySet().stream()
                .map(e -> URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8)
                        + "=" + URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8))
                .collect(Collectors.joining("&"));
        String url = baseUrl + path + "?" + query;
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(10))
                .header("X-Internal-Key", internalKey)
                .header("Accept", "application/json");
        HttpRequest req = ("GET".equals(method))
                ? builder.GET().build()
                : builder.POST(HttpRequest.BodyPublishers.noBody()).build();

        // 1) 网络层：超时和连不上，翻译成人话（注意顺序：超时是 IOException 的子类，必须排在前面）
        HttpResponse<String> resp;
        try {
            resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        } catch (HttpTimeoutException e) {
            throw new ApiException("HTTP_TIMEOUT", "请求订单服务超时，请稍后重试");
        } catch (IOException e) {
            throw new ApiException("CONNECTION_FAILED", "无法连接订单服务，请稍后重试");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ApiException("CANCELLED", "请求被中断");
        }

        // 2) HTTP 状态码：4xx 与 5xx 性质不同，分类号分开
        int status = resp.statusCode();
        if (status >= 400 && status < 500) {
            throw new ApiException("HTTP_4XX", "订单服务拒绝了请求（HTTP " + status + "）");
        }
        if (status >= 500) {
            throw new ApiException("HTTP_5XX", "订单服务暂时不可用（HTTP " + status + "），请稍后重试");
        }

        // 3) body 必须是 JSON：解析不了说明服务返回了脏数据，别把原始内容喂给模型
        JsonNode root;
        try {
            root = json.readTree(resp.body());
        } catch (JsonProcessingException e) {
            throw new ApiException("INVALID_RESPONSE", "订单服务返回了无法解析的内容");
        }

        // 4) 业务层：HTTP 200 但 body.code != 200，如"订单不存在"，用服务自己的 message
        int code = root.path("code").asInt();
        if (code != 200) {
            throw new ApiException("BUSINESS_ERROR", root.path("message").asText("业务处理失败"));
        }
        return root.path("data");
    }

    private String text(JsonNode node, String field) {
        return node.hasNonNull(field) ? node.get(field).asText() : "";
    }
}
