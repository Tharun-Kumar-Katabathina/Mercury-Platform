package com.mercury.gateway.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * Tells the proxy whether a request really carries a body.
 * <p>
 * The proxy streams a request body to the service when {@code getInputStream().isFinished()} is false, and a stream only reports
 * that after it has been read to its end. A request with no body therefore looks like one with a body, and it is forwarded as an
 * empty chunked stream. The JDK HTTP client has a race with that (the stream can complete before the client has set up its body
 * subscriber), and the call then fails with a NullPointerException, a few times in thousands under load.
 * <p>
 * HTTP/1.x says where a body is (RFC 9112 section 6.3), so this filter reads it from the request: no {@code Transfer-Encoding} and no
 * (or a zero) {@code Content-Length} is no body at all; a declared length is passed through untouched; a chunked request is empty only
 * if its first read reaches the end, so that one byte is read here and handed on again, in front of the rest of the stream.
 * <p>
 * It runs after authentication, authorization and the rate limits, so a request those refuse is never read, and the read it does
 * is the one the proxy would make next, with the same container timeouts.
 */
public class BodylessRequestFilter extends OncePerRequestFilter {

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        // the rules above are HTTP/1.x's: a later protocol can carry a body without any length header
        String protocol = request.getProtocol();
        return protocol == null || !protocol.startsWith("HTTP/1.") || request.getRequestURI().startsWith("/actuator/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (request.getHeader(HttpHeaders.TRANSFER_ENCODING) != null) {
            chain.doFilter(afterTheFirstByte(request), response);
        } else if (request.getContentLengthLong() > 0) {
            chain.doFilter(request, response);
        } else {
            chain.doFilter(new NoBody(request), response);
        }
    }

    private static HttpServletRequest afterTheFirstByte(HttpServletRequest request) throws IOException {
        ServletInputStream in = request.getInputStream();
        if (in.isFinished()) {
            return new NoBody(request);
        }
        int first = in.read();
        return first == -1 ? new NoBody(request) : new PeekedBody(request, in, first);
    }

    /** A request that carries no body: its stream is finished from the start. */
    private static final class NoBody extends HttpServletRequestWrapper {

        private static final ServletInputStream EMPTY = new ServletInputStream() {
            @Override public int read() { return -1; }
            @Override public int read(byte[] b, int off, int len) { Objects.checkFromIndexSize(off, len, b.length); return len == 0 ? 0 : -1; }
            @Override public int available() { return 0; }
            @Override public boolean isFinished() { return true; }
            @Override public boolean isReady() { return true; }
            @Override public void setReadListener(ReadListener listener) { throw new IllegalStateException("Non-blocking reads are not available here"); }
        };

        NoBody(HttpServletRequest request) {
            super(request);
        }

        @Override public ServletInputStream getInputStream() { return EMPTY; }

        @Override public BufferedReader getReader() { return new BufferedReader(Reader.nullReader()); }
    }

    /** A chunked request whose first byte has been read: that byte comes back first, then the rest of the container's stream. */
    private static final class PeekedBody extends HttpServletRequestWrapper {

        private final ServletInputStream stream;

        PeekedBody(HttpServletRequest request, ServletInputStream rest, int first) {
            super(request);
            this.stream = new Peeked(rest, first);
        }

        @Override public ServletInputStream getInputStream() { return stream; }

        @Override
        public BufferedReader getReader() {
            String encoding = getCharacterEncoding();
            Charset charset = encoding == null ? StandardCharsets.ISO_8859_1 : Charset.forName(encoding);
            return new BufferedReader(new InputStreamReader(stream, charset));
        }
    }

    private static final class Peeked extends ServletInputStream {

        private final ServletInputStream rest;
        private int first;                       // the byte read ahead, or -1 once it has been handed on

        Peeked(ServletInputStream rest, int first) {
            this.rest = rest;
            this.first = first;
        }

        @Override
        public int read() throws IOException {
            if (first >= 0) {
                int b = first;
                first = -1;
                return b;
            }
            return rest.read();
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            Objects.checkFromIndexSize(off, len, b.length);
            if (len == 0) {
                return 0;
            }
            if (first >= 0) {
                b[off] = (byte) first;           // just that byte: a read may return less, and asking for more could wait for the client
                first = -1;
                return 1;
            }
            return rest.read(b, off, len);
        }

        @Override public int available() throws IOException { return (first >= 0 ? 1 : 0) + rest.available(); }

        @Override public boolean isFinished() { return first < 0 && rest.isFinished(); }

        @Override public boolean isReady() { return first >= 0 || rest.isReady(); }

        @Override public void setReadListener(ReadListener listener) { throw new IllegalStateException("Non-blocking reads are not available here"); }

        @Override public void close() throws IOException { rest.close(); }
    }
}
