package com.mercury.recommendation;

import com.mercury.recommendation.config.RecommendationProperties;
import com.mercury.recommendation.service.VectorIndex;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.IntSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Creating the collection when several callers get there at the same time, against a REAL Qdrant.
 *
 * Whether two initializers collide normally depends on timing, and so does what the one that loses sees next: for a
 * few milliseconds after refusing its create, Qdrant cannot show the collection that is being created (it answers
 * 500). Here a pass-through in front of Qdrant holds the requests back until all callers are at the same step, and
 * decides what the loser is shown, so each of these cases happens every time instead of now and then.
 */
@Testcontainers(disabledWithoutDocker = true)
class VectorIndexInitializationTests {

    @Container static final GenericContainer<?> QDRANT = new GenericContainer<>(DockerImageName.parse("qdrant/qdrant:v1.19.1")).withExposedPorts(6333);

    private static final int DIMENSIONS = 64;
    private static final float[] VECTOR = new float[DIMENSIONS];
    /** what one request to Qdrant may take in production, which is also how long the loser of a race keeps looking */
    private static final Duration QDRANT_TIMEOUT = Duration.ofSeconds(2);

    static {
        VECTOR[0] = 1;
    }

    private static String qdrant() {
        return "http://" + QDRANT.getHost() + ":" + QDRANT.getMappedPort(6333);
    }

    private static String newCollection() {
        return "products-" + UUID.randomUUID().toString().substring(0, 8);
    }

    /** an index as one instance of the service would have it (with more patience than in production, for slow machines) */
    private static VectorIndex index(String url, String collection, int dimensions) {
        return index(url, collection, dimensions, Duration.ofSeconds(10));
    }

    private static VectorIndex index(String url, String collection, int dimensions, Duration timeout) {
        return new VectorIndex(new RecommendationProperties(new RecommendationProperties.Qdrant(
                url, collection, dimensions, timeout, Duration.ofHours(1), 200), null, null, null));
    }

    private static List<Throwable> failuresOf(Caller... callers) throws Exception {
        List<Throwable> failures = new ArrayList<>();
        for (Caller caller : callers) {
            failures.add(caller.failure());
        }
        return failures.stream().filter(Objects::nonNull).toList();
    }

    @Test
    void twoInstancesThatCreateTheCollectionAtTheSameMomentBothEndUpWithIt() throws Exception {
        String collection = newCollection();
        try (Gate gate = new Gate(qdrant()).lettingCreatesThroughInGroupsOf(2)) {
            VectorIndex first = index(gate.url(), collection, DIMENSIONS);
            VectorIndex second = index(gate.url(), collection, DIMENSIONS);

            Caller a = Caller.of(first);                               // each finds no collection, each sends its create
            Caller b = Caller.of(second);

            assertThat(a.failure()).isNull();
            assertThat(b.failure()).isNull();
            assertThat(gate.createAnswers()).containsExactlyInAnyOrder(200, 409);      // Qdrant did refuse one of them
            first.upsert(UUID.randomUUID(), VECTOR);                                   // and both can use the collection
            assertThat(second.indexedCount()).isEqualTo(1);
        }
    }

    @Test
    void callersOfOneInstanceShareASingleInitialization() throws Exception {
        String collection = newCollection();
        try (Gate gate = new Gate(qdrant()).holdingChecks()) {
            VectorIndex index = index(gate.url(), collection, DIMENSIONS);
            List<Caller> callers = new ArrayList<>();
            for (int i = 0; i < 6; i++) {
                callers.add(Caller.of(index));
            }
            for (Caller caller : callers) {                            // all six are inside and none has an answer yet
                Waiting.untilParkedIn(caller.thread(), VectorIndex.class, "ensureCollection");
            }

            gate.releaseChecks();

            for (Caller caller : callers) {
                assertThat(caller.failure()).isNull();
            }
            assertThat(gate.checks()).isEqualTo(1);                    // one of them looked and created, the others waited for it
            assertThat(gate.createAnswers()).containsExactly(200);
            assertThat(index.indexedCount()).isZero();
        }
    }

    // ---- what the loser of a create race sees when it looks at the collection ------------------------------------

    @Test
    void aLoserThatLooksBeforeTheCollectionCanBeShownWaitsForItAndSucceeds() throws Exception {
        String collection = newCollection();
        try (Gate gate = new Gate(qdrant()).lettingCreatesThroughInGroupsOf(2).tellingTheLoser(500)) {
            VectorIndex first = index(gate.url(), collection, DIMENSIONS);
            VectorIndex second = index(gate.url(), collection, DIMENSIONS);

            Caller a = Caller.of(first);
            Caller b = Caller.of(second);

            assertThat(failuresOf(a, b)).isEmpty();                                    // both, in the call that collided
            assertThat(gate.createAnswers()).containsExactlyInAnyOrder(200, 409);
            assertThat(gate.answersToTheLoser()).containsExactly(500, 200);            // refused, not shown yet, then shown
            first.ensureCollection();                                                  // neither has anything left to ask
            second.ensureCollection();
            assertThat(gate.checks()).isEqualTo(4);
            first.upsert(UUID.randomUUID(), VECTOR);
            assertThat(second.indexedCount()).isEqualTo(1);
        }
    }

    @Test
    void theLoserKeepsLookingWhileQdrantCannotShowTheCollection() throws Exception {
        String collection = newCollection();
        try (Gate gate = new Gate(qdrant()).lettingCreatesThroughInGroupsOf(2).tellingTheLoser(500, 503, 502)) {
            Caller a = Caller.of(index(gate.url(), collection, DIMENSIONS));
            Caller b = Caller.of(index(gate.url(), collection, DIMENSIONS));

            assertThat(failuresOf(a, b)).isEmpty();
            assertThat(gate.answersToTheLoser()).containsExactly(500, 503, 502, 200);  // it stopped at the first real answer
        }
    }

    @Test
    void aLoserGivesUpWithQdrantsLastAnswerWhenTheCollectionIsNeverShown() throws Exception {
        String collection = newCollection();
        try (Gate gate = new Gate(qdrant()).lettingCreatesThroughInGroupsOf(2).tellingTheLoserForever(500, 503)) {
            Caller a = Caller.of(index(gate.url(), collection, DIMENSIONS, QDRANT_TIMEOUT));
            Caller b = Caller.of(index(gate.url(), collection, DIMENSIONS, QDRANT_TIMEOUT));

            assertThat(failuresOf(a, b)).singleElement().isInstanceOf(HttpServerErrorException.class);
            Caller loser = a.failure() != null ? a : b;
            List<Integer> looks = gate.answersToTheLoser();
            assertThat(looks).hasSizeBetween(2, 101);                                  // a pause apart, for as long as allowed, no longer
            assertThat(((HttpServerErrorException) loser.failure()).getResponseBodyAsString())
                    .contains("(answer " + looks.size() + ")");                        // the last one it got, not the first
            assertThat(loser.took()).isBetween(QDRANT_TIMEOUT.minusMillis(100), QDRANT_TIMEOUT.plusMillis(1500));
        }
    }

    @Test
    void theLoserOfTheRaceDoesNotAcceptACollectionThatHoldsOtherVectors() throws Exception {
        String collection = newCollection();
        try (Gate gate = new Gate(qdrant()).lettingCreatesThroughInGroupsOf(2)) {
            Caller ours = Caller.of(index(gate.url(), collection, DIMENSIONS));
            Caller theirs = Caller.of(index(gate.url(), collection, 8));               // same name, other vectors

            List<Throwable> failures = failuresOf(ours, theirs);

            assertThat(gate.createAnswers()).containsExactlyInAnyOrder(200, 409);
            assertThat(failures).singleElement().isInstanceOf(IllegalStateException.class);   // whoever lost: the 409 was no proof
            assertThat(failures.get(0)).hasMessageContaining(collection);
            assertThat(gate.answersToTheLoser()).containsExactly(200);                 // one look was enough to know
        }
    }

    @Test
    void aRefusedCreateStaysAnErrorWhenTheCollectionIsNotThereAfterAll() throws Exception {
        String collection = newCollection();
        try (Gate gate = new Gate(qdrant()).lettingCreatesThroughInGroupsOf(2).tellingTheLoser(404)) {
            Caller a = Caller.of(index(gate.url(), collection, DIMENSIONS));
            Caller b = Caller.of(index(gate.url(), collection, DIMENSIONS));

            assertThat(failuresOf(a, b)).singleElement().isInstanceOf(HttpClientErrorException.Conflict.class);
            assertThat(gate.answersToTheLoser()).containsExactly(404);                 // it did not look again
        }
    }

    @Test
    void anAnswerOtherThanNotShownYetIsNotWaitedOut() throws Exception {
        String collection = newCollection();
        try (Gate gate = new Gate(qdrant()).lettingCreatesThroughInGroupsOf(2).tellingTheLoser(403)) {
            Caller a = Caller.of(index(gate.url(), collection, DIMENSIONS));
            Caller b = Caller.of(index(gate.url(), collection, DIMENSIONS));

            assertThat(failuresOf(a, b)).singleElement().isInstanceOf(HttpClientErrorException.Forbidden.class);
            assertThat(gate.answersToTheLoser()).containsExactly(403);
        }
    }

    // ---- without a race ------------------------------------------------------------------------------------------

    @Test
    void aFirstCheckThatFailsIsNotRepeated() throws Exception {
        String collection = newCollection();
        try (Gate gate = new Gate(qdrant()).tellingTheFirstCheck(500)) {
            VectorIndex index = index(gate.url(), collection, DIMENSIONS);

            assertThatThrownBy(index::ensureCollection).isInstanceOf(HttpServerErrorException.class);

            assertThat(gate.checks()).isEqualTo(1);                    // the waiting belongs to a refused create, not to this check
            assertThat(gate.createAnswers()).isEmpty();
            index.ensureCollection();                                  // and the failure is not remembered
            assertThat(gate.createAnswers()).containsExactly(200);
        }
    }

    @Test
    void anExistingCollectionThatHoldsOtherVectorsIsNotTakenForOurs() {
        String collection = newCollection();
        index(qdrant(), collection, 8).ensureCollection();

        VectorIndex ours = index(qdrant(), collection, DIMENSIONS);

        assertThatThrownBy(ours::ensureCollection).isInstanceOf(IllegalStateException.class).hasMessageContaining(collection);
        assertThatThrownBy(() -> ours.upsert(UUID.randomUUID(), VECTOR)).isInstanceOf(IllegalStateException.class);
    }

    /** one call of ensureCollection() on a thread of its own */
    private record Caller(Thread thread, FutureTask<Void> call, AtomicLong nanos) {

        static Caller of(VectorIndex index) {
            AtomicLong nanos = new AtomicLong();
            FutureTask<Void> call = new FutureTask<>(() -> {
                long start = System.nanoTime();
                try {
                    index.ensureCollection();
                } finally {
                    nanos.set(System.nanoTime() - start);
                }
            }, null);
            Thread thread = new Thread(call, "initializer");
            thread.start();
            return new Caller(thread, call, nanos);
        }

        /** @return what the call failed with, or null when it succeeded */
        Throwable failure() throws Exception {
            try {
                call.get(30, TimeUnit.SECONDS);
                return null;
            } catch (ExecutionException e) {
                return e.getCause();
            }
        }

        /** how long the call took; ask after {@link #failure()} */
        Duration took() {
            return Duration.ofNanos(nanos.get());
        }
    }

    /**
     * Passes every request on to Qdrant and the answer back, but can hold back the two requests the initialization is
     * made of (the check whether the collection exists, and the create) and can answer the checks itself.
     */
    private static final class Gate implements AutoCloseable {

        private static final int PASS_ON = -1;

        private final String qdrant;
        private final HttpServer server;
        private final ExecutorService workers = Executors.newCachedThreadPool();
        private final HttpClient client = HttpClient.newHttpClient();
        private final List<Integer> createAnswers = new CopyOnWriteArrayList<>();
        private final List<Integer> answersToTheLoser = new CopyOnWriteArrayList<>();
        private final AtomicInteger checks = new AtomicInteger();
        private final AtomicInteger toldAtTheFirstCheck = new AtomicInteger(PASS_ON);
        private volatile IntSupplier toldToTheLoser = () -> PASS_ON;
        private volatile CountDownLatch createsToCollide = new CountDownLatch(0);
        private volatile CountDownLatch checksHeld = new CountDownLatch(0);
        private volatile boolean refused;

        Gate(String qdrant) throws IOException {
            this.qdrant = qdrant;
            this.server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
            server.createContext("/", this::handle);
            server.setExecutor(workers);
            server.start();
        }

        /** a create waits until that many of them have arrived, then they all reach Qdrant together */
        Gate lettingCreatesThroughInGroupsOf(int creators) {
            createsToCollide = new CountDownLatch(creators);
            return this;
        }

        /** the checks get no answer until {@link #releaseChecks()} */
        Gate holdingChecks() {
            checksHeld = new CountDownLatch(1);
            return this;
        }

        /**
         * What the caller whose create was refused gets to its next looks at the collection, one status per look: 5xx
         * is what Qdrant says while it cannot show the collection yet, 404 that there is none. Once these are used up,
         * and when nothing is given at all, a look is passed on as soon as Qdrant can show the collection.
         */
        Gate tellingTheLoser(int... statuses) {
            AtomicInteger next = new AtomicInteger();
            toldToTheLoser = () -> next.get() < statuses.length ? statuses[next.getAndIncrement()] : PASS_ON;
            return this;
        }

        /** the same, but these statuses over and over: the loser never gets to see the collection */
        Gate tellingTheLoserForever(int... statuses) {
            AtomicInteger next = new AtomicInteger();
            toldToTheLoser = () -> statuses[next.getAndIncrement() % statuses.length];
            return this;
        }

        /** the answer to the very first check, before any create */
        Gate tellingTheFirstCheck(int status) {
            toldAtTheFirstCheck.set(status);
            return this;
        }

        void releaseChecks() {
            checksHeld.countDown();
        }

        String url() {
            return "http://localhost:" + server.getAddress().getPort();
        }

        List<Integer> createAnswers() {
            return createAnswers;
        }

        /** the status of every look the refused caller took at the collection, in order */
        List<Integer> answersToTheLoser() {
            return answersToTheLoser;
        }

        int checks() {
            return checks.get();
        }

        private void handle(HttpExchange exchange) throws IOException {
            try (exchange) {
                String method = exchange.getRequestMethod();
                URI uri = exchange.getRequestURI();
                byte[] body = exchange.getRequestBody().readAllBytes();
                boolean theCollectionItself = uri.getRawPath().matches("/collections/[^/]+");
                boolean check = theCollectionItself && method.equals("GET");
                boolean create = theCollectionItself && method.equals("PUT");
                boolean fromTheLoser = check && refused;
                if (check) {
                    checks.incrementAndGet();
                    await(checksHeld);
                    int told = fromTheLoser ? toldToTheLoser.getAsInt() : toldAtTheFirstCheck.getAndSet(PASS_ON);
                    if (told != PASS_ON) {
                        int answer = fromTheLoser ? answersToTheLoser.size() + 1 : 0;
                        if (fromTheLoser) {
                            answersToTheLoser.add(told);
                        }
                        answer(exchange, told, ("{\"status\":{\"error\":\"" + said(told) + " (answer " + answer + ")\"}}").getBytes(StandardCharsets.UTF_8));
                        return;
                    }
                    if (fromTheLoser) {
                        untilQdrantCanShow(uri);
                    }
                }
                if (create) {
                    createsToCollide.countDown();
                    await(createsToCollide);
                }
                HttpResponse<byte[]> fromQdrant = send(method, uri, body);
                if (create) {
                    if (fromQdrant.statusCode() == 409) {
                        refused = true;
                    }
                    createAnswers.add(fromQdrant.statusCode());
                }
                if (fromTheLoser) {
                    answersToTheLoser.add(fromQdrant.statusCode());
                }
                answer(exchange, fromQdrant.statusCode(), fromQdrant.body());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException(e);
            }
        }

        /** Qdrant's own words for the statuses the tests make up */
        private static String said(int status) {
            return switch (status) {
                case 404 -> "Not found: Collection doesn't exist!";
                case 403 -> "Forbidden";
                default -> "Service internal error: 0 of 0 read operations failed";
            };
        }

        private HttpResponse<byte[]> send(String method, URI uri, byte[] body) throws IOException, InterruptedException {
            return client.send(HttpRequest.newBuilder(URI.create(qdrant + uri))
                    .method(method, HttpRequest.BodyPublishers.ofByteArray(body))
                    .header("Content-Type", "application/json").build(), HttpResponse.BodyHandlers.ofByteArray());
        }

        private void untilQdrantCanShow(URI collection) throws IOException, InterruptedException {
            long giveUp = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
            while (send("GET", collection, new byte[0]).statusCode() >= 500) {
                if (System.nanoTime() > giveUp) {
                    throw new IllegalStateException("Qdrant never showed the collection");
                }
                Thread.sleep(2);
            }
        }

        private static void await(CountDownLatch latch) throws InterruptedException {
            if (!latch.await(30, TimeUnit.SECONDS)) {
                throw new IllegalStateException("the gate was never opened");
            }
        }

        private static void answer(HttpExchange exchange, int status, byte[] body) throws IOException {
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, body.length == 0 ? -1 : body.length);
            if (body.length > 0) {
                exchange.getResponseBody().write(body);
            }
        }

        @Override
        public void close() {
            releaseChecks();
            server.stop(0);
            workers.shutdownNow();
            client.shutdownNow();
        }
    }
}
