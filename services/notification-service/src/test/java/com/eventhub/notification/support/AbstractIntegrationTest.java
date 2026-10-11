package com.eventhub.notification.support;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;

/**
 * Base des tests d'integration : RabbitMQ, Redis et Mailhog reels via Testcontainers.
 *
 * Mailhog est le meme serveur SMTP de capture qu'en dev : le test verifie donc exactement
 * ce que demande la Definition of Done du lot 4, un email visible dans Mailhog. Les
 * conteneurs sont demarres une seule fois pour toute la JVM.
 */
@SpringBootTest
@ActiveProfiles("test")
public abstract class AbstractIntegrationTest {

    private static final int SMTP_PORT = 1025;
    private static final int MAILHOG_HTTP_PORT = 8025;

    /** Meme delai allonge que dans les autres services : RabbitMQ + management est lent a demarrer. */
    static final RabbitMQContainer RABBITMQ =
            new RabbitMQContainer(DockerImageName.parse("rabbitmq:3.12-management-alpine"))
                    .withStartupTimeout(Duration.ofMinutes(5));

    static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

    static final GenericContainer<?> MAILHOG =
            new GenericContainer<>(DockerImageName.parse("mailhog/mailhog:latest"))
                    .withExposedPorts(SMTP_PORT, MAILHOG_HTTP_PORT)
                    .waitingFor(Wait.forHttp("/api/v2/messages").forPort(MAILHOG_HTTP_PORT));

    static {
        RABBITMQ.start();
        REDIS.start();
        MAILHOG.start();
    }

    @DynamicPropertySource
    static void registerContainerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.rabbitmq.host", RABBITMQ::getHost);
        registry.add("spring.rabbitmq.port", RABBITMQ::getAmqpPort);
        registry.add("spring.rabbitmq.username", RABBITMQ::getAdminUsername);
        registry.add("spring.rabbitmq.password", RABBITMQ::getAdminPassword);

        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));

        registry.add("spring.mail.host", MAILHOG::getHost);
        registry.add("spring.mail.port", () -> MAILHOG.getMappedPort(SMTP_PORT));
    }

    protected static String mailhogApiUrl() {
        return "http://" + MAILHOG.getHost() + ":" + MAILHOG.getMappedPort(MAILHOG_HTTP_PORT);
    }
}
