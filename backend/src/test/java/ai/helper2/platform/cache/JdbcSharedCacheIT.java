package ai.helper2.platform.cache;

import static org.assertj.core.api.Assertions.assertThat;

import ai.helper2.TestcontainersConfiguration;
import ai.helper2.platform.resilience.Resilience;
import ai.helper2.testing.TestPolicies;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** The shared cache on the real schema: two caches on one table stand for two replicas. */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class JdbcSharedCacheIT {

    private static final Duration DAY = Duration.ofHours(24);

    @Autowired
    DataSource dataSource;

    @Autowired
    JdbcClient jdbc;

    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();

    private SharedCaches caches(DataSource db, Resilience resilience) {
        return new SharedCaches(db, resilience, meters);
    }

    private double count(String cache, String result) {
        return meters.counter("helper2.shared.cache", "cache", cache, "result", result).count();
    }

    @Test
    void whatOneReplicaStoresAnotherReads_untilItExpires() {
        SharedCache a = caches(dataSource, Resilience.NONE).named("translation");
        SharedCache b = caches(dataSource, Resilience.NONE).named("translation");
        String key = a.key("fast", "v1", "it", UUID.randomUUID().toString());

        assertThat(b.get(key)).isNull();
        a.put(key, "MCP server postgres", DAY);

        assertThat(b.get(key)).isEqualTo("MCP server postgres");
        Instant expiresAt = jdbc.sql("SELECT expires_at FROM ai_cache WHERE cache_name = 'translation' AND key_hash = decode(:key, 'hex')")
            .param("key", key).query(Instant.class).single();
        assertThat(expiresAt).isBetween(Instant.now().plus(DAY).minusSeconds(60), Instant.now().plus(DAY).plusSeconds(60));
        assertThat(count("translation", "hit")).isEqualTo(1);
        assertThat(count("translation", "miss")).isEqualTo(1);
    }

    @Test
    void aSecondPutReplacesTheValueAndItsExpiry() {
        SharedCache cache = caches(dataSource, Resilience.NONE).named("tool-guide");
        String key = cache.key(UUID.randomUUID().toString());
        cache.put(key, "unavailable", Duration.ZERO);

        cache.put(key, "checked steps", DAY);

        assertThat(cache.get(key)).isEqualTo("checked steps");
    }

    @Test
    void anExpiredEntryIsAMiss_andThePurgeDeletesOnlyExpiredEntries() {
        SharedCaches caches = caches(dataSource, Resilience.NONE);
        SharedCache cache = caches.named("translation");
        String expired = cache.key(UUID.randomUUID().toString());
        String live = cache.key(UUID.randomUUID().toString());
        cache.put(expired, "old", Duration.ZERO);
        cache.put(live, "new", DAY);

        assertThat(cache.get(expired)).isNull();

        assertThat(new SharedCacheRetention(jdbc).purge()).isPositive();
        assertThat(jdbc.sql("SELECT count(*) FROM ai_cache WHERE key_hash IN (decode(:a, 'hex'), decode(:b, 'hex'))")
            .param("a", expired).param("b", live).query(Integer.class).single()).isEqualTo(1);
        assertThat(cache.get(live)).isEqualTo("new");
    }

    @Test
    void theSameKeyInAnotherCacheIsAnotherEntry() {
        SharedCaches caches = caches(dataSource, Resilience.NONE);
        SharedCache translations = caches.named("translation");
        String key = translations.key(UUID.randomUUID().toString());
        translations.put(key, "MCP server", DAY);

        assertThat(caches.named("query-vector").get(key)).isNull();
    }

    @Test
    void aSlowDatabaseIsAMissWithinTheStatementTimeout() throws SQLException {
        SharedCache cache = caches(dataSource, Resilience.NONE).named("translation");
        String key = cache.key(UUID.randomUUID().toString());
        try (Connection holder = dataSource.getConnection()) {
            holder.setAutoCommit(false);
            try (Statement lock = holder.createStatement()) {
                lock.execute("LOCK TABLE ai_cache IN ACCESS EXCLUSIVE MODE");
            }
            long started = System.nanoTime();

            assertThat(cache.get(key)).isNull();
            cache.put(key, "MCP server", DAY);

            assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(4));
            holder.rollback();
        }
        assertThat(cache.get(key)).isNull();
    }

    @Test
    void whileTheDatabaseIsDownEveryReadMissesAndWritesDoNothing_thenTheCircuitSparesIt() {
        CountingDataSource down = new CountingDataSource();
        SharedCache cache = caches(down, TestPolicies.dbCache(Clock.systemUTC())).named("translation");
        String key = cache.key("fast", "v1", "it", "server mcp per postgres");

        assertThat(cache.get(key)).isNull();
        cache.put(key, "MCP server postgres", DAY);
        assertThat(cache.get(key)).isNull();

        assertThat(down.connections).as("the open circuit refuses the next calls at once").isEqualTo(1);
        assertThat(count("translation", "miss")).isEqualTo(2);
    }

    /** No database listens on port 1: every connection fails at once. */
    private static final class CountingDataSource extends DriverManagerDataSource {
        int connections;

        CountingDataSource() {
            super("jdbc:postgresql://127.0.0.1:1/none", "none", "none");
        }

        @Override
        public Connection getConnection() throws SQLException {
            connections++;
            return super.getConnection();
        }
    }
}
