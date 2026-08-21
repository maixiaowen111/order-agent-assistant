package com.orderagent;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 写操作工具：修改订单收货地址。
 * 业务规则（非取消状态可改、地址非空/长度限制）由 order-system 兜住，agent 只做决策和触发。
 * readOnly() 返回 false，权限闸门据此拦截，要人工确认后才能执行。
 */
@Component
public class UpdateAddressTool implements Tool {

    private final OrderSystemApiClient api;

    public UpdateAddressTool(OrderSystemApiClient api) {
        this.api = api;
    }

    @Override
    public String name() {
        return "update_order_address";
    }

    @Override
    public String description() {
        return "修改一个订单的收货地址。参数：orderNo 是订单号，address 是新的收货地址。"
                + "这是写操作，会修改数据，需要人工确认后才会真正执行。";
    }

    @Override
    public Map<String, Object> inputSchema() {
        Map<String, Object> orderNo = Map.<String, Object>of(
                "type", "string",
                "description", "订单号 order_no，如 2026073113563149f68e");
        Map<String, Object> address = Map.<String, Object>of(
                "type", "string",
                "description", "新的收货地址，如 上海市浦东新区张江高科技园区");
        Map<String, Object> properties = Map.<String, Object>of(
                "orderNo", orderNo,
                "address", address);
        return Map.<String, Object>of(
                "type", "object",
                "properties", properties,
                "required", List.<String>of("orderNo", "address"));
    }

    @Override
    public boolean readOnly() {
        return false;
    }

    @Override
    public String run(Map<String, Object> args) {
        String orderNo = args.get("orderNo") == null ? "" : String.valueOf(args.get("orderNo")).trim();
        String address = args.get("address") == null ? "" : String.valueOf(args.get("address")).trim();
        if (orderNo.isEmpty()) {
            return "错误：缺少订单号 orderNo";
        }
        if (address.isEmpty()) {
            return "错误：缺少新收货地址 address";
        }
        return api.updateOrderAddress(orderNo, address);
    }
}
