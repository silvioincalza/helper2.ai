package ai.helper2.search.config;

import ai.helper2.platform.ClusterLock;
import ai.helper2.platform.ClusterLocks;
import ai.helper2.search.WebResultRetention;
import ai.helper2.search.usecase.PurgeExpiredData;
import java.time.Clock;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.Scheduled;

/** Runs the search module's retention once a day (helper2.retention.cron, UTC), on one replica at a time. */
@Configuration(proxyBeanMethods = false)
class SearchRetentionSchedule {

    private static final Logger log = LoggerFactory.getLogger(SearchRetentionSchedule.class);

    @Bean("searchPurgeExpiredData")
    PurgeExpiredData searchPurgeExpiredData(WebResultRetention retention, Clock clock,
                                            @Value("${helper2.retention.tavily-results}") Duration webResults) {
        return new PurgeExpiredData(retention, clock, webResults);
    }

    @Bean
    Daily searchRetentionDaily(PurgeExpiredData searchPurgeExpiredData, ClusterLocks locks) {
        return new Daily(searchPurgeExpiredData, locks.keptAlive("retention-search", Duration.ofMinutes(1)));
    }

    static final class Daily {
        private final PurgeExpiredData purge;
        private final ClusterLock lock;

        Daily(PurgeExpiredData purge, ClusterLock lock) {
            this.purge = purge;
            this.lock = lock;
        }

        @Scheduled(cron = "${helper2.retention.cron}", zone = "UTC")
        void run() {
            try {
                lock.tryRun(purge::purge);
            } catch (RuntimeException e) {
                log.warn("search retention failed: {}", e.getClass().getSimpleName());
            }
        }
    }
}
