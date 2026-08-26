package com.orderagent;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * CancelOrderTool 测试：参数兜底（缺失/类型）、业务失败时透传结构化错误且不声称退款。
 * 写操作是否被闸门拦由 WritePermissionGateTest / AgentLoopTest 覆盖，这里不重复。
 */
class CancelOrderToolTest {

    private final OrderSystemApiClient api = mock(OrderSystemApiClient.class);
    private final CancelOrderTool tool = new CancelOrderTool(api);

    @Test
    void 传订单号_转发给内部接口() {
        when(api.cancelOrder("A123")).thenReturn("已取消订单 A123，并已触发退款通知");
        String result = tool.run(Map.of("orderNo", "A123"));
        assertThat(result).contains("已取消订单");
        verify(api).cancelOrder("A123");
    }

    @Test
    void 缺订单号_返回结构化错误且不调接口() {
        String result = tool.run(Map.of());
        assertThat(result).contains("\"success\":false").contains("缺少订单号");
        verify(api, never()).cancelOrder(any());
    }

    @Test
    void 订单号传了数字_不炸_统一转字符串再转发() {
        when(api.cancelOrder("123")).thenReturn("已取消订单 123");
        String result = tool.run(Map.of("orderNo", 123));
        assertThat(result).isEqualTo("已取消订单 123");
        verify(api).cancelOrder("123");
    }

    @Test
    void 业务失败_透传结构化错误_不声称退款不声称已取消() {
        // 真实契约：客户端内部把失败转成错误 JSON 字符串返回，工具原样透传，不自己编造成功
        when(api.cancelOrder("A123")).thenReturn(ToolErrors.fail("BUSINESS_ERROR", "订单不存在"));
        String result = tool.run(Map.of("orderNo", "A123"));
        assertThat(result).contains("\"success\":false")
                .contains("BUSINESS_ERROR")
                .contains("订单不存在")
                .doesNotContain("退款")
                .doesNotContain("已取消");
    }
}
