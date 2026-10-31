package ai.helper2.platform.cache;

import ai.helper2.platform.resilience.Resilience;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The shared caches of model answers, one {@link SharedCache} per name, all in the PostgreSQL table {@code ai_cache}
 * (ADR 0005). Each statement runs under the {@code db-cache} policy (ADR 0033) and stops after {@link #STATEMENT_TIMEOUT},
 * so a slow or unavailable database costs a lookup at most that long before it is a miss.
 */
public final class SharedCaches {

    static final String POLICY = "db-cache";
    static final Duration STATEMENT_TIMEOUT = Duration.ofSeconds(1);

    private final JdbcClient jdbc;
    private final Resilience resilience;
    private final MeterRegistry meters;

    public SharedCaches(DataSource dataSource, Resilience resilience, MeterRegistry meters) {
        resilience.require(POLICY);
        JdbcTemplate timed = new JdbcTemplate(dataSource);
        timed.setQueryTimeout((int) STATEMENT_TIMEOUT.toSeconds());
        this.jdbc = JdbcClient.create(timed);
        this.resilience = resilience;
        this.meters = meters;
    }

    public SharedCache named(String name) {
        return new JdbcSharedCache(name, jdbc, resilience, meters);
    }
}
