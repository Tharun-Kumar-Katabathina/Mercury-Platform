package com.mercury.gateway;

import com.mercury.gateway.security.TestTokens;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;

import static com.mercury.gateway.security.TestTokens.bearer;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * What the gateway does with a request body, through a real port and the real servlet container (MockMvc has no container: its
 * streams never say a request is finished until they are read). The requests are written by hand, byte by byte, so that the
 * framing is exactly what these tests say it is: neither the JDK client nor any other has a say in it.
 * <p>
 * The size limit is 64 KB here. A declared length over it is refused before anything is read (GatewayEdgeTests); these tests are
 * about a chunked body, which declares none.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "mercury.security.enabled=true",
        "gateway.rate-limit.ip-per-second=1000", "gateway.rate-limit.ip-burst=1000",
        "gateway.rate-limit.user-per-second=1000", "gateway.rate-limit.user-burst=1000",
        "gateway.rate-limit.auth-per-minute=1000",
        "gateway.max-body-size=64KB",
        "server.tomcat.connection-timeout=2s"})
class GatewayRequestBodyTests {

    static final StubDownstream DOWNSTREAM = new StubDownstream();
    private static final String CRLF = "\r\n";
    private static final int LIMIT = 64 * 1024;

    @DynamicPropertySource
    static void wiring(DynamicPropertyRegistry registry) {
        registry.add("mercury.security.jwt.public-key", TestTokens::trustedPublicKeyPem);
        for (String name : new String[]{"USER", "PRODUCT", "ORDER", "NOTIFICATION", "RECOMMENDATION"}) {
            registry.add(name + "_SERVICE_URL", DOWNSTREAM::url);
        }
    }

    @AfterAll
    static void stop() {
        DOWNSTREAM.close();
    }

    @Value("${local.server.port}") private int port;

    private final String token = bearer(TestTokens.user("alice"));

    @BeforeEach
    void forgetEarlierCalls() {
        DOWNSTREAM.calls.clear();
        DOWNSTREAM.aborted.clear();
    }

    // ---- requests that carry no body ---------------------------------------------------------------------------------

    @Test
    void anEmptyChunkedPostIsForwardedAsACallWithNoBody() throws Exception {
        Reply reply = send(request("POST", "Transfer-Encoding: chunked") + CRLF + "0" + CRLF + CRLF);

        assertThat(reply.status()).isEqualTo(200);
        assertThat(DOWNSTREAM.calls).singleElement().satisfies(c -> {
            assertThat(c.method()).isEqualTo("POST");
            assertThat(c.authorization()).isEqualTo(token);
            assertThat(c.transferEncoding()).isNull();
            assertThat(c.body()).isEmpty();
        });
    }

    @Test
    void aGetADeleteAndAZeroLengthPostAreForwardedWithNoBody() throws Exception {
        assertThat(send(request("GET") + CRLF).status()).isEqualTo(200);
        assertThat(send(request("DELETE") + CRLF).status()).isEqualTo(200);
        assertThat(send(request("POST", "Content-Length: 0") + CRLF).status()).isEqualTo(200);

        assertThat(DOWNSTREAM.calls).extracting(StubDownstream.Call::method).containsExactly("GET", "DELETE", "POST");
        assertThat(DOWNSTREAM.calls).allSatisfy(c -> {
            assertThat(c.authorization()).isEqualTo(token);
            assertThat(c.transferEncoding()).isNull();
            assertThat(c.body()).isEmpty();
        });
    }

    // ---- requests that carry one -------------------------------------------------------------------------------------

    @Test
    void aChunkedBodyReachesTheServiceByteForByte() throws Exception {
        byte[] payload = binary(20_000, 1);
        // three chunks that do not end where a buffer would, the first one a single byte long
        byte[] body = chunked(slice(payload, 0, 1), slice(payload, 1, 9_999), slice(payload, 10_000, 10_000));

        Reply reply = send(request("POST", "Transfer-Encoding: chunked"), body);

        assertThat(reply.status()).isEqualTo(200);
        assertThat(DOWNSTREAM.calls).singleElement().satisfies(c -> {
            assertThat(c.authorization()).isEqualTo(token);
            assertThat(c.body()).isEqualTo(payload);
        });
    }

    @Test
    void aChunkedBodyWhoseFirstByteArrivesLateIsStillForwardedWhole() throws Exception {
        byte[] payload = binary(5_000, 2);

        Reply reply = sendInPieces(request("POST", "Transfer-Encoding: chunked"), Duration.ofMillis(250), chunked(payload));

        assertThat(reply.status()).isEqualTo(200);
        assertThat(DOWNSTREAM.calls).singleElement().satisfies(c -> assertThat(c.body()).isEqualTo(payload));
    }

    @Test
    void aBodyWithADeclaredLengthReachesTheServiceByteForByte() throws Exception {
        byte[] payload = binary(20_000, 3);

        Reply reply = send(request("POST", "Content-Length: " + payload.length), payload);

        assertThat(reply.status()).isEqualTo(200);
        assertThat(DOWNSTREAM.calls).singleElement().satisfies(c -> {
            assertThat(c.authorization()).isEqualTo(token);
            assertThat(c.body()).isEqualTo(payload);
        });
    }

    // ---- a chunked body declares no length, so the limit is counted as it is read -------------------------------------------

    @Test
    void aChunkedBodyExactlyAtTheLimitReachesTheServiceByteForByte() throws Exception {
        byte[] payload = binary(LIMIT, 4);
        byte[] body = chunked(slice(payload, 0, 1), slice(payload, 1, 30_000), slice(payload, 30_001, LIMIT - 30_001));

        Reply reply = send(request("POST", "Transfer-Encoding: chunked"), body);

        assertThat(reply.status()).isEqualTo(200);
        assertThat(DOWNSTREAM.calls).singleElement().satisfies(c -> assertThat(c.body()).isEqualTo(payload));
        assertThat(DOWNSTREAM.aborted).isEmpty();
    }

    @Test
    void aChunkedBodyOneByteOverTheLimitIsRefusedAndTheServiceGetsNoMoreThanTheLimit() throws Exception {
        Reply reply = send(request("POST", "Transfer-Encoding: chunked"), chunked(binary(LIMIT + 1, 5)));

        assertRefusedAsTooLarge(reply);
        assertTheServiceWasCutOffAtTheLimit();
    }

    @Test
    void aLimitCrossedInTheMiddleOfAChunkIsRefused() throws Exception {
        // the second chunk starts at 30,000 and the limit falls 35,536 bytes into it
        Reply reply = send(request("POST", "Transfer-Encoding: chunked"), chunked(binary(30_000, 6), binary(40_000, 7)));

        assertRefusedAsTooLarge(reply);
        assertTheServiceWasCutOffAtTheLimit();
    }

    @Test
    void aSmallContentLengthDoesNotLetAChunkedBodyPastTheLimit() throws Exception {
        // the container goes by the chunks and ignores the length: a check of the header alone would pass this one
        Reply reply = send(request("POST", "Transfer-Encoding: chunked", "Content-Length: 10"), chunked(binary(LIMIT + 5_000, 8)));

        assertRefusedAsTooLarge(reply);
        assertTheServiceWasCutOffAtTheLimit();
    }

    @Test
    void anOversizedChunkedRequestToTheLoginRouteIsRefused() throws Exception {
        // the login route is public: nobody has to have a token to send a body to it
        Reply reply = send(unauthenticated("POST", "/api/v1/auth/login", "Transfer-Encoding: chunked"), chunked(binary(LIMIT + 1_000, 9)));

        assertRefusedAsTooLarge(reply);
        assertTheServiceWasCutOffAtTheLimit();
    }

    @Test
    void anOversizedChunkedRequestWithoutATokenIsRefusedAs401AndNoServiceIsCalled() throws Exception {
        Reply reply = send(unauthenticated("POST", "/api/v1/orders/123", "Transfer-Encoding: chunked"), chunked(binary(LIMIT + 1_000, 10)));

        assertThat(reply.status()).isEqualTo(401);
        assertThat(DOWNSTREAM.calls).isEmpty();
        assertThat(DOWNSTREAM.aborted).isEmpty();
    }

    @Test
    void aSmallChunkedBodyIsStillForwardedByteForByte() throws Exception {
        byte[] payload = binary(1_000, 11);

        Reply reply = send(request("POST", "Transfer-Encoding: chunked"), chunked(slice(payload, 0, 1), slice(payload, 1, 999)));

        assertThat(reply.status()).isEqualTo(200);
        assertThat(DOWNSTREAM.calls).singleElement().satisfies(c -> assertThat(c.body()).isEqualTo(payload));
    }

    private void assertRefusedAsTooLarge(Reply reply) {
        assertThat(reply.status()).isEqualTo(413);
        assertThat(reply.header("content-type")).startsWith("application/json");
        assertThat(reply.header("connection")).as("the request body was not read to its end: no second request on this connection").isEqualTo("close");
        assertThat(reply.body()).contains("\"status\":413", "\"error\":\"PAYLOAD_TOO_LARGE\"", "larger than " + LIMIT + " bytes");
    }

    /** the service saw a request begin and was cut off, having received no more than the limit: it never got a complete oversized body */
    private void assertTheServiceWasCutOffAtTheLimit() {
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                assertThat(DOWNSTREAM.aborted).singleElement().satisfies(received -> assertThat(received).isPositive().isLessThanOrEqualTo(LIMIT)));
        assertThat(DOWNSTREAM.calls).isEmpty();
    }

    // ---- what the filter must not do ---------------------------------------------------------------------------------

    @Test
    void aRefusedRequestIsAnsweredWithoutWaitingForItsBody() throws Exception {
        // chunked, no token, and no body ever sent: if the gateway read the body before refusing, the answer would wait for the
        // container's 2s timeout
        byte[] refused = ("POST /api/v1/orders/123 HTTP/1.1" + CRLF + "Host: gateway" + CRLF + "Connection: close" + CRLF
                + "Transfer-Encoding: chunked" + CRLF + CRLF).getBytes(StandardCharsets.US_ASCII);
        assertThat(exchange(refused, 20_000).status()).isEqualTo(401);                 // the first call warms the path up

        long started = System.nanoTime();
        Reply reply = exchange(refused, 20_000);

        assertThat(reply.status()).isEqualTo(401);
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofMillis(1_500));
        assertThat(DOWNSTREAM.calls).isEmpty();
    }

    @Test
    void aClientThatNeverSendsItsChunkedBodyIsCutOffByTheContainersTimeout() throws Exception {
        // the one byte the filter reads is the read the proxy would make next, so it ends at the same connection timeout (2s here)
        long started = System.nanoTime();

        Reply reply = exchange((request("POST", "Transfer-Encoding: chunked") + CRLF).getBytes(StandardCharsets.US_ASCII), 20_000);

        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(15));
        assertThat(reply.status()).isNotEqualTo(200);                                   // an error status, or the connection closed (-1)
        assertThat(DOWNSTREAM.calls).isEmpty();
    }

    // ---- by-hand HTTP ------------------------------------------------------------------------------------------------

    private record Reply(int status, Map<String, String> headers, String body) {
        String header(String lowerCaseName) {
            return headers.getOrDefault(lowerCaseName, "");
        }
    }

    /** the request line and headers of an authenticated call to the order service; the blank line that ends them is the caller's */
    private String request(String method, String... framing) {
        StringBuilder head = new StringBuilder(method + " /api/v1/orders/123 HTTP/1.1" + CRLF + "Host: gateway" + CRLF + "Connection: close" + CRLF
                + "Authorization: " + token + CRLF + "Content-Type: application/octet-stream" + CRLF);
        for (String header : framing) {
            head.append(header).append(CRLF);
        }
        return head.toString();
    }

    /** like {@link #request}, to any path and without a token */
    private String unauthenticated(String method, String path, String... framing) {
        StringBuilder head = new StringBuilder(method + " " + path + " HTTP/1.1" + CRLF + "Host: gateway" + CRLF + "Connection: close" + CRLF
                + "Content-Type: application/json" + CRLF);
        for (String header : framing) {
            head.append(header).append(CRLF);
        }
        return head.toString();
    }

    private Reply send(String wholeRequest) throws IOException {
        return exchange(wholeRequest.getBytes(StandardCharsets.US_ASCII), 20_000);
    }

    private Reply send(String head, byte[] body) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write((head + CRLF).getBytes(StandardCharsets.US_ASCII));
        out.write(body);
        return exchange(out.toByteArray(), 20_000);
    }

    private Reply sendInPieces(String head, Duration pause, byte[] body) throws Exception {
        try (Socket socket = new Socket("127.0.0.1", port)) {
            socket.setSoTimeout(20_000);
            OutputStream out = socket.getOutputStream();
            out.write((head + CRLF).getBytes(StandardCharsets.US_ASCII));
            out.flush();
            Thread.sleep(pause);
            out.write(body);
            out.flush();
            return read(socket);
        }
    }

    private Reply exchange(byte[] request, int readTimeoutMillis) throws IOException {
        try (Socket socket = new Socket("127.0.0.1", port)) {
            socket.setSoTimeout(readTimeoutMillis);
            try {
                socket.getOutputStream().write(request);
                socket.getOutputStream().flush();
            } catch (IOException e) {
                // the gateway may have answered, and closed, before the whole body was sent: what matters is the answer
            }
            return read(socket);
        }
    }

    private static Reply read(Socket socket) throws IOException {
        BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.ISO_8859_1));
        // the status line and headers, then the body as far as it is declared: the container may keep the connection open after
        // that, reading what the client never sent, so this does not wait for the end of the stream
        String statusLine = in.readLine();
        if (statusLine == null || !statusLine.startsWith("HTTP/1.1 ")) {
            return new Reply(-1, Map.of(), "");
        }
        Map<String, String> headers = new HashMap<>();
        for (String line = in.readLine(); line != null && !line.isEmpty(); line = in.readLine()) {
            int colon = line.indexOf(':');
            headers.put(line.substring(0, colon).toLowerCase(), line.substring(colon + 1).trim());
        }
        StringBuilder body = new StringBuilder();
        int declared = Integer.parseInt(headers.getOrDefault("content-length", "0"));
        char[] buffer = new char[1024];
        while (body.length() < declared) {
            int n = in.read(buffer, 0, Math.min(buffer.length, declared - body.length()));
            if (n == -1) {
                break;
            }
            body.append(buffer, 0, n);
        }
        return new Reply(Integer.parseInt(statusLine.split(" ")[1]), headers, body.toString());
    }

    private static byte[] binary(int length, long seed) {
        byte[] bytes = new byte[length];
        new Random(seed).nextBytes(bytes);          // every byte value, including the ones that look like framing
        return bytes;
    }

    private static byte[] slice(byte[] from, int offset, int length) {
        byte[] part = new byte[length];
        System.arraycopy(from, offset, part, 0, length);
        return part;
    }

    /** the chunked transfer coding of these chunks, ending with the last-chunk */
    private static byte[] chunked(byte[]... chunks) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] chunk : chunks) {
            out.write((Integer.toHexString(chunk.length) + CRLF).getBytes(StandardCharsets.US_ASCII));
            out.write(chunk);
            out.write(CRLF.getBytes(StandardCharsets.US_ASCII));
        }
        out.write(("0" + CRLF + CRLF).getBytes(StandardCharsets.US_ASCII));
        return out.toByteArray();
    }
}
