package ai.helper2.catalog;

import ai.helper2.catalog.usecase.PurgeExpiredData;
import ai.helper2.platform.ClusterLock;
import ai.helper2.platform.ClusterLocks;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Runs the catalog's retention once a day (helper2.retention.cron, UTC), on one replica at a time. */
@Component
class CatalogRetentionSchedule {

    private static final Logger log = LoggerFactory.getLogger(CatalogRetentionSchedule.class);

    private final PurgeExpiredData purge;
    private final ClusterLock lock;

    CatalogRetentionSchedule(PurgeExpiredData purge, ClusterLocks locks) {
        this.purge = purge;
        this.lock = locks.keptAlive("retention-catalog", Duration.ofMinutes(1));
    }

    @Scheduled(cron = "${helper2.retention.cron}", zone = "UTC")
    void daily() {
        try {
            lock.tryRun(purge::purge);
        } catch (RuntimeException e) {
            log.warn("catalog retention failed: {}", e.getClass().getSimpleName());
        }
    }
}
