package com.orderagent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

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
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();
    private final ObjectMapper json = new ObjectMapper();

    public OrderSystemApiClient(
            @Value("${order-system.base-url}") String baseUrl,
            @Value("${order-system.internal-key}") String internalKey) {
        this.baseUrl = baseUrl;
        this.internalKey = internalKey;
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
                    + "，下单时间：" + text(data, "createTime");
        } catch (Exception e) {
            return "查询订单失败：" + e.getMessage();
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
        } catch (Exception e) {
            return "取消失败：" + e.getMessage();
        }
    }

    /**
     * 统一发请求：业务系统的 Result.code != 200 视为失败。
     * 注意：业务异常时 HTTP 状态码仍是 200，必须看 body 里的 code 字段。
     */
    private JsonNode call(String method, String path, String paramName, String paramValue) throws Exception {
        String url = baseUrl + path + "?" + paramName + "="
                + URLEncoder.encode(paramValue, StandardCharsets.UTF_8);
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(10))
                .header("X-Internal-Key", internalKey)
                .header("Accept", "application/json");
        HttpRequest req = ("GET".equals(method))
                ? builder.GET().build()
                : builder.POST(HttpRequest.BodyPublishers.noBody()).build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());

        JsonNode root = json.readTree(resp.body());
        int code = root.path("code").asInt();
        if (code != 200) {
            throw new RuntimeException(root.path("message").asText("未知错误"));
        }
        return root.path("data");
    }

    private String text(JsonNode node, String field) {
        return node.hasNonNull(field) ? node.get(field).asText() : "";
    }
}
