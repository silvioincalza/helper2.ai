package ai.helper2.catalog.config;

import ai.helper2.catalog.AwesomeListIndex;
import ai.helper2.catalog.CatalogRetention;
import ai.helper2.catalog.CatalogWriter;
import ai.helper2.catalog.CategoryLabels;
import ai.helper2.catalog.RelationshipTargets;
import ai.helper2.catalog.Resources;
import ai.helper2.catalog.internal.CatalogBackOfficeAdapters;
import ai.helper2.catalog.usecase.ForgetWithdrawnRecords;
import ai.helper2.catalog.usecase.IndexAwesomeLists;
import ai.helper2.catalog.usecase.IndexResources;
import ai.helper2.catalog.usecase.LinkKnownRelationshipTargets;
import ai.helper2.catalog.usecase.PurgeExpiredData;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.modulith.NamedInterface;

@NamedInterface("backoffice")
@Import(CatalogBackOfficeAdapters.class)
public class CatalogBackOfficeConfiguration {

  @Bean
  LinkKnownRelationshipTargets linkKnownRelationshipTargets(RelationshipTargets targets) {
    return new LinkKnownRelationshipTargets(targets);
  }

  @Bean
  IndexAwesomeLists indexAwesomeLists(AwesomeListIndex index, CategoryLabels labels) {
    return new IndexAwesomeLists(index, labels);
  }

  @Bean
  IndexResources indexResources(CatalogWriter writer, Resources resources) {
    return new IndexResources(writer, resources);
  }

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
