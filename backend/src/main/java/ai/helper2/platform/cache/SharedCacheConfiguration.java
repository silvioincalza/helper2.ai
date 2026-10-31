package ai.helper2.platform.cache;

import ai.helper2.platform.ClusterLock;
import ai.helper2.platform.ClusterLocks;
import ai.helper2.platform.resilience.Resilience;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;

/** The shared caches, and their purge once a day (helper2.retention.cron, UTC) on one replica at a time. */
@Configuration(proxyBeanMethods = false)
class SharedCacheConfiguration {

    private static final Logger log = LoggerFactory.getLogger(SharedCacheConfiguration.class);

    @Bean
    SharedCaches sharedCaches(DataSource dataSource, Resilience resilience, MeterRegistry meters) {
        return new SharedCaches(dataSource, resilience, meters);
    }

    @Bean
    Daily sharedCacheRetentionDaily(JdbcClient jdbc, ClusterLocks locks) {
        return new Daily(new SharedCacheRetention(jdbc), locks.keptAlive("retention-shared-cache", Duration.ofMinutes(1)));
    }

    static final class Daily {
        private final SharedCacheRetention retention;
        private final ClusterLock lock;

        Daily(SharedCacheRetention retention, ClusterLock lock) {
            this.retention = retention;
            this.lock = lock;
        }

        @Scheduled(cron = "${helper2.retention.cron}", zone = "UTC")
        void run() {
            try {
                lock.tryRun(retention::purge);
            } catch (RuntimeException e) {
                log.warn("shared cache retention failed: {}", e.getClass().getSimpleName());
            }
        }
    }
}
