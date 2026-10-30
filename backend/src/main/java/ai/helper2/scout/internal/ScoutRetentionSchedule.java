package ai.helper2.scout.internal;

import ai.helper2.platform.ClusterLock;
import ai.helper2.platform.ClusterLocks;
import ai.helper2.scout.RunHistoryRetention;
import ai.helper2.scout.usecase.PurgeExpiredData;
import java.time.Clock;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.Scheduled;

/** Runs the Scout's retention once a day (helper2.retention.cron, UTC), on one replica at a time. */
@Configuration(proxyBeanMethods = false)
class ScoutRetentionSchedule {

    private static final Logger log = LoggerFactory.getLogger(ScoutRetentionSchedule.class);

    @Bean("scoutPurgeExpiredData")
    PurgeExpiredData scoutPurgeExpiredData(RunHistoryRetention history, Clock clock,
                                           @Value("${helper2.retention.scout-runs}") Duration scoutRuns) {
        return new PurgeExpiredData(history, clock, scoutRuns);
    }

    @Bean
    Daily scoutRetentionDaily(PurgeExpiredData scoutPurgeExpiredData, ClusterLocks locks) {
        return new Daily(scoutPurgeExpiredData, locks.keptAlive("retention-scout", Duration.ofMinutes(1)));
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
                log.warn("scout retention failed: {}", e.getClass().getSimpleName());
            }
        }
    }
}
