package com.orderagent.langchain4j;

import com.orderagent.OrderSystemApiClient;
import com.orderagent.ToolErrors;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import org.springframework.stereotype.Component;

/**
 * 任务4：把两个写操作工具（取消订单 / 修改收货地址）用 LangChain4j 的 @Tool 定义。
 * 逻辑与手写版（CancelOrderTool / UpdateAddressTool）完全一致：
 *   参数兜底（缺失/类型 → 统一转字符串校验）→ 走 OrderSystemApiClient → order-system 做业务校验。
 * 手写版保留不删，两版并存。
 *
 * 安全关键：这俩是写操作（会改数据）。方法本身只是"会做什么"的定义，
 * 能不能真执行由 {@link ToolProposalGate} 决定——模型提议后必须人工批准才放行。
 */
@Component
public class LangChain4jWriteTools {

    private final OrderSystemApiClient api;

    public LangChain4jWriteTools(OrderSystemApiClient api) {
        this.api = api;
    }

    @Tool(name = "cancel_order",
            value = "取消一个订单：待支付订单直接取消；已支付订单取消并触发退款通知。"
                    + "参数 orderNo 是订单号。这是写操作，会修改数据，需要人工确认后才会真正执行。")
    public String cancelOrder(@P(value = "要取消的订单号 order_no，如 2026073113563149f68e", required = true) String orderNo) {
        String no = orderNo == null ? "" : orderNo.trim();
        if (no.isEmpty()) {
            return ToolErrors.fail("INVALID_ARG", "缺少订单号 orderNo");
        }
        return api.cancelOrder(no);
    }

    @Tool(name = "update_order_address",
            value = "修改一个订单的收货地址。参数：orderNo 是订单号，address 是新的收货地址。"
                    + "这是写操作，会修改数据，需要人工确认后才会真正执行。")
    public String updateOrderAddress(
            @P(value = "订单号 order_no，如 2026073113563149f68e", required = true) String orderNo,
            @P(value = "新的收货地址，如 上海市浦东新区张江高科技园区", required = true) String address) {
        String no = orderNo == null ? "" : orderNo.trim();
        String addr = address == null ? "" : address.trim();
        if (no.isEmpty()) {
            return ToolErrors.fail("INVALID_ARG", "缺少订单号 orderNo");
        }
        if (addr.isEmpty()) {
            return ToolErrors.fail("INVALID_ARG", "缺少新收货地址 address");
        }
        return api.updateOrderAddress(no, addr);
    }
}
