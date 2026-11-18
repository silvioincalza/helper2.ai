package ai.helper2.search.internal;

import ai.helper2.search.ShadowLibraries;
import ai.helper2.search.ShadowLibraryStore;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;

@Configuration(proxyBeanMethods = false)
class ShadowLibrariesConfiguration {

  private static final Logger log = LoggerFactory.getLogger(ShadowLibrariesConfiguration.class);

  static final Duration REFRESH = Duration.ofSeconds(30);

  @Bean
  JdbcShadowLibraryStore shadowLibraryStore(JdbcClient jdbc) {
    return new JdbcShadowLibraryStore(jdbc);
  }

  @Bean
  ShadowLibraries shadowLibraries(ShadowLibraryStore shadowLibraryStore) {
    ShadowLibraries libraries = ShadowLibraries.readFrom(shadowLibraryStore);
    Refresh.readSafely(libraries);
    return libraries;
  }

  @Bean(initMethod = "start", destroyMethod = "stop")
  Refresh shadowLibrariesRefresh(ShadowLibraries shadowLibraries) {
    return new Refresh(shadowLibraries);
  }

  static final class Refresh {
    private final ShadowLibraries libraries;
    private ScheduledExecutorService executor;

    Refresh(ShadowLibraries libraries) {
      this.libraries = libraries;
    }

    void start() {
      executor =
          Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "shadow-libraries");
            t.setDaemon(true);
            return t;
          });
      executor.scheduleWithFixedDelay(
          () -> readSafely(libraries),
          REFRESH.toMillis(),
          REFRESH.toMillis(),
          TimeUnit.MILLISECONDS);
    }

    void stop() {
      if (executor != null) {
        executor.shutdownNow();
      }
    }

    static void readSafely(ShadowLibraries libraries) {
      try {
        libraries.reload();
      } catch (RuntimeException e) {
        log.warn(
            "shadow libraries not read, the last list stays: {}", e.getClass().getSimpleName());
      }
    }
  }
}
