package com.mercury.gateway.config;

import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

/** The filter's decisions, and what the request it hands on promises about its stream (the Servlet input-stream contract). */
class BodylessRequestFilterTests {

    private final BodylessRequestFilter filter = new BodylessRequestFilter();

    /** a container stream that counts how often it was read, and fails if anything reads past what the client has sent */
    private static final class Scripted extends ServletInputStream {
        private final byte[] sent;
        private int position;
        int reads;

        Scripted(String sent) {
            this.sent = sent.getBytes(StandardCharsets.ISO_8859_1);
        }

        @Override public int read() {
            reads++;
            if (position == sent.length) {
                return -1;
            }
            return sent[position++] & 0xFF;
        }

        @Override public int available() { return sent.length - position; }
        @Override public boolean isFinished() { return position == sent.length && reads > sent.length; }   // like a container: only after the end was read
        @Override public boolean isReady() { return true; }
        @Override public void setReadListener(ReadListener listener) { }
    }

    private static MockHttpServletRequest request(String path, Scripted stream, String... headers) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", path) {
            @Override public ServletInputStream getInputStream() { return stream; }
        };
        request.setProtocol("HTTP/1.1");
        for (int i = 0; i < headers.length; i += 2) {
            request.addHeader(headers[i], headers[i + 1]);
        }
        return request;
    }

    private HttpServletRequest handedOn(MockHttpServletRequest request) throws Exception {
        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(request, new MockHttpServletResponse(), chain);
        return (HttpServletRequest) chain.getRequest();
    }

    private static byte[] drain(ServletInputStream in) throws IOException {
        return in.readAllBytes();
    }

    // ---- no body --------------------------------------------------------------------------------------------------------

    @Test
    void aRequestWithNeitherLengthNorTransferEncodingHasNoBodyAndItsStreamIsFinishedFromTheStart() throws Exception {
        Scripted stream = new Scripted("");

        HttpServletRequest out = handedOn(request("/api/v1/orders/1", stream));

        assertThat(out.getInputStream().isFinished()).isTrue();
        assertThat(out.getInputStream().isReady()).isTrue();
        assertThat(out.getInputStream().available()).isZero();
        assertThat(out.getInputStream().read()).isEqualTo(-1);
        assertThat(out.getReader().read()).isEqualTo(-1);
        assertThat(stream.reads).as("the container's stream is never touched").isZero();
    }

    @Test
    void aZeroContentLengthHasNoBody() throws Exception {
        MockHttpServletRequest in = request("/api/v1/orders/1", new Scripted(""), "Content-Length", "0");
        in.setContent(new byte[0]);

        HttpServletRequest out = handedOn(in);

        assertThat(out.getInputStream().isFinished()).isTrue();
    }

    @Test
    void anEmptyChunkedRequestHasNoBody() throws Exception {
        Scripted stream = new Scripted("");

        HttpServletRequest out = handedOn(request("/api/v1/orders/1", stream, "Transfer-Encoding", "chunked"));

        assertThat(out.getInputStream().isFinished()).isTrue();
        assertThat(out.getInputStream().read()).isEqualTo(-1);
        assertThat(stream.reads).as("one read, to find out").isEqualTo(1);
    }

    // ---- a body -----------------------------------------------------------------------------------------------------------

    @Test
    void aDeclaredLengthIsPassedThroughUntouched() throws Exception {
        Scripted stream = new Scripted("hello");
        MockHttpServletRequest in = request("/api/v1/orders/1", stream, "Content-Length", "5");
        in.setContent("hello".getBytes(StandardCharsets.ISO_8859_1));

        HttpServletRequest out = handedOn(in);

        assertThat(out).isSameAs(in);
        assertThat(stream.reads).isZero();
    }

    @Test
    void aChunkedBodyIsReadOnlyAsFarAsItsFirstByteAndIsHandedOnWhole() throws Exception {
        Scripted stream = new Scripted("hello world");

        HttpServletRequest out = handedOn(request("/api/v1/orders/1", stream, "Transfer-Encoding", "chunked"));

        assertThat(stream.reads).as("the first byte, and not a byte more: the client may have nothing else to send yet").isEqualTo(1);
        ServletInputStream in = out.getInputStream();
        assertThat(in.isFinished()).isFalse();
        assertThat(in.isReady()).isTrue();
        assertThat(in.available()).as("the byte read ahead and the rest").isEqualTo(11);
        assertThat(new String(drain(in), StandardCharsets.ISO_8859_1)).isEqualTo("hello world");
        assertThat(in.isFinished()).isTrue();
        assertThat(in.read()).isEqualTo(-1);
    }

    @Test
    void aBulkReadAfterThePeekReturnsTheByteReadAheadWithoutAskingTheClientForMore() throws Exception {
        Scripted stream = new Scripted("abc");
        ServletInputStream in = handedOn(request("/api/v1/orders/1", stream, "Transfer-Encoding", "chunked")).getInputStream();

        byte[] buffer = new byte[16];
        int first = in.read(buffer, 0, buffer.length);

        assertThat(first).isEqualTo(1);
        assertThat(buffer[0]).isEqualTo((byte) 'a');
        assertThat(stream.reads).isEqualTo(1);
        assertThat(in.read(buffer, 0, 0)).isZero();
        assertThat(new String(drain(in), StandardCharsets.ISO_8859_1)).isEqualTo("bc");
    }

    @Test
    void everyByteValueSurvivesThePeek() throws Exception {
        StringBuilder all = new StringBuilder();
        for (int b = 255; b >= 0; b--) {                                    // starting with 0xFF: a byte that must not read as end of stream
            all.append((char) b);
        }
        ServletInputStream in = handedOn(request("/api/v1/orders/1", new Scripted(all.toString()), "Transfer-Encoding", "chunked")).getInputStream();

        assertThat(drain(in)).isEqualTo(all.toString().getBytes(StandardCharsets.ISO_8859_1));
    }

    @Test
    void theReaderOfAPeekedRequestSeesTheWholeBody() throws Exception {
        MockHttpServletRequest in = request("/api/v1/orders/1", new Scripted("hello"), "Transfer-Encoding", "chunked");
        in.setCharacterEncoding("UTF-8");

        HttpServletRequest out = handedOn(in);

        assertThat(out.getReader().readLine()).isEqualTo("hello");
    }

    @Test
    void nonBlockingReadsAreRefusedLikeOnAnyRequestThatIsNotAsync() throws Exception {
        HttpServletRequest none = handedOn(request("/api/v1/orders/1", new Scripted("")));
        HttpServletRequest peeked = handedOn(request("/api/v1/orders/1", new Scripted("x"), "Transfer-Encoding", "chunked"));

        assertThatIllegalStateException().isThrownBy(() -> none.getInputStream().setReadListener(null));
        assertThatIllegalStateException().isThrownBy(() -> peeked.getInputStream().setReadListener(null));
    }

    // ---- left alone ----------------------------------------------------------------------------------------------------------

    @Test
    void anotherProtocolThanHttp1IsNotTouched() throws Exception {
        Scripted stream = new Scripted("data");
        MockHttpServletRequest in = request("/api/v1/orders/1", stream);
        in.setProtocol("HTTP/2.0");                                          // a body needs no length header there

        assertThat(handedOn(in)).isSameAs(in);
        assertThat(stream.reads).isZero();
    }

    @Test
    void theActuatorIsNotTouched() throws Exception {
        Scripted stream = new Scripted("");
        MockHttpServletRequest in = request("/actuator/health/liveness", stream, "Transfer-Encoding", "chunked");

        assertThat(handedOn(in)).isSameAs(in);
        assertThat(stream.reads).isZero();
    }
}
