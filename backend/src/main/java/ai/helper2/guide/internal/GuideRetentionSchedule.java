package ai.helper2.guide.internal;

import ai.helper2.guide.GuideAnswerRetention;
import ai.helper2.guide.usecase.PurgeExpiredData;
import ai.helper2.platform.ClusterLock;
import ai.helper2.platform.ClusterLocks;
import java.time.Clock;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.Scheduled;

/** Runs the guide module's retention once a day (helper2.retention.cron, UTC), on one replica at a time. */
@Configuration(proxyBeanMethods = false)
class GuideRetentionSchedule {

    private static final Logger log = LoggerFactory.getLogger(GuideRetentionSchedule.class);

    @Bean("guidePurgeExpiredData")
    PurgeExpiredData guidePurgeExpiredData(GuideAnswerRetention retention, Clock clock) {
        return new PurgeExpiredData(retention, clock);
    }

    @Bean
    Daily guideRetentionDaily(PurgeExpiredData guidePurgeExpiredData, ClusterLocks locks) {
        return new Daily(guidePurgeExpiredData, locks.keptAlive("retention-guide", Duration.ofMinutes(1)));
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
                log.warn("guide retention failed: {}", e.getClass().getSimpleName());
            }
        }
    }
}
