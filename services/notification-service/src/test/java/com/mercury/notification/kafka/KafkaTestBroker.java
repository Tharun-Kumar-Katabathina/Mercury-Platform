package com.mercury.notification.kafka;

import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.PortBinding;
import com.github.dockerjava.api.model.Ports;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

import java.io.IOException;
import java.net.ServerSocket;

/**
 * A throw-away single-node Kafka (KRaft) for tests.
 *
 * Testcontainers' own KafkaContainer builds its advertised address in a start-up script that
 * resolves to 0.0.0.0 on this Docker setup, which Kafka refuses. This keeps the listener
 * configuration of docker-compose.yml, which is known to work, and binds a host port chosen up
 * front so the advertised address is correct. The port stays the same when the container is paused
 * and unpaused, which is how the tests simulate a Kafka outage.
 */
public final class KafkaTestBroker extends GenericContainer<KafkaTestBroker> {

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

    private static int freePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
