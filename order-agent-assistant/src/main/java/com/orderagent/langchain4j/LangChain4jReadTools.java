package com.orderagent.langchain4j;

import com.orderagent.OrderSystemApiClient;
import com.orderagent.ToolErrors;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import org.springframework.stereotype.Component;

/**
 * 任务3：把两个只读工具（查订单 / 查库存）用 LangChain4j 的 @Tool 方式重写。
 * 和手写版（QueryOrderTool / QueryStockTool）一一对应：
 *   - @Tool 的 name/value ≈ 手写的 name()/description()
 *   - @P 描述参数 ≈ 手写 inputSchema() 里 properties 的描述
 *   - 方法体 = 手写 run()，同样走 OrderSystemApiClient → order-system，异常由客户端转统一格式
 * 手写版保留不删，两版并存；任务4 才把它们接进模型循环。
 */
@Component
public class LangChain4jReadTools {

    private final OrderSystemApiClient api;

    public LangChain4jReadTools(OrderSystemApiClient api) {
        this.api = api;
    }

    @Tool(name = "query_order",
            value = "按订单号(order_no)查订单的金额、状态、收货人、收货电话、收货地址。订单号形如 2026073113563149f68e")
    public String queryOrder(@P(value = "订单号 order_no，如 2026073113563149f68e", required = true) String orderNo) {
        String no = orderNo == null ? "" : orderNo.trim();
        if (no.isEmpty()) {
            return ToolErrors.fail("INVALID_ARG", "缺少订单号 orderNo");
        }
        return api.queryOrder(no);
    }

    @Tool(name = "query_product_stock",
            value = "查商品库存。参数二选一：productId（商品 id，数字）直接查该商品库存；"
                    + "keyword（商品名称关键字）按名称搜索并返回匹配商品的 id、名称和库存。"
                    + "用户只说商品名、不知道 id 时，用 keyword 先搜出 id 再查。")
    public String queryProductStock(
            @P(value = "商品 id（数字），与 keyword 二选一", required = false) Long productId,
            @P(value = "商品名称关键字，与 productId 二选一", required = false) String keyword) {
        boolean hasId = productId != null;
        boolean hasKw = keyword != null && !keyword.trim().isEmpty();

        if (hasId && hasKw) {
            return ToolErrors.fail("INVALID_ARG", "productId 和 keyword 只能传一个");
        }
        if (hasId) {
            return api.queryProductStock(productId);
        }
        if (hasKw) {
            return api.queryProductSearch(keyword.trim());
        }
        return ToolErrors.fail("INVALID_ARG", "请提供 productId（商品 id 数字）或 keyword（商品名称关键字）");
    }
}
