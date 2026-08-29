package com.orderagent.langchain4j;

import com.orderagent.OrderSystemApiClient;
import com.orderagent.ToolErrors;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 任务4：LangChain4j 版写工具行为 = 手写版（CancelOrderTool / UpdateAddressTool）：
 * 参数兜底、缺参报 INVALID_ARG 且不调接口、业务失败透传结构化错误且不编造成功。
 * 「能不能执行」由 ToolProposalGate 决定，这里只验证工具本身。
 */
class LangChain4jWriteToolsTest {

    private final OrderSystemApiClient api = mock(OrderSystemApiClient.class);
    private final LangChain4jWriteTools tools = new LangChain4jWriteTools(api);

    @Test
    void cancelOrder_转发给内部接口() {
        when(api.cancelOrder("A123")).thenReturn("已取消订单 A123，并已触发退款通知");
        String result = tools.cancelOrder("A123");
        assertThat(result).contains("已取消订单");
        verify(api).cancelOrder("A123");
    }

    @Test
    void cancelOrder_缺订单号_报错且不调接口() {
        String result = tools.cancelOrder(null);
        assertThat(result).contains("\"success\":false").contains("缺少订单号");
        verify(api, never()).cancelOrder(any());
    }

    @Test
    void updateOrderAddress_转发给内部接口() {
        when(api.updateOrderAddress("20260819134108bf588c", "上海市浦东新区"))
                .thenReturn("已更新订单 20260819134108bf588c 的收货地址为：上海市浦东新区");
        String result = tools.updateOrderAddress("20260819134108bf588c", "上海市浦东新区");
        assertThat(result).contains("已更新").contains("上海市浦东新区");
        verify(api).updateOrderAddress("20260819134108bf588c", "上海市浦东新区");
    }

    @Test
    void updateOrderAddress_缺地址_报错且不调接口() {
        String result = tools.updateOrderAddress("20260819134108bf588c", "");
        assertThat(result).contains("\"success\":false").contains("缺少新收货地址");
        verify(api, never()).updateOrderAddress(any(), any());
    }

    @Test
    void cancelOrder_业务失败_透传结构化错误_不编造成功() {
        when(api.cancelOrder("A123")).thenReturn(ToolErrors.fail("BUSINESS_ERROR", "订单不存在"));
        String result = tools.cancelOrder("A123");
        assertThat(result).contains("\"success\":false")
                .contains("BUSINESS_ERROR")
                .contains("订单不存在")
                .doesNotContain("退款")
                .doesNotContain("已取消");
    }
}
