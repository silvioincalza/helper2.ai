package ai.helper2.platform.cache;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The shared caches' retention (ADR 0034): an entry is deleted once it expired. Reads already ignore it, so the purge
 * only frees the space; it runs without the lookups' statement timeout.
 */
final class SharedCacheRetention {

    private static final Logger log = LoggerFactory.getLogger(SharedCacheRetention.class);

    private final JdbcClient jdbc;

    SharedCacheRetention(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Returns how many entries were deleted. */
    int purge() {
        int deleted = jdbc.sql("DELETE FROM ai_cache WHERE expires_at <= now()").update();
        log.info("shared cache retention: {} expired entr{} deleted", deleted, deleted == 1 ? "y" : "ies");
        return deleted;
    }
}
