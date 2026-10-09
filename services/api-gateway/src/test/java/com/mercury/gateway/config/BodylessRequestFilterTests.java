package com.mercury.gateway.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

/**
 * The filter's decisions, what the request it hands on promises about its stream (the Servlet input-stream contract), and the
 * size limit it applies to a chunked body: counted from the first byte it read ahead, never more than the limit handed out.
 */
class BodylessRequestFilterTests {

    private static final long MAX = 1_000;

    private final BodylessRequestFilter filter = new BodylessRequestFilter(MAX);

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
        int consumed() { return position; }
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

    private static String bytes(int count) {
        return "x".repeat(count);
    }

    private static ServletInputStream chunkedStream(BodylessRequestFilter filter, Scripted stream) throws Exception {
        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(request("/api/v1/orders/1", stream, "Transfer-Encoding", "chunked"), new MockHttpServletResponse(), chain);
        return ((HttpServletRequest) chain.getRequest()).getInputStream();
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

    // ---- the size limit on a chunked body --------------------------------------------------------------------------------------

    @Test
    void aChunkedBodyOfExactlyTheLimitPassesAndThenEndsWithOneProbeOfTheContainer() throws Exception {
        Scripted stream = new Scripted(bytes((int) MAX));
        ServletInputStream in = chunkedStream(filter, stream);

        assertThat(drain(in)).hasSize((int) MAX);

        assertThat(stream.consumed()).isEqualTo((int) MAX);
        assertThat(stream.reads).as("the bytes, and one more read to find out that the body had ended").isEqualTo((int) MAX + 1);
        assertThat(in.read()).isEqualTo(-1);
    }

    @Test
    void theFirstByteBeyondTheLimitFailsTheReadAndNoByteBeyondItIsHandedOut() throws Exception {
        Scripted stream = new Scripted(bytes((int) MAX + 1));
        ServletInputStream in = chunkedStream(filter, stream);

        for (int i = 0; i < MAX; i++) {
            assertThat(in.read()).isEqualTo('x');
        }
        assertThatExceptionOfType(BodylessRequestFilter.BodyTooLargeException.class).isThrownBy(in::read)
                .withMessage("The request body is larger than " + MAX + " bytes");

        assertThat(stream.consumed()).as("the one-byte probe, and nothing further").isEqualTo((int) MAX + 1);
    }

    @Test
    void theByteReadAheadCountsTowardsTheLimit() throws Exception {
        BodylessRequestFilter oneByte = new BodylessRequestFilter(1);

        assertThat(drain(chunkedStream(oneByte, new Scripted("a")))).hasSize(1);

        ServletInputStream two = chunkedStream(oneByte, new Scripted("ab"));
        assertThat(two.read()).isEqualTo('a');
        assertThatExceptionOfType(BodylessRequestFilter.BodyTooLargeException.class).isThrownBy(two::read);
    }

    @Test
    void aLimitOfZeroRefusesTheFirstByte() throws Exception {
        ServletInputStream in = chunkedStream(new BodylessRequestFilter(0), new Scripted("a"));

        assertThatExceptionOfType(BodylessRequestFilter.BodyTooLargeException.class).isThrownBy(in::read);
    }

    @Test
    void bulkReadsNeverHandOutMoreThanTheLimitAndTheNextOneProbesAndFails() throws Exception {
        Scripted stream = new Scripted(bytes((int) MAX + 500));
        ServletInputStream in = chunkedStream(filter, stream);

        byte[] buffer = new byte[4_096];
        int total = 0;
        while (total < MAX) {
            int n = in.read(buffer, 0, buffer.length);
            assertThat(n).isPositive();
            total += n;
            assertThat(total).isLessThanOrEqualTo((int) MAX);
        }

        assertThat(total).isEqualTo((int) MAX);
        assertThat(stream.consumed()).as("nothing beyond the limit has been read yet").isEqualTo((int) MAX);
        assertThatExceptionOfType(BodylessRequestFilter.BodyTooLargeException.class).isThrownBy(() -> in.read(buffer, 0, buffer.length));
        assertThat(stream.consumed()).as("the probe read one byte").isEqualTo((int) MAX + 1);
    }

    @Test
    void readAllBytesOfAnOversizedBodyFailsInsteadOfReturningIt() throws Exception {
        ServletInputStream in = chunkedStream(filter, new Scripted(bytes((int) MAX + 1)));

        assertThatExceptionOfType(BodylessRequestFilter.BodyTooLargeException.class).isThrownBy(in::readAllBytes);
    }

    @Test
    void aBulkReadAtTheLimitOfAnEndedBodyReturnsEndOfStream() throws Exception {
        ServletInputStream in = chunkedStream(filter, new Scripted(bytes((int) MAX)));
        drain(in);

        assertThat(in.read(new byte[8], 0, 8)).isEqualTo(-1);
    }

    @Test
    void theLimitMathDoesNotOverflowAtTheLargestLimit() throws Exception {
        BodylessRequestFilter unlimited = new BodylessRequestFilter(Long.MAX_VALUE);
        ServletInputStream in = chunkedStream(unlimited, new Scripted("hello"));

        assertThat(in.available()).isEqualTo(5);
        assertThat(new String(drain(in), StandardCharsets.ISO_8859_1)).isEqualTo("hello");
    }

    @Test
    void availableNeverPromisesMoreThanTheLimitAllows() throws Exception {
        ServletInputStream in = chunkedStream(new BodylessRequestFilter(3), new Scripted("abcdefgh"));

        assertThat(in.available()).isEqualTo(3);
        in.read();
        assertThat(in.available()).isEqualTo(2);
        in.readNBytes(2);
        assertThat(in.available()).isZero();
    }

    // ---- what the filter answers when the limit trips ----------------------------------------------------------------------------

    private MockHttpServletResponse run(FilterChain chain, MockHttpServletRequest request) throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, chain);
        return response;
    }

    @Test
    void anOversizedChunkedBodyIsAnsweredWithTheGatewaysUsual413AndTheConnectionIsClosed() throws Exception {
        MockHttpServletResponse response = run((req, res) -> req.getInputStream().readAllBytes(),
                request("/api/v1/orders/1", new Scripted(bytes((int) MAX + 1)), "Transfer-Encoding", "chunked"));

        assertThat(response.getStatus()).isEqualTo(413);
        assertThat(response.getContentType()).startsWith("application/json");
        assertThat(response.getHeader("Connection")).isEqualTo("close");
        assertThat(response.getContentAsString()).contains("\"status\":413", "\"error\":\"PAYLOAD_TOO_LARGE\"", "larger than " + MAX + " bytes");
    }

    @Test
    void theLimitFailureIsFoundWhereverTheProxyWrappedIt() throws Exception {
        FilterChain wrapped = (req, res) -> {
            throw new ServletException("Request processing failed", new IllegalStateException(new BodylessRequestFilter.BodyTooLargeException(MAX)));
        };
        FilterChain unchecked = (req, res) -> {
            throw new IllegalStateException(new IOException(new BodylessRequestFilter.BodyTooLargeException(MAX)));
        };

        assertThat(run(wrapped, request("/api/v1/orders/1", new Scripted("a"), "Transfer-Encoding", "chunked")).getStatus()).isEqualTo(413);
        assertThat(run(unchecked, request("/api/v1/orders/1", new Scripted("a"), "Transfer-Encoding", "chunked")).getStatus()).isEqualTo(413);
    }

    @Test
    void aResponseThatHasAlreadyBegunIsNotOverwrittenAndTheFailureIsPassedOn() throws Exception {
        // nothing can be said once the answer has started; the container then drops the connection. With this proxy the case
        // does not arise (so there is no real-server test for it): the JDK client reads the service's answer only after the whole
        // request body has been sent, and Spring does not enable Expect: 100-continue, the one handshake that reorders that.
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain begun = (req, res) -> {
            res.getWriter().write("{\"early\":true}");
            res.flushBuffer();
            throw new IOException(new BodylessRequestFilter.BodyTooLargeException(MAX));
        };

        assertThatExceptionOfType(IOException.class).isThrownBy(() -> filter.doFilter(
                        request("/api/v1/orders/1", new Scripted("a"), "Transfer-Encoding", "chunked"), response, begun))
                .withCauseInstanceOf(BodylessRequestFilter.BodyTooLargeException.class);

        assertThat(response.isCommitted()).isTrue();
        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getHeader("Connection")).isNull();
        assertThat(response.getContentAsString()).isEqualTo("{\"early\":true}");
    }

    @Test
    void aFailureThatIsNotTheLimitIsPassedOnNotAnswered() {
        FilterChain broken = (req, res) -> {
            throw new IOException("the service is gone");
        };
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertThatExceptionOfType(IOException.class).isThrownBy(() -> filter.doFilter(
                request("/api/v1/orders/1", new Scripted("a"), "Transfer-Encoding", "chunked"), response, broken)).withMessage("the service is gone");

        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    void onlyAChunkedRequestIsGivenTheCountingStreamAndTheLimit() throws Exception {
        // a declared length is the container's to enforce and RequestSizeFilter's to refuse: what arrives is handed on untouched
        Scripted stream = new Scripted(bytes((int) MAX * 3));
        MockHttpServletRequest declared = request("/api/v1/orders/1", stream, "Content-Length", String.valueOf(MAX * 3));
        declared.setContent(new byte[(int) MAX * 3]);

        assertThat(handedOn(declared)).isSameAs(declared);
        assertThat(stream.reads).isZero();
    }
}
