package com.orderagent;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * UpdateAddressTool 测试：参数解析与转发；缺参数报错。
 * 它是写操作（readOnly=false），是否被闸门拦截由 WritePermissionGateTest 覆盖，这里不重复。
 */
class UpdateAddressToolTest {

    private final OrderSystemApiClient api = mock(OrderSystemApiClient.class);
    private final UpdateAddressTool tool = new UpdateAddressTool(api);

    @Test
    void 是写操作_需要走闸门() {
        assertThat(tool.readOnly()).isFalse();
    }

    @Test
    void 传orderNo和address时_转发给内部接口() {
        when(api.updateOrderAddress("20260819134108bf588c", "上海市浦东新区"))
                .thenReturn("已更新订单 20260819134108bf588c 的收货地址为：上海市浦东新区");
        String result = tool.run(Map.of(
                "orderNo", "20260819134108bf588c",
                "address", "上海市浦东新区"));
        assertThat(result).contains("已更新").contains("上海市浦东新区");
        verify(api).updateOrderAddress("20260819134108bf588c", "上海市浦东新区");
    }

    @Test
    void 缺订单号_报错且不调接口() {
        String result = tool.run(Map.of("address", "北京市朝阳区"));
        assertThat(result).contains("缺少订单号");
        verify(api, never()).updateOrderAddress("", "北京市朝阳区");
    }

    @Test
    void 缺地址_报错且不调接口() {
        String result = tool.run(Map.of("orderNo", "20260819134108bf588c"));
        assertThat(result).contains("缺少新收货地址");
        verify(api, never()).updateOrderAddress("20260819134108bf588c", "");
    }
}
