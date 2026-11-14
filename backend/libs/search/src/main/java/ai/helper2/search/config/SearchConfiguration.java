package ai.helper2.search.config;

import ai.helper2.platform.quota.QuotaCounter;
import ai.helper2.platform.quota.QuotaKeys;
import ai.helper2.platform.settings.RuntimeSetting;
import ai.helper2.platform.settings.RuntimeSettings;
import ai.helper2.search.DailyLimits;
import ai.helper2.search.LanguageDetector;
import ai.helper2.search.SourceHealth;
import ai.helper2.search.WebSearchQuota;
import io.micrometer.context.ContextExecutorService;
import io.micrometer.context.ContextSnapshotFactory;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class SearchConfiguration {

  @Bean(destroyMethod = "shutdownNow")
  ExecutorService searchExecutor() {
    return ContextExecutorService.wrap(
        Executors.newVirtualThreadPerTaskExecutor(), ContextSnapshotFactory.builder().build());
  }

  @Bean
  LanguageDetector languageDetector() {
    return new LanguageDetector();
  }

  @Bean
  SourceHealth sourceHealth() {
    return new SourceHealth();
  }

  @Bean
  WebSearchQuota webSearchQuota(
      QuotaCounter counter, QuotaKeys keys, RuntimeSettings runtime, SearchProperties search) {
    SearchProperties.Quota quota = search.web().quota();
    return new WebSearchQuota(
        counter,
        keys,
        new DailyLimits(quota.guest().search(), quota.guest().guide()),
        runtime.declareCap(
            RuntimeSetting.SEARCH_WEB_GLOBAL_DAILY_CREDITS, quota.globalDailyCredits()));
  }
}
