package com.orderagent;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.io.InputStream;

/**
 * 请求体大小护栏：/query、/approve 的 JSON body 超过 64KB 直接 413。
 *
 * 为什么：/query 的 q 最多 2000 字、body 撑死几 KB；上限卡在 64KB 足以挡住
 * "拿巨型 body 打接口"这类粗暴滥用，又不影响正常使用。两层判断：
 *   ① 有 Content-Length 且超限 → 直接 413（真实 HTTP 客户端都带 Content-Length，主防线）；
 *   ② 没有 Content-Length（chunked）→ 用包装器读流，读到超限就抛 BodyTooLargeException → 413。
 */
@Component
public class MaxBodySizeFilter extends OncePerRequestFilter {

    static final int MAX_BYTES = 64 * 1024;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        if (!isLimited(request)) {
            chain.doFilter(request, response);
            return;
        }
        long len = request.getContentLengthLong();
        if (len > MAX_BYTES) {
            reject(response);
            return;
        }
        try {
            // 有 Content-Length 且没超限直接放行；没有（chunked）用包装器按上限兜底
            chain.doFilter(len >= 0 ? request : new LimitedRequestWrapper(request), response);
        } catch (RuntimeException e) {
            if (isBodyTooLarge(e)) {
                reject(response);
            } else {
                throw e;
            }
        }
    }

    private boolean isLimited(HttpServletRequest request) {
        String uri = request.getRequestURI();
        return "/query".equals(uri) || "/approve".equals(uri);
    }

    private boolean isBodyTooLarge(Throwable t) {
        for (Throwable cur = t; cur != null; cur = cur.getCause()) {
            if (cur instanceof BodyTooLargeException) {
                return true;
            }
        }
        return false;
    }

    private void reject(HttpServletResponse response) throws IOException {
        response.setStatus(413);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"error\":\"请求体过大（上限 " + MAX_BYTES + " 字节）\"}");
    }

    /** 读流时超过上限抛出，由过滤器捕获转成 413。 */
    static final class BodyTooLargeException extends RuntimeException {
        BodyTooLargeException() {
            super("request body exceeds " + MAX_BYTES + " bytes");
        }
    }

    /** 无 Content-Length 时的兜底包装：getInputStream 换成带上限的流。 */
    private static final class LimitedRequestWrapper extends HttpServletRequestWrapper {

        private final ServletInputStream stream;

        LimitedRequestWrapper(HttpServletRequest request) throws IOException {
            super(request);
            this.stream = new LimitedInputStream(request.getInputStream());
        }

        @Override
        public ServletInputStream getInputStream() {
            return stream;
        }
    }

    private static final class LimitedInputStream extends ServletInputStream {

        private final InputStream in;
        private int totalRead;
        private boolean finished;

        LimitedInputStream(InputStream in) {
            this.in = in;
        }

        @Override
        public int read() throws IOException {
            if (totalRead >= MAX_BYTES) {
                throw new BodyTooLargeException();
            }
            int b = in.read();
            if (b < 0) {
                finished = true;
                return -1;
            }
            totalRead++;
            return b;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            if (totalRead >= MAX_BYTES) {
                throw new BodyTooLargeException();
            }
            int n = in.read(b, off, Math.min(len, MAX_BYTES - totalRead));
            if (n < 0) {
                finished = true;
                return -1;
            }
            totalRead += n;
            return n;
        }

        @Override
        public boolean isFinished() {
            return finished;
        }

        @Override
        public boolean isReady() {
            return true;
        }

        @Override
        public void setReadListener(ReadListener listener) {
            // 同步读路径用不到；保持 Servlet 3.1 规范不抛 UnsupportedOperationException 即可
            try {
                listener.onDataAvailable();
            } catch (IOException e) {
                listener.onError(e);
            }
        }
    }
}
