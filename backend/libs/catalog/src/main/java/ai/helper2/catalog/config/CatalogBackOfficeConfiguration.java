package ai.helper2.catalog.config;

import ai.helper2.catalog.AwesomeListIndex;
import ai.helper2.catalog.CatalogRetention;
import ai.helper2.catalog.CatalogWriter;
import ai.helper2.catalog.CategoryLabels;
import ai.helper2.catalog.JobMarkers;
import ai.helper2.catalog.KnownTarget;
import ai.helper2.catalog.KnownTargetList;
import ai.helper2.catalog.RelationshipTargets;
import ai.helper2.catalog.Resources;
import ai.helper2.catalog.internal.CatalogBackOfficeAdapters;
import ai.helper2.catalog.usecase.BackfillCategories;
import ai.helper2.catalog.usecase.ForgetWithdrawnRecords;
import ai.helper2.catalog.usecase.IndexAwesomeLists;
import ai.helper2.catalog.usecase.IndexResources;
import ai.helper2.catalog.usecase.LinkKnownRelationshipTargets;
import ai.helper2.catalog.usecase.ListWellKnownResources;
import ai.helper2.catalog.usecase.LookUpResourceTitles;
import ai.helper2.catalog.usecase.ManageKnownTargets;
import ai.helper2.catalog.usecase.PurgeExpiredData;
import ai.helper2.platform.AuditTrail;
import ai.helper2.platform.ClusterLocks;
import ai.helper2.shared.CategoryCatalog;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.modulith.NamedInterface;
import org.springframework.transaction.annotation.Transactional;

@NamedInterface("backoffice")
@Import(CatalogBackOfficeAdapters.class)
public class CatalogBackOfficeConfiguration {

  @Bean
  BackfillCategories backfillCategories(
      CategoryLabels labels,
      JobMarkers markers,
      CategoryCatalog catalog,
      ClusterLocks locks,
      AuditTrail audit,
      MeterRegistry meters) {
    return new BackfillCategories(
        labels,
        markers,
        catalog,
        locks.keptAlive("category-backfill", Duration.ofMinutes(1)),
        audit,
        meters,
        task -> Thread.ofVirtual().name("category-backfill").start(task));
  }

  @Bean
  LinkKnownRelationshipTargets linkKnownRelationshipTargets(RelationshipTargets targets) {
    return new LinkKnownRelationshipTargets(targets);
  }

  @Bean
  ManageKnownTargets manageKnownTargets(KnownTargetList targets, AuditTrail audit, Clock clock) {
    return new TransactionalManageKnownTargets(targets, audit, clock);
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
  ListWellKnownResources listWellKnownResources(Resources resources) {
    return new ListWellKnownResources(resources);
  }

  @Bean
  LookUpResourceTitles lookUpResourceTitles(Resources resources) {
    return new LookUpResourceTitles(resources);
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

  static class TransactionalManageKnownTargets extends ManageKnownTargets {

    TransactionalManageKnownTargets(KnownTargetList targets, AuditTrail audit, Clock clock) {
      super(targets, audit, clock);
    }

    @Override
    @Transactional
    public Saved add(
        String by,
        KnownTarget.Kind kind,
        @Nullable String name,
        @Nullable List<String> otherNames) {
      return super.add(by, kind, name, otherNames);
    }

    @Override
    @Transactional
    public Saved addDiscovered(
        String by,
        KnownTarget.Kind kind,
        @Nullable String name,
        @Nullable List<String> otherNames) {
      return super.addDiscovered(by, kind, name, otherNames);
    }

    @Override
    @Transactional
    public Saved change(
        String by,
        UUID id,
        KnownTarget.Kind kind,
        @Nullable String name,
        @Nullable List<String> otherNames) {
      return super.change(by, id, kind, name, otherNames);
    }

    @Override
    @Transactional
    public Removed remove(String by, UUID id) {
      return super.remove(by, id);
    }
  }
}
