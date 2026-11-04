package ai.helper2;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Real PostgreSQL 18 and Redis 8, same images as compose.yaml.
 *
 * <p>One container of each is shared by every test context in the JVM. Without sharing, each
 * distinct Spring context starts its own pair, and with Ryuk disabled (needed on rootless Podman)
 * they pile up until the container VM runs out of resources. Tests isolate data themselves
 * (unique queries/ids), so sharing is safe.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

    public static final String POSTGRES_IMAGE = "pgvector/pgvector:0.8.6-pg18-trixie";
    public static final String REDIS_IMAGE = "redis:8.10.2-alpine";

    /**
     * Every cached Spring test context keeps its own pool (H2_DATABASE_POOL_SIZE, 10) on this one container, and
     * Spring keeps up to 32 contexts: Postgres' default of 100 connections runs out ("too many clients already").
     */
    static final PostgreSQLContainer POSTGRES =
        new PostgreSQLContainer(DockerImageName.parse(POSTGRES_IMAGE).asCompatibleSubstituteFor("postgres"))
            .withCommand("postgres", "-c", "max_connections=400");
    static final GenericContainer<?> REDIS =
        new GenericContainer<>(DockerImageName.parse(REDIS_IMAGE)).withExposedPorts(6379);

    static {
        POSTGRES.start();
        REDIS.start();
    }

    // destroyMethod = "": a context closing must not stop containers other cached contexts still use.
    @Bean(destroyMethod = "")
    @ServiceConnection
    PostgreSQLContainer postgresContainer() {
        return POSTGRES;
    }

    @Bean(destroyMethod = "")
    @ServiceConnection(name = "redis")
    GenericContainer<?> redisContainer() {
        return REDIS;
    }
}
