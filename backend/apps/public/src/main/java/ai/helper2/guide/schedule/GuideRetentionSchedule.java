package ai.helper2.guide.schedule;

import ai.helper2.guide.SimilarQuestionRetention;
import ai.helper2.guide.usecase.PurgeExpiredData;
import ai.helper2.platform.DailyJob;
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
