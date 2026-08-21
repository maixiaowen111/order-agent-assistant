package com.orderagent;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * QueryStockTool 测试：productId / keyword 二选一；都给、都不给、给了非数字都报错。
 * 只读工具不接闸门，这里只验证参数解析和转发。
 */
class QueryStockToolTest {

    private final OrderSystemApiClient api = mock(OrderSystemApiClient.class);
    private final QueryStockTool tool = new QueryStockTool(api);

    @BeforeEach
    void setUp() {
        when(api.queryProductStock(1L)).thenReturn("商品：iPhone，单价：5999，库存：10，在售");
        when(api.queryProductSearch("手机")).thenReturn("id=1 名称=iPhone 库存=10\nid=2 名称=小米手机 库存=5");
    }

    @Test
    void 传productId时_查指定商品库存() {
        String result = tool.run(Map.of("productId", 1L));
        assertThat(result).contains("iPhone").contains("库存：10");
        verify(api).queryProductStock(1L);
    }

    @Test
    void 传keyword时_按名称搜索() {
        String result = tool.run(Map.of("keyword", "手机"));
        assertThat(result).contains("id=1").contains("小米");
        verify(api).queryProductSearch("手机");
    }

    @Test
    void 两个参数都传_报错且不调接口() {
        String result = tool.run(Map.of("productId", 1L, "keyword", "手机"));
        assertThat(result).contains("只能传一个");
        verify(api, never()).queryProductStock(1L);
        verify(api, never()).queryProductSearch("手机");
    }

    @Test
    void 什么都不传_报错() {
        String result = tool.run(Map.of());
        assertThat(result).contains("请提供");
    }

    @Test
    void productId不是数字_报错且不调接口() {
        String result = tool.run(Map.of("productId", "abc"));
        assertThat(result).contains("必须是数字");
        verify(api, never()).queryProductStock(1L);
    }
}
