package com.orderagent.langchain4j;

import com.orderagent.OrderSystemApiClient;
import com.orderagent.QueryOrderTool;
import com.orderagent.QueryStockTool;
import com.orderagent.ToolErrors;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.agent.tool.ToolSpecifications;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 任务3 验证：LangChain4j 版只读工具 = 手写版的行为（两版同样查询、同样错误、同样不泄露堆栈），
 * 且 @Tool 定义能被 LangChain4j 生成出模型真正看到的 ToolSpecification。
 * mock OrderSystemApiClient，纯离线，空机器能跑绿。
 */
class LangChain4jReadToolsTest {

    private final OrderSystemApiClient api = mock(OrderSystemApiClient.class);
    private final QueryOrderTool handOrder = new QueryOrderTool(api);
    private final QueryStockTool handStock = new QueryStockTool(api);
    private final LangChain4jReadTools l4j = new LangChain4jReadTools(api);

    @Test
    void bothVersionsQueryOrderReturnSameResult() {
        String result = "订单号：20260827001，金额：99，状态：已支付，收货人：张*明，收货电话：138****8000，收货地址：北***，下单时间：2026-08-27";
        when(api.queryOrder("20260827001")).thenReturn(result);

        assertThat(l4j.queryOrder("20260827001"))
                .isEqualTo(handOrder.run(Map.of("orderNo", "20260827001")))
                .isEqualTo(result);
    }

    @Test
    void bothVersionsMissingOrderNoReturnSameError() {
        String expected = ToolErrors.fail("INVALID_ARG", "缺少订单号 orderNo");

        assertThat(l4j.queryOrder(null))
                .isEqualTo(handOrder.run(Map.of()))
                .isEqualTo(expected);
    }

    @Test
    void bothVersionsQueryStockReturnSameResult() {
        String result = "商品：iPhone，单价：5999，库存：88，在售";
        when(api.queryProductStock(7L)).thenReturn(result);

        assertThat(l4j.queryProductStock(7L, null))
                .isEqualTo(handStock.run(Map.of("productId", "7")))
                .isEqualTo(result);
    }

    @Test
    void serviceDownErrorIsCleanNotStackTrace() {
        // 真实场景：order-system 挂了，OrderSystemApiClient 内部返回统一错误串（不带堆栈）
        String clean = ToolErrors.fail("CONNECTION_FAILED", "无法连接订单服务，请稍后重试");
        when(api.queryOrder("x")).thenReturn(clean);

        String r = l4j.queryOrder("x");

        assertThat(r).isEqualTo(clean);
        assertThat(r).doesNotContain("Exception").doesNotContain("at com.orderagent");
    }

    @Test
    void toolAnnotationsGenerateLangChain4jSpecs() {
        // 迁移的"定义侧"证明：@Tool/@P 能生成模型真正看到的 ToolSpecification（≈ 手写 inputSchema）
        List<ToolSpecification> specs = ToolSpecifications.toolSpecificationsFrom(LangChain4jReadTools.class);

        assertThat(specs).hasSize(2);
        assertThat(specs).extracting(ToolSpecification::name)
                .containsExactlyInAnyOrder("query_order", "query_product_stock");
        for (ToolSpecification spec : specs) {
            assertThat(spec.description()).isNotBlank();
        }
    }
}
