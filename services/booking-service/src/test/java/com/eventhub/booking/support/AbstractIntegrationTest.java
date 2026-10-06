package com.eventhub.booking.support;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;

/**
 * Base des tests d'integration : Postgres, Redis et RabbitMQ reels via Testcontainers.
 *
 * On teste contre les vraies technologies plutot qu'avec des mocks, parce que ce qu'on
 * veut valider ici (atomicite du script Lua Redis, FOR UPDATE SKIP LOCKED, confirmations
 * de publication RabbitMQ) n'existe tout simplement pas dans un double de test.
 *
 * Les conteneurs sont demarres une seule fois pour toute la JVM (pattern "singleton
 * container") : les redemarrer par classe de test couterait plusieurs dizaines de
 * secondes pour aucun gain d'isolation, l'etat etant remis a zero avant chaque test.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestSecurityConfig.class)
public abstract class AbstractIntegrationTest {

    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"));

    static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

    /**
     * RabbitMQ avec le plugin management met facilement plus d'une minute a annoncer
     * "Server startup complete" sur un poste charge : le delai par defaut de
     * Testcontainers (60 s) provoque des echecs qui n'ont rien a voir avec le code teste.
     */
    static final RabbitMQContainer RABBITMQ =
            new RabbitMQContainer(DockerImageName.parse("rabbitmq:3.12-management-alpine"))
                    .withStartupTimeout(Duration.ofMinutes(5));

    static {
        POSTGRES.start();
        REDIS.start();
        RABBITMQ.start();
    }

    @Autowired
    protected StringRedisTemplate redisTemplate;

    @DynamicPropertySource
    static void registerContainerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);

        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));

        registry.add("spring.rabbitmq.host", RABBITMQ::getHost);
        registry.add("spring.rabbitmq.port", RABBITMQ::getAmqpPort);
        registry.add("spring.rabbitmq.username", RABBITMQ::getAdminUsername);
        registry.add("spring.rabbitmq.password", RABBITMQ::getAdminPassword);
    }

    @BeforeEach
    void flushRedis() {
        redisTemplate.execute((org.springframework.data.redis.core.RedisCallback<Void>) connection -> {
            connection.serverCommands().flushAll();
            return null;
        });
    }
}
