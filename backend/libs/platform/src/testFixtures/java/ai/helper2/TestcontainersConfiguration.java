package ai.helper2;

import ai.helper2.platform.schema.AppLogins;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.flywaydb.core.Flyway;
import org.springframework.boot.data.redis.autoconfigure.DataRedisConnectionDetails;
import org.springframework.boot.jdbc.autoconfigure.JdbcConnectionDetails;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.images.builder.Transferable;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Real PostgreSQL 18 and Redis 8, same images as compose.yaml.
 *
 * <p>One container of each is shared by every test context in the JVM. Without sharing, each
 * distinct Spring context starts its own pair, and with Ryuk disabled (needed on rootless Podman)
 * they pile up until the container VM runs out of resources. Tests isolate data themselves (unique
 * queries/ids), so sharing is safe.
 *
 * <p>As in a deployment (ADR 0004): the schema is migrated once here, as the owner (the container's
 * superuser), and the apps' roles get their logins ({@link AppLogins}); Redis has the apps' ACL
 * users of the chart (deploy/helm/helper2/files/redis-users.acl). A test context connects as {@code
 * helper2.test.client}: {@code h2_public} or {@code h2_backoffice} (each app's test config), so a
 * missing grant or Redis permission fails a test; the owner and Redis' default user otherwise (the
 * libraries' tests).
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

  public static final String POSTGRES_IMAGE = "pgvector/pgvector:0.8.6-pg18-trixie";
  public static final String REDIS_IMAGE = "redis:8.10.2-alpine";

  /** The chart's ACL users, read by the Redis container. */
  static final Path REDIS_USERS = Path.of("../deploy/helm/helper2/files/redis-users.acl");

  /** The client of a test context, from {@code helper2.test.client}. */
  public static final String CLIENT = "helper2.test.client";

  /** Every test client's password, in Postgres and in Redis: a test value, never a real one. */
  static final String TEST_PASSWORD = "test-only-0123456789abcdef0123456789abcdef";

  /**
   * Every cached Spring test context keeps its own pool (H2_DATABASE_POOL_SIZE, 10) on this one
   * container, and Spring keeps up to 32 contexts: Postgres' default of 100 connections runs out
   * ("too many clients already").
   */
  public static final PostgreSQLContainer POSTGRES =
      new PostgreSQLContainer(
              DockerImageName.parse(POSTGRES_IMAGE).asCompatibleSubstituteFor("postgres"))
          .withCommand("postgres", "-c", "max_connections=400");

  public static final GenericContainer<?> REDIS =
      new GenericContainer<>(DockerImageName.parse(REDIS_IMAGE))
          .withExposedPorts(6379)
          .withCopyToContainer(Transferable.of(redisConfig()), "/etc/redis-test.conf")
          .withCommand("redis-server", "/etc/redis-test.conf");

  static {
    POSTGRES.start();
    REDIS.start();
    migrateAsOwner();
  }

  private static void migrateAsOwner() {
    var owner =
        new DriverManagerDataSource(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    Flyway.configure().dataSource(owner).locations("classpath:db/migration").load().migrate();
    try {
      AppLogins.open(
          owner,
          AppLogins.ROLES.stream().collect(Collectors.toMap(role -> role, role -> TEST_PASSWORD)));
    } catch (SQLException e) {
      throw new IllegalStateException("Could not open the apps' logins", e);
    }
  }

  /** Redis' default user stays open (no password); the apps' users as the chart has them. */
  private static String redisConfig() {
    try (Stream<String> lines = Files.lines(REDIS_USERS)) {
      return lines
          .map(String::strip)
          .filter(line -> !line.isEmpty() && !line.startsWith("#"))
          .map(line -> {
            String[] user = line.split(" ", 2);
            return "user " + user[0] + " on >" + TEST_PASSWORD + " " + user[1];
          })
          .collect(Collectors.joining("\n", "", "\n"));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static String client(Environment environment) {
    String client = environment.getProperty(CLIENT, "owner");
    if (!client.equals("owner") && !AppLogins.ROLES.contains(client)) {
      throw new IllegalArgumentException(CLIENT + " must be owner or one of " + AppLogins.ROLES);
    }
    return client;
  }

  // destroyMethod = "": a context closing must not stop containers other cached contexts still use.
  @Bean(destroyMethod = "")
  PostgreSQLContainer postgresContainer() {
    return POSTGRES;
  }

  @Bean(destroyMethod = "")
  GenericContainer<?> redisContainer() {
    return REDIS;
  }

  @Bean
  JdbcConnectionDetails postgresConnection(Environment environment) {
    String client = client(environment);
    boolean owner = client.equals("owner");
    return new JdbcConnectionDetails() {
      @Override
      public String getUsername() {
        return owner ? POSTGRES.getUsername() : client;
      }

      @Override
      public String getPassword() {
        return owner ? POSTGRES.getPassword() : TEST_PASSWORD;
      }

      @Override
      public String getJdbcUrl() {
        return POSTGRES.getJdbcUrl();
      }
    };
  }

  @Bean
  DataRedisConnectionDetails redisConnection(Environment environment) {
    String client = client(environment);
    boolean owner = client.equals("owner");
    return new DataRedisConnectionDetails() {
      @Override
      public String getUsername() {
        return owner ? null : client;
      }

      @Override
      public String getPassword() {
        return owner ? null : TEST_PASSWORD;
      }

      @Override
      public Standalone getStandalone() {
        return Standalone.of(REDIS.getHost(), REDIS.getMappedPort(6379));
      }
    };
  }

  /**
   * The owner's connection (the container's superuser), for test data an app's role may not write
   * (a fixture's cleanup, a row a back office job would make).
   */
  public static JdbcClient ownerJdbc() {
    return JdbcClient.create(
        new DriverManagerDataSource(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));
  }

  private static volatile StringRedisTemplate admin;

  /**
   * Redis as its default user, the admin (like redis-cli in the cluster): for a test that lists or
   * clears keys (KEYS), which the apps' ACL users may not.
   */
  public static StringRedisTemplate adminRedis() {
    if (admin == null) {
      synchronized (TestcontainersConfiguration.class) {
        if (admin == null) {
          var factory =
              new LettuceConnectionFactory(
                  new RedisStandaloneConfiguration(REDIS.getHost(), REDIS.getMappedPort(6379)));
          factory.afterPropertiesSet();
          factory.start();
          admin = new StringRedisTemplate(factory);
        }
      }
    }
    return admin;
  }
}
