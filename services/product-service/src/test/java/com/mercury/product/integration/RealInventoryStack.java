package com.mercury.product.integration;

import org.testcontainers.postgresql.PostgreSQLContainer;

import java.io.File;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * The real infrastructure behind the Product -> Inventory -> PostgreSQL integration test:
 *
 *  - one throw-away PostgreSQL container (Testcontainers) holding a database per service;
 *  - the real Inventory Service, built from ../inventory-service and started as its own
 *    process, so Product Service reaches it over genuine HTTP.
 *
 * Product Service itself runs in the test JVM (@SpringBootTest). Nothing has to be started
 * by hand, and no class from Inventory Service is on Product Service's classpath.
 */
final class RealInventoryStack {

    static final String PRODUCT_DB = "mercury_product";
    static final String INVENTORY_DB = "mercury_inventory";

    private static final Duration BUILD_TIMEOUT = Duration.ofMinutes(5);
    private static final Duration STARTUP_TIMEOUT = Duration.ofSeconds(120);

    private static RealInventoryStack instance;

    private final PostgreSQLContainer postgres;
    private final Path inventoryProject;
    private final Path logDirectory;
    private Process inventoryProcess;
    private int inventoryPort;

    private RealInventoryStack() {
        Path productProject = Path.of(System.getProperty("basedir", "")).toAbsolutePath();
        this.inventoryProject = productProject.resolveSibling("inventory-service");
        this.logDirectory = productProject.resolve("target").resolve("it-logs");

        // Not stopped explicitly: the Spring context still holds pooled connections until the
        // JVM exits. Testcontainers' Ryuk sidecar removes the container when this JVM is gone.
        this.postgres = new PostgreSQLContainer("postgres:16-alpine")
                .withDatabaseName(PRODUCT_DB)
                .withUsername("mercury")
                .withPassword("it-password");
    }

    static synchronized RealInventoryStack start() {
        if (instance == null) {
            RealInventoryStack stack = new RealInventoryStack();
            stack.boot();
            instance = stack;
        }
        return instance;
    }

    static synchronized void stopInventoryService() {
        if (instance != null) {
            instance.destroyInventoryProcess();
        }
    }

    // ---- connection details --------------------------------------------------------------

    String productJdbcUrl() {
        return jdbcUrl(PRODUCT_DB);
    }

    String inventoryJdbcUrl() {
        return jdbcUrl(INVENTORY_DB);
    }

    String dbUsername() {
        return postgres.getUsername();
    }

    String dbPassword() {
        return postgres.getPassword();
    }

    String inventoryBaseUrl() {
        return "http://localhost:" + inventoryPort;
    }

    Connection openInventoryDb() throws SQLException {
        return DriverManager.getConnection(inventoryJdbcUrl(), dbUsername(), dbPassword());
    }

    Connection openProductDb() throws SQLException {
        return DriverManager.getConnection(productJdbcUrl(), dbUsername(), dbPassword());
    }

    private String jdbcUrl(String database) {
        return "jdbc:postgresql://" + postgres.getHost() + ":" + postgres.getFirstMappedPort()
                + "/" + database;
    }

    // ---- start-up ------------------------------------------------------------------------

    private void boot() {
        try {
            Files.createDirectories(logDirectory);
            postgres.start();
            createInventoryDatabase();
            Path jar = buildInventoryJar();
            startInventoryProcess(jar);
        } catch (Exception e) {
            destroyInventoryProcess();
            throw new IllegalStateException(
                    "Could not start the real Product/Inventory stack: " + e.getMessage(), e);
        }
    }

    private void createInventoryDatabase() throws SQLException {
        try (Connection connection = DriverManager.getConnection(
                productJdbcUrl(), dbUsername(), dbPassword());
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE DATABASE " + INVENTORY_DB);
        }
    }

    /** Always rebuilds (incremental and quick when nothing changed) so a stale jar can't pass. */
    private Path buildInventoryJar() throws IOException, InterruptedException {
        File wrapper = inventoryProject.resolve("mvnw").toFile();
        if (!wrapper.canExecute()) {
            throw new IllegalStateException("Inventory Service not found at " + inventoryProject);
        }

        Path buildLog = logDirectory.resolve("inventory-build.log");
        Process build = new ProcessBuilder(
                wrapper.getPath(), "-B", "-q", "package", "-DskipTests")
                .directory(inventoryProject.toFile())
                .redirectErrorStream(true)
                .redirectOutput(buildLog.toFile())
                .start();

        if (!build.waitFor(BUILD_TIMEOUT.toSeconds(), TimeUnit.SECONDS)) {
            build.destroyForcibly();
            throw new IllegalStateException("Building Inventory Service timed out; see " + buildLog);
        }
        if (build.exitValue() != 0) {
            throw new IllegalStateException("Building Inventory Service failed:\n" + tail(buildLog));
        }

        try (Stream<Path> files = Files.list(inventoryProject.resolve("target"))) {
            List<Path> jars = files
                    .filter(f -> f.getFileName().toString().matches("inventory-service-.*\\.jar"))
                    .toList();
            if (jars.size() != 1) {
                throw new IllegalStateException("Expected exactly one Inventory jar, found " + jars);
            }
            return jars.get(0);
        }
    }

    private void startInventoryProcess(Path jar) throws IOException, InterruptedException {
        inventoryPort = freePort();
        Path log = logDirectory.resolve("inventory-service.log");

        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        ProcessBuilder builder = new ProcessBuilder(java, "-jar", jar.toString())
                .directory(inventoryProject.toFile())
                .redirectErrorStream(true)
                .redirectOutput(log.toFile());
        builder.environment().put("SERVER_PORT", String.valueOf(inventoryPort));
        // these tests are about stock and idempotency, not authentication (which has its own tests and its own black-box suite)
        builder.environment().put("SECURITY_ENABLED", "false");
        builder.environment().put("INVENTORY_DB_URL", inventoryJdbcUrl());
        builder.environment().put("POSTGRES_USER", dbUsername());
        builder.environment().put("POSTGRES_PASSWORD", dbPassword());

        inventoryProcess = builder.start();
        Runtime.getRuntime().addShutdownHook(new Thread(this::destroyInventoryProcess));

        awaitHealthy(log);
    }

    private void awaitHealthy(Path log) throws InterruptedException {
        HttpClient client = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder(
                URI.create(inventoryBaseUrl() + "/actuator/health")).GET().build();
        long deadline = System.nanoTime() + STARTUP_TIMEOUT.toNanos();

        while (System.nanoTime() < deadline) {
            if (!inventoryProcess.isAlive()) {
                throw new IllegalStateException(
                        "Inventory Service exited during start-up:\n" + tail(log));
            }
            try {
                HttpResponse<String> response =
                        client.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() == 200 && response.body().contains("\"UP\"")) {
                    return;
                }
            } catch (IOException notUpYet) {
                // keep polling
            }
            Thread.sleep(250);
        }
        throw new IllegalStateException(
                "Inventory Service did not become healthy in " + STARTUP_TIMEOUT + ":\n" + tail(log));
    }

    private void destroyInventoryProcess() {
        Process process = inventoryProcess;
        if (process == null || !process.isAlive()) {
            return;
        }
        process.destroy();
        try {
            if (!process.waitFor(20, TimeUnit.SECONDS)) {
                process.destroyForcibly();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
        }
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private static String tail(Path file) {
        try {
            List<String> lines = Files.readAllLines(file);
            return String.join("\n", lines.subList(Math.max(0, lines.size() - 40), lines.size()));
        } catch (IOException e) {
            return "(no log: " + e.getMessage() + ")";
        }
    }
}
