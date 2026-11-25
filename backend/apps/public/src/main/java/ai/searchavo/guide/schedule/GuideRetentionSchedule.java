package ai.searchavo.guide.schedule;

import ai.searchavo.guide.SimilarQuestionRetention;
import ai.searchavo.guide.usecase.PurgeExpiredData;
import ai.searchavo.platform.DailyJob;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class GuideRetentionSchedule {

  @Bean("guidePurgeExpiredData")
  PurgeExpiredData guidePurgeExpiredData(SimilarQuestionRetention retention, Clock clock) {
    return new PurgeExpiredData(retention, clock);
  }

  @Bean
  DailyJob guideRetention(PurgeExpiredData guidePurgeExpiredData) {
    return new DailyJob("guide", guidePurgeExpiredData::purge);
  }
}
