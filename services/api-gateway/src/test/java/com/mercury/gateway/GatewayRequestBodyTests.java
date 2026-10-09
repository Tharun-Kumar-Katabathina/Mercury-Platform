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
import java.util.Random;

import static com.mercury.gateway.security.TestTokens.bearer;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * What the gateway does with a request body, through a real port and the real servlet container (MockMvc has no container: its
 * streams never say a request is finished until they are read). The requests are written by hand, byte by byte, so that the
 * framing is exactly what these tests say it is: neither the JDK client nor any other has a say in it.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "mercury.security.enabled=true",
        "gateway.rate-limit.ip-per-second=1000", "gateway.rate-limit.ip-burst=1000",
        "gateway.rate-limit.user-per-second=1000", "gateway.rate-limit.user-burst=1000",
        "gateway.rate-limit.auth-per-minute=1000",
        "server.tomcat.connection-timeout=2s"})
class GatewayRequestBodyTests {

    static final StubDownstream DOWNSTREAM = new StubDownstream();
    private static final String CRLF = "\r\n";

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

    private record Reply(int status) { }

    /** the request line and headers of an authenticated call to the order service; the blank line that ends them is the caller's */
    private String request(String method, String... framing) {
        StringBuilder head = new StringBuilder(method + " /api/v1/orders/123 HTTP/1.1" + CRLF + "Host: gateway" + CRLF + "Connection: close" + CRLF
                + "Authorization: " + token + CRLF + "Content-Type: application/octet-stream" + CRLF);
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
            socket.getOutputStream().write(request);
            socket.getOutputStream().flush();
            return read(socket);
        }
    }

    private static Reply read(Socket socket) throws IOException {
        // the status line only: the container may keep the connection open after it, reading what the client never sent
        String statusLine = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.ISO_8859_1)).readLine();
        statusLine = statusLine == null ? "" : statusLine;
        return new Reply(statusLine.startsWith("HTTP/1.1 ") ? Integer.parseInt(statusLine.split(" ")[1]) : -1);
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
