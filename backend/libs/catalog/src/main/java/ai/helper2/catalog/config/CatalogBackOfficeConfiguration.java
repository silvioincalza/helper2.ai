package ai.helper2.catalog.config;

import ai.helper2.catalog.CatalogRetention;
import ai.helper2.catalog.CatalogWriter;
import ai.helper2.catalog.internal.CatalogBackOfficeAdapters;
import ai.helper2.catalog.usecase.ForgetWithdrawnRecords;
import ai.helper2.catalog.usecase.PurgeExpiredData;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.modulith.NamedInterface;

@NamedInterface("backoffice")
@Import(CatalogBackOfficeAdapters.class)
public class CatalogBackOfficeConfiguration {

  @Bean
  ForgetWithdrawnRecords forgetWithdrawnRecords(CatalogWriter writer) {
    return new ForgetWithdrawnRecords(writer);
  }

  @Bean
  PurgeExpiredData catalogPurgeExpiredData(
      CatalogRetention retention, Clock clock, CatalogRetentionProperties periods) {
    return new PurgeExpiredData(
        retention,
        clock,
        new PurgeExpiredData.Periods(
            periods.resolvedReviewItems(),
            periods.resolvedIssueReports(),
            periods.enrichmentRuns(),
            periods.webNews(),
            periods.pastEvents(),
            periods.observedUnseen(),
            periods.listIndex()));
  }
}
