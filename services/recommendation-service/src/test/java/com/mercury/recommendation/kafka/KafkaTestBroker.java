package com.mercury.recommendation.kafka;

import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.PortBinding;
import com.github.dockerjava.api.model.Ports;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.NewTopic;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

import static org.awaitility.Awaitility.await;

/**
 * A throw-away single-node Kafka (KRaft) for tests.
 *
 * Testcontainers' own KafkaContainer builds its advertised address in a start-up script that
 * resolves to 0.0.0.0 on this Docker setup, which Kafka refuses. This keeps the listener
 * configuration of docker-compose.yml, which is known to work, and binds a host port chosen up
 * front so the advertised address is correct (see freePort for which one).
 *
 * Like the broker in docker-compose.yml it runs on Kafka's own defaults for everything else: in
 * particular it creates a topic that does not exist, with one partition, when a client asks for it.
 */
public final class KafkaTestBroker extends GenericContainer<KafkaTestBroker> {

    /** below the ports an operating system or Docker assigns by itself, and below the broker's own internal listener (29092) */
    private static final int FIRST_PORT = 20_000;
    private static final int LAST_PORT = 28_999;

    private final int hostPort = freePort();

    public KafkaTestBroker() {
        super(DockerImageName.parse("apache/kafka:3.9.0"));
        withExposedPorts(9092);
        withEnv("KAFKA_NODE_ID", "1");
        withEnv("KAFKA_PROCESS_ROLES", "broker,controller");
        withEnv("KAFKA_LISTENERS", "INTERNAL://:29092,CONTROLLER://:9093,EXTERNAL://:9092");
        withEnv("KAFKA_ADVERTISED_LISTENERS", "INTERNAL://localhost:29092,EXTERNAL://localhost:" + hostPort);
        withEnv("KAFKA_LISTENER_SECURITY_PROTOCOL_MAP", "INTERNAL:PLAINTEXT,CONTROLLER:PLAINTEXT,EXTERNAL:PLAINTEXT");
        withEnv("KAFKA_INTER_BROKER_LISTENER_NAME", "INTERNAL");
        withEnv("KAFKA_CONTROLLER_QUORUM_VOTERS", "1@localhost:9093");
        withEnv("KAFKA_CONTROLLER_LISTENER_NAMES", "CONTROLLER");
        withEnv("KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR", "1");
        withEnv("KAFKA_TRANSACTION_STATE_LOG_REPLICATION_FACTOR", "1");
        withEnv("KAFKA_TRANSACTION_STATE_LOG_MIN_ISR", "1");
        withEnv("KAFKA_GROUP_INITIAL_REBALANCE_DELAY_MS", "0");
        withCreateContainerCmdModifier(cmd -> cmd.getHostConfig()
                .withPortBindings(new PortBinding(Ports.Binding.bindPort(hostPort), new ExposedPort(9092))));
        waitingFor(Wait.forLogMessage(".*Kafka Server started.*", 1));
    }

    public String bootstrapServers() {
        return "localhost:" + hostPort;
    }

    /** the topics the broker has, once it answers: asking does not create any */
    public Set<String> topics() {
        try (Admin admin = admin()) {
            return await().atMost(Duration.ofSeconds(60)).ignoreExceptions()
                    .until(() -> admin.listTopics().names().get(10, TimeUnit.SECONDS), names -> true);
        }
    }

    public void createTopic(String name, int partitions) throws Exception {
        try (Admin admin = admin()) {
            admin.createTopics(List.of(new NewTopic(name, partitions, (short) 1))).all().get(10, TimeUnit.SECONDS);
        }
    }

    public int partitionsOf(String topic) throws Exception {
        try (Admin admin = admin()) {
            return admin.describeTopics(List.of(topic)).allTopicNames().get(10, TimeUnit.SECONDS).get(topic).partitions().size();
        }
    }

    private Admin admin() {
        return Admin.create(Map.of("bootstrap.servers", bootstrapServers()));
    }

    /**
     * A host port for the broker. One of the system's own free ports (new ServerSocket(0)) is not safe with Docker Desktop:
     * macOS hands out a port there even while a running container is publishing it, no bind can tell, and Docker then
     * refuses to start the broker ("port is already allocated"). So this takes a port at random from a range that neither an
     * operating system nor Docker assigns by itself, and makes sure that nothing on this machine listens on it.
     */
    private static int freePort() {
        for (int attempt = 0; attempt < 50; attempt++) {
            int port = ThreadLocalRandom.current().nextInt(FIRST_PORT, LAST_PORT + 1);
            try (ServerSocket socket = new ServerSocket()) {
                socket.setReuseAddress(false);              // with it, a port that is taken on one address only would look free
                socket.bind(new InetSocketAddress(port));
                return port;
            } catch (IOException e) {
                // taken: another one
            }
        }
        throw new IllegalStateException("no free port between " + FIRST_PORT + " and " + LAST_PORT);
    }
}
