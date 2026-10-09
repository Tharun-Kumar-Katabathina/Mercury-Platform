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
 * Tells the proxy whether a request really carries a body, and keeps a chunked body within the size limit.
 * <p>
 * The proxy streams a request body to the service when {@code getInputStream().isFinished()} is false, and a stream only reports
 * that after it has been read to its end. A request with no body therefore looks like one with a body, and it is forwarded as an
 * empty chunked stream. The JDK HTTP client has a race with that (the stream can complete before the client has set up its body
 * subscriber), and the call then fails with a NullPointerException, a few times in thousands under load.
 * <p>
 * HTTP/1.x says where a body is (RFC 9112 section 6.3), so this filter reads it from the request: no {@code Transfer-Encoding} and no
 * (or a zero) {@code Content-Length} is no body at all; a declared length is passed through untouched ({@link RequestSizeFilter}
 * has already refused one over the limit, and the container reads no more than it declares); a chunked request is empty only
 * if its first read reaches the end, so that one byte is read here and handed on again, in front of the rest of the stream.
 * <p>
 * A chunked body declares no length, so {@link RequestSizeFilter} cannot see how large it is. The stream handed on counts the bytes
 * as the proxy reads them and never hands out more than {@code maxBytes}: the first byte beyond them fails the read with a
 * {@link BodyTooLargeException}, which aborts the call to the service (it has received the limit, never a complete request
 * larger than it) and is answered here with the same 413 as a declared length. Nothing is buffered: the body still streams.
 * <p>
 * It runs after authentication, authorization and the rate limits, so a request those refuse is never read, and the read it does
 * is the one the proxy would make next, with the same container timeouts.
 */
public class BodylessRequestFilter extends OncePerRequestFilter {

    /** a chunked body went past the limit: the one read that would have returned its first excess byte fails with this */
    static final class BodyTooLargeException extends IOException {
        BodyTooLargeException(long maxBytes) {
            super("The request body is larger than " + maxBytes + " bytes");
        }
    }

    private final long maxBytes;

    public BodylessRequestFilter(long maxBytes) {
        this.maxBytes = maxBytes;
    }

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
            chunked(request, response, chain);
        } else if (request.getContentLengthLong() > 0) {
            chain.doFilter(request, response);
        } else {
            chain.doFilter(new NoBody(request), response);
        }
    }

    private void chunked(HttpServletRequest request, HttpServletResponse response, FilterChain chain) throws ServletException, IOException {
        ServletInputStream in = request.getInputStream();
        HttpServletRequest forwarded = in.isFinished() ? new NoBody(request) : afterTheFirstByte(request, in);
        try {
            chain.doFilter(forwarded, response);
        } catch (ServletException | IOException | RuntimeException e) {
            if (!causedByTooLargeBody(e) || response.isCommitted()) {
                throw e;        // not ours, or the answer has already begun: nothing is left to say but to drop the connection
            }
            // the request body was not read to its end, so this connection cannot carry another request
            response.setHeader(HttpHeaders.CONNECTION, "close");
            RequestSizeFilter.refuse(response, maxBytes);
        }
    }

    private HttpServletRequest afterTheFirstByte(HttpServletRequest request, ServletInputStream in) throws IOException {
        int first = in.read();
        return first == -1 ? new NoBody(request) : new LimitedBody(request, new Counted(in, first, maxBytes));
    }

    private static boolean causedByTooLargeBody(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof BodyTooLargeException) {
                return true;
            }
        }
        return false;
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

    /** A chunked request with a body: its stream is the container's, with the byte read ahead put back and the size limit applied. */
    private static final class LimitedBody extends HttpServletRequestWrapper {

        private final ServletInputStream stream;

        LimitedBody(HttpServletRequest request, ServletInputStream stream) {
            super(request);
            this.stream = stream;
        }

        @Override public ServletInputStream getInputStream() { return stream; }

        @Override
        public BufferedReader getReader() {
            String encoding = getCharacterEncoding();
            Charset charset = encoding == null ? StandardCharsets.ISO_8859_1 : Charset.forName(encoding);
            return new BufferedReader(new InputStreamReader(stream, charset));
        }
    }

    /**
     * The container's stream after its first byte was read ahead: that byte comes back first, then the rest. No more than
     * {@code maxBytes} are ever handed out, the byte read ahead included.
     */
    private static final class Counted extends ServletInputStream {

        private final ServletInputStream rest;
        private final long maxBytes;
        private int first;                       // the byte read ahead, or -1 once it has been handed on
        private long handedOut;                  // never more than maxBytes, so maxBytes - handedOut cannot overflow

        Counted(ServletInputStream rest, int first, long maxBytes) {
            this.rest = rest;
            this.first = first;
            this.maxBytes = maxBytes;
        }

        private long room() {
            return maxBytes - handedOut;
        }

        @Override
        public int read() throws IOException {
            if (first >= 0) {
                int b = first;
                countOne();
                first = -1;
                return b;
            }
            int b = rest.read();
            if (b != -1) {
                countOne();
            }
            return b;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            Objects.checkFromIndexSize(off, len, b.length);
            if (len == 0) {
                return 0;
            }
            if (first >= 0) {
                b[off] = (byte) first;           // just that byte: a read may return less, and asking for more could wait for the client
                countOne();
                first = -1;
                return 1;
            }
            if (room() <= 0) {
                return rest.read() == -1 ? -1 : tooLarge();      // at the limit: only the end of the body may follow
            }
            int n = rest.read(b, off, (int) Math.min(len, room()));
            if (n > 0) {
                handedOut += n;
            }
            return n;
        }

        private void countOne() throws BodyTooLargeException {
            if (room() <= 0) {
                tooLarge();
            }
            handedOut++;
        }

        private int tooLarge() throws BodyTooLargeException {
            throw new BodyTooLargeException(maxBytes);
        }

        @Override
        public int available() throws IOException {
            long pending = (first >= 0 ? 1L : 0L) + rest.available();
            return (int) Math.min(pending, Math.max(room(), 0L));
        }

        @Override public boolean isFinished() { return first < 0 && rest.isFinished(); }

        @Override public boolean isReady() { return first >= 0 || rest.isReady(); }

        @Override public void setReadListener(ReadListener listener) { throw new IllegalStateException("Non-blocking reads are not available here"); }

        @Override public void close() throws IOException { rest.close(); }
    }
}
