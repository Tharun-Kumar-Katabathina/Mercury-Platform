package com.mercury.order.integration;

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
 * The real infrastructure behind the Order -> Product -> Inventory -> PostgreSQL tests:
 *
 *  - one throw-away PostgreSQL container (Testcontainers) with a database per service;
 *  - the real Product Service and Inventory Service, each built from its own folder next to
 *    this one and started as a separate process, so Order Service reaches them over genuine HTTP.
 *
 * Order Service itself runs in the test JVM (@SpringBootTest). Nothing has to be started by hand
 * and no class from Product or Inventory is on Order Service's classpath.
 */
final class RealServicesStack {

    static final String ORDER_DB = "mercury_order";
    static final String PRODUCT_DB = "mercury_product";
    static final String INVENTORY_DB = "mercury_inventory";

    private static final Duration BUILD_TIMEOUT = Duration.ofMinutes(5);
    private static final Duration STARTUP_TIMEOUT = Duration.ofSeconds(120);

    private static RealServicesStack instance;

    private final PostgreSQLContainer postgres;
    private final Path servicesDirectory;
    private final Path logDirectory;
    private final ServiceProcess inventory;
    private final ServiceProcess product;

    /** One real service running as its own process. */
    private final class ServiceProcess {
        final String name;
        final Path project;
        int port;
        Process process;

        ServiceProcess(String name) {
            this.name = name;
            this.project = servicesDirectory.resolve(name);
        }

        void build() throws IOException, InterruptedException {
            File wrapper = project.resolve("mvnw").toFile();
            if (!wrapper.canExecute()) {
                throw new IllegalStateException(name + " not found at " + project);
            }
            Path buildLog = logDirectory.resolve(name + "-build.log");
            Process build = new ProcessBuilder(wrapper.getPath(), "-B", "-q", "package", "-DskipTests")
                    .directory(project.toFile())
                    .redirectErrorStream(true)
                    .redirectOutput(buildLog.toFile())
                    .start();
            if (!build.waitFor(BUILD_TIMEOUT.toSeconds(), TimeUnit.SECONDS)) {
                build.destroyForcibly();
                throw new IllegalStateException("Building " + name + " timed out; see " + buildLog);
            }
            if (build.exitValue() != 0) {
                throw new IllegalStateException("Building " + name + " failed:\n" + tail(buildLog));
            }
        }

        Path jar() throws IOException {
            try (Stream<Path> files = Files.list(project.resolve("target"))) {
                List<Path> jars = files
                        .filter(f -> f.getFileName().toString().matches(name + "-.*\\.jar"))
                        .toList();
                if (jars.size() != 1) {
                    throw new IllegalStateException("Expected exactly one " + name + " jar, found " + jars);
                }
                return jars.get(0);
            }
        }

        void start(String databaseUrl, String dbUrlVariable) throws IOException, InterruptedException {
            if (port == 0) {
                port = freePort();
            }
            Path log = logDirectory.resolve(name + ".log");
            ProcessBuilder builder = new ProcessBuilder(
                    Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                    "-jar", jar().toString())
                    .directory(project.toFile())
                    .redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.appendTo(log.toFile()));
            builder.environment().put("SERVER_PORT", String.valueOf(port));
            builder.environment().put(dbUrlVariable, databaseUrl);
            builder.environment().put("POSTGRES_USER", dbUsername());
            builder.environment().put("POSTGRES_PASSWORD", dbPassword());
            process = builder.start();
            awaitHealthy(log);
        }

        void stop() {
            Process p = process;
            if (p == null || !p.isAlive()) {
                return;
            }
            p.destroy();
            try {
                if (!p.waitFor(20, TimeUnit.SECONDS)) {
                    p.destroyForcibly();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                p.destroyForcibly();
            }
        }

        String baseUrl() {
            return "http://localhost:" + port;
        }

        private void awaitHealthy(Path log) throws InterruptedException {
            HttpClient client = HttpClient.newHttpClient();
            HttpRequest request = HttpRequest.newBuilder(
                    URI.create(baseUrl() + "/actuator/health")).GET().build();
            long deadline = System.nanoTime() + STARTUP_TIMEOUT.toNanos();
            while (System.nanoTime() < deadline) {
                if (!process.isAlive()) {
                    throw new IllegalStateException(name + " exited during start-up:\n" + tail(log));
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
                    name + " did not become healthy in " + STARTUP_TIMEOUT + ":\n" + tail(log));
        }
    }

    private RealServicesStack() {
        Path orderProject = Path.of(System.getProperty("basedir", "")).toAbsolutePath();
        this.servicesDirectory = orderProject.getParent();
        this.logDirectory = orderProject.resolve("target").resolve("it-logs");
        this.inventory = new ServiceProcess("inventory-service");
        this.product = new ServiceProcess("product-service");

        // Not stopped explicitly: the Spring context still holds pooled connections until the JVM
        // exits. Testcontainers' Ryuk sidecar removes the container when this JVM is gone.
        this.postgres = new PostgreSQLContainer("postgres:16-alpine")
                .withDatabaseName(ORDER_DB)
                .withUsername("mercury")
                .withPassword("it-password");
    }

    static synchronized RealServicesStack start() {
        if (instance == null) {
            RealServicesStack stack = new RealServicesStack();
            stack.boot();
            instance = stack;
        }
        return instance;
    }

    static synchronized void stopServices() {
        if (instance != null) {
            instance.inventory.stop();
            instance.product.stop();
        }
    }

    // ---- control --------------------------------------------------------------------------

    void stopInventoryService() {
        inventory.stop();
    }

    void startInventoryService() throws IOException, InterruptedException {
        inventory.start(jdbcUrl(INVENTORY_DB), "INVENTORY_DB_URL");
    }

    // ---- connection details ---------------------------------------------------------------

    String orderJdbcUrl() {
        return jdbcUrl(ORDER_DB);
    }

    String dbUsername() {
        return postgres.getUsername();
    }

    String dbPassword() {
        return postgres.getPassword();
    }

    String productBaseUrl() {
        return product.baseUrl();
    }

    String inventoryBaseUrl() {
        return inventory.baseUrl();
    }

    Connection open(String database) throws SQLException {
        return DriverManager.getConnection(jdbcUrl(database), dbUsername(), dbPassword());
    }

    private String jdbcUrl(String database) {
        return "jdbc:postgresql://" + postgres.getHost() + ":" + postgres.getFirstMappedPort()
                + "/" + database;
    }

    // ---- start-up -------------------------------------------------------------------------

    private void boot() {
        try {
            Files.createDirectories(logDirectory);
            postgres.start();
            createDatabase(PRODUCT_DB);
            createDatabase(INVENTORY_DB);

            inventory.build();
            product.build();

            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                inventory.stop();
                product.stop();
            }));

            inventory.start(jdbcUrl(INVENTORY_DB), "INVENTORY_DB_URL");
            product.start(jdbcUrl(PRODUCT_DB), "PRODUCT_DB_URL");
        } catch (Exception e) {
            inventory.stop();
            product.stop();
            throw new IllegalStateException(
                    "Could not start the real Order/Product/Inventory stack: " + e.getMessage(), e);
        }
    }

    private void createDatabase(String name) throws SQLException {
        try (Connection connection = open(ORDER_DB);
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE DATABASE " + name);
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
