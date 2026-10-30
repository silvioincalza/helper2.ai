package ai.helper2.platform;

import javax.sql.DataSource;
import net.javacrumbs.shedlock.core.DefaultLockingTaskExecutor;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.core.LockingTaskExecutor;
import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Locks shared by every replica, for work that must run on one of them at a time (the Scout, the embedding
 * indexer): a row per lock in the Postgres table {@code shedlock}. Expiry is computed with the database clock,
 * so pods with drifting clocks still agree on when a lock ends. A lock row names the pod that holds it
 * ({@code locked_by} = hostname). Bounded tasks (the embedding indexer) take a {@link ClusterLock} over
 * {@link LockingTaskExecutor}; tasks of unknown length (the Scout) take a lease kept alive by {@link ClusterLocks}.
 */
@Configuration(proxyBeanMethods = false)
class ClusterLockConfiguration {

    @Bean
    JdbcTemplateLockProvider lockProvider(DataSource dataSource) {
        return new JdbcTemplateLockProvider(JdbcTemplateLockProvider.Configuration.builder()
            .withJdbcTemplate(new JdbcTemplate(dataSource))
            .usingDbTime()
            .build());
    }

    @Bean
    LockingTaskExecutor lockingTaskExecutor(LockProvider lockProvider) {
        return new DefaultLockingTaskExecutor(lockProvider);
    }

    @Bean
    ClusterLocks clusterLocks(JdbcTemplateLockProvider lockProvider) {
        return new ClusterLocks(lockProvider);
    }
}
