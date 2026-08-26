package com.orderagent;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 写操作工具：取消订单。
 * 业务规则（状态机/库存/退款事件）全部由 order-system 负责，agent 只做决策和触发——
 * 通过内部 API 调 order-system，不再直连数据库。
 * readOnly() 返回 false，权限闸门据此拦截，要人工确认后才能执行。
 */
@Component
public class CancelOrderTool implements Tool {

    private final OrderSystemApiClient api;

    public CancelOrderTool(OrderSystemApiClient api) {
        this.api = api;
    }

    @Override
    public String name() {
        return "cancel_order";
    }

    @Override
    public String description() {
        return "取消一个订单：待支付订单直接取消；已支付订单取消并触发退款通知。参数 orderNo 是订单号。这是写操作，会修改数据。";
    }

    @Override
    public Map<String, Object> inputSchema() {
        Map<String, Object> orderNo = Map.<String, Object>of(
                "type", "string",
                "description", "要取消的订单号 order_no，如 2026073113563149f68e");
        return Map.<String, Object>of(
                "type", "object",
                "properties", Map.<String, Object>of("orderNo", orderNo),
                "required", List.of("orderNo"));
    }

    @Override
    public boolean readOnly() {
        return false;
    }

    @Override
    public String run(Map<String, Object> args) {
        // 参数不能全信：可能缺失、可能是数字，统一转字符串再校验，避免 ClassCastException
        String orderNo = args.get("orderNo") == null ? "" : String.valueOf(args.get("orderNo")).trim();
        if (orderNo.isEmpty()) {
            return ToolErrors.fail("INVALID_ARG", "缺少订单号 orderNo");
        }
        return api.cancelOrder(orderNo);
    }
}
