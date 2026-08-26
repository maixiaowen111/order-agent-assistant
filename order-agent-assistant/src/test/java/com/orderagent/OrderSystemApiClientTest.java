package com.orderagent;

import org.junit.jupiter.api.Test;

import java.net.ConnectException;
import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * OrderSystemApiClient 错误分类测试：不碰真实网络，用假 HttpClient 制造各种故障，
 * 验证每一类故障都被翻译成结构化 JSON（success=false + errorCode + message），
 * 而不是把 Java 异常原话/堆栈漏给模型。
 */
class OrderSystemApiClientTest {

    private final HttpClient http = mock(HttpClient.class);
    private final OrderSystemApiClient api = new OrderSystemApiClient("http://test", "test-key", http);

    /** 让假 HttpClient 返回指定状态码和 body */
    private void stubHttp(int status, String body) throws Exception {
        HttpResponse<String> resp = mock(HttpResponse.class);
        when(resp.statusCode()).thenReturn(status);
        when(resp.body()).thenReturn(body);
        // send 是泛型方法，when(...).thenReturn 会把泛型推断成 Object，必须用 doReturn 绕开
        doReturn(resp).when(http).send(any(), any());
    }

    /** 让假 HttpClient 抛指定异常（模拟超时/连不上） */
    private void stubHttpThrows(Exception e) throws Exception {
        doThrow(e).when(http).send(any(), any());
    }

    @Test
    void 业务服务返回5xx_翻译成HTTP_5XX且不泄漏堆栈() throws Exception {
        stubHttp(500, "Internal Server Error");
        String result = api.queryOrder("A123");
        assertThat(result).contains("\"success\":false")
                .contains("HTTP_5XX")
                .doesNotContain("Internal Server Error")
                .doesNotContain("Exception");
    }

    @Test
    void 业务服务返回4xx_翻译成HTTP_4XX() throws Exception {
        stubHttp(404, "Not Found");
        String result = api.queryOrder("A123");
        assertThat(result).contains("HTTP_4XX").contains("404");
    }

    @Test
    void 业务code不为200_翻译成BUSINESS_ERROR并带上服务消息() throws Exception {
        stubHttp(200, "{\"code\":500,\"message\":\"订单不存在，无法执行取消操作\"}");
        String result = api.cancelOrder("A123");
        assertThat(result).contains("BUSINESS_ERROR").contains("订单不存在");
    }

    @Test
    void body不是JSON_翻译成INVALID_RESPONSE且不泄漏原文() throws Exception {
        stubHttp(200, "<html>gateway error</html>");
        String result = api.queryProductStock(1L);
        assertThat(result).contains("INVALID_RESPONSE")
                .doesNotContain("<html>");
    }

    @Test
    void 请求超时_翻译成HTTP_TIMEOUT且不泄漏异常原话() throws Exception {
        stubHttpThrows(new HttpTimeoutException("read timed out"));
        String result = api.cancelOrder("A123");
        assertThat(result).contains("HTTP_TIMEOUT")
                .doesNotContain("read timed out")
                .doesNotContain("Exception");
    }

    @Test
    void 连接失败_翻译成CONNECTION_FAILED且不泄漏异常原话() throws Exception {
        stubHttpThrows(new ConnectException("Connection refused"));
        String result = api.queryOrder("A123");
        assertThat(result).contains("CONNECTION_FAILED")
                .doesNotContain("Connection refused");
    }

    @Test
    void 成功时_返回正常业务描述() throws Exception {
        stubHttp(200, "{\"code\":200,\"data\":{\"orderNo\":\"A123\",\"refundTriggered\":true}}");
        String result = api.cancelOrder("A123");
        assertThat(result).contains("已取消订单").contains("退款");
    }
}
