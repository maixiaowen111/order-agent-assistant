package com.orderagent;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 只读工具：按订单号查真实订单。
 * 数据来自 order-system 的内部接口（/internal/order/byOrderNo），不直连数据库。
 */
@Component
public class QueryOrderTool implements Tool {

    private final OrderSystemApiClient api;

    public QueryOrderTool(OrderSystemApiClient api) {
        this.api = api;
    }

    @Override
    public String name() {
        return "query_order";
    }

    @Override
    public String description() {
        return "按订单号(order_no)查订单的金额、状态、收货人、收货电话、收货地址。订单号形如 2026073113563149f68e";
    }

    @Override
    public Map<String, Object> inputSchema() {
        Map<String, Object> orderNo = Map.<String, Object>of(
                "type", "string",
                "description", "订单号 order_no，如 2026073113563149f68e");
        Map<String, Object> properties = Map.<String, Object>of("orderNo", orderNo);
        return Map.<String, Object>of(
                "type", "object",
                "properties", properties,
                "required", List.<String>of("orderNo"));
    }

    @Override
    public String run(Map<String, Object> args) {
        String orderNo = args.get("orderNo") == null ? "" : String.valueOf(args.get("orderNo")).trim();
        if (orderNo.isEmpty()) {
            return ToolErrors.fail("INVALID_ARG", "缺少订单号 orderNo");
        }
        return api.queryOrder(orderNo);
    }
}
