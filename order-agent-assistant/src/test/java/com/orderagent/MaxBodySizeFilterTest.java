package com.orderagent;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.DelegatingServletInputStream;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 请求体大小护栏测试：/query、/approve 超 64KB → 413，其余路径不拦。
 * Content-Length 用 mock 请求精确控制（MockHttpServletRequest 没有独立的 setter）；
 * chunked（无 Content-Length）走包装流按上限兜底。
 */
class MaxBodySizeFilterTest {

    private final MaxBodySizeFilter filter = new MaxBodySizeFilter();
    private final MockHttpServletResponse response = new MockHttpServletResponse();

    @Test
    void 非限定路径_不拦_直接放行() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/health");
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        verify(chain).doFilter(request, response);
        assertThat(response.getStatus()).isEqualTo(200); // 过滤器没动状态码
    }

    @Test
    void query带超限ContentLength_413_不调用chain() throws Exception {
        HttpServletRequest request = requestWithContentLength("/query", MaxBodySizeFilter.MAX_BYTES + 1L);
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(413);
        verify(chain, never()).doFilter(request, response);
    }

    @Test
    void approve带超限ContentLength_413() throws Exception {
        HttpServletRequest request = requestWithContentLength("/approve", MaxBodySizeFilter.MAX_BYTES + 1L);
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(413);
        verify(chain, never()).doFilter(request, response);
    }

    @Test
    void 没超限的ContentLength_放行() throws Exception {
        HttpServletRequest request = requestWithContentLength("/query", 100L);
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        verify(chain).doFilter(request, response);
        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    void 没有ContentLength_chunked流读到超限_转413() throws Exception {
        // chunked：无 Content-Length（-1），走包装流按上限兜底
        HttpServletRequest request = requestWithStream("/query", new byte[MaxBodySizeFilter.MAX_BYTES + 1]);
        // chain 把 body 整个读完——读到超过上限时包装流抛 BodyTooLargeException
        FilterChain readingChain = (req, res) -> {
            ServletInputStream in = req.getInputStream();
            byte[] buf = new byte[4096];
            while (in.read(buf) != -1) {
                // 读空即可
            }
        };

        filter.doFilter(request, response, readingChain);

        assertThat(response.getStatus()).isEqualTo(413);
    }

    @Test
    void chunked流没超限_放行() throws Exception {
        HttpServletRequest request = requestWithStream("/query", new byte[100]);
        FilterChain readingChain = (req, res) -> {
            ServletInputStream in = req.getInputStream();
            byte[] buf = new byte[64];
            while (in.read(buf) != -1) {
                // 读空即可
            }
        };

        filter.doFilter(request, response, readingChain);

        assertThat(response.getStatus()).isEqualTo(200);
    }

    private HttpServletRequest requestWithContentLength(String uri, long len) {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getRequestURI()).thenReturn(uri);
        when(request.getContentLengthLong()).thenReturn(len);
        return request;
    }

    private HttpServletRequest requestWithStream(String uri, byte[] body) throws java.io.IOException {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getRequestURI()).thenReturn(uri);
        when(request.getContentLengthLong()).thenReturn(-1L); // chunked
        when(request.getInputStream()).thenReturn(new DelegatingServletInputStream(new java.io.ByteArrayInputStream(body)));
        return request;
    }
}
