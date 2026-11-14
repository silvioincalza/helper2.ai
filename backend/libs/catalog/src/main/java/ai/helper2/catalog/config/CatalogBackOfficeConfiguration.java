package ai.helper2.catalog.config;

import ai.helper2.catalog.AwesomeListIndex;
import ai.helper2.catalog.CatalogRetention;
import ai.helper2.catalog.CatalogWriter;
import ai.helper2.catalog.CategoryLabels;
import ai.helper2.catalog.CuratedFields;
import ai.helper2.catalog.JobMarkers;
import ai.helper2.catalog.KnownTarget;
import ai.helper2.catalog.KnownTargetCandidates;
import ai.helper2.catalog.KnownTargetList;
import ai.helper2.catalog.RelationshipTargets;
import ai.helper2.catalog.Resources;
import ai.helper2.catalog.ReviewDecisions;
import ai.helper2.catalog.ReviewQueue;
import ai.helper2.catalog.internal.CatalogBackOfficeAdapters;
import ai.helper2.catalog.internal.CatalogSearchSource;
import ai.helper2.catalog.usecase.AddCuratedResource;
import ai.helper2.catalog.usecase.BackfillCategories;
import ai.helper2.catalog.usecase.ChangeRelationshipTarget;
import ai.helper2.catalog.usecase.CurateField;
import ai.helper2.catalog.usecase.EditKnownTargetCandidate;
import ai.helper2.catalog.usecase.ForgetWithdrawnRecords;
import ai.helper2.catalog.usecase.GetReviewItem;
import ai.helper2.catalog.usecase.IndexAwesomeLists;
import ai.helper2.catalog.usecase.IndexResources;
import ai.helper2.catalog.usecase.LinkKnownRelationshipTargets;
import ai.helper2.catalog.usecase.ListReviewItems;
import ai.helper2.catalog.usecase.ListWellKnownResources;
import ai.helper2.catalog.usecase.LookUpResourceTitles;
import ai.helper2.catalog.usecase.ManageKnownTargets;
import ai.helper2.catalog.usecase.PurgeExpiredData;
import ai.helper2.catalog.usecase.ResolveReviewItem;
import ai.helper2.platform.AuditTrail;
import ai.helper2.platform.ClusterLocks;
import ai.helper2.shared.CategoryCatalog;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.modulith.NamedInterface;
import org.springframework.transaction.annotation.Transactional;

@NamedInterface("backoffice")
@Import(CatalogBackOfficeAdapters.class)
public class CatalogBackOfficeConfiguration {

  @Bean
  AddCuratedResource addCuratedResource(
      CatalogWriter writer,
      Resources resources,
      @Qualifier("curatedSearchSource") CatalogSearchSource curated,
      Clock clock) {
    return new AddCuratedResource(writer, resources, curated.getDescriptor(), clock);
  }

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
  ChangeRelationshipTarget changeRelationshipTarget(
      RelationshipTargets targets, AddCuratedResource add, AuditTrail audit) {
    return new TransactionalChangeRelationshipTarget(targets, add, audit);
  }

  @Bean
  ManageKnownTargets manageKnownTargets(KnownTargetList targets, AuditTrail audit, Clock clock) {
    return new TransactionalManageKnownTargets(targets, audit, clock);
  }

  @Bean
  EditKnownTargetCandidate editKnownTargetCandidate(
      ReviewQueue queue, KnownTargetCandidates candidates, AuditTrail audit) {
    return new TransactionalEditKnownTargetCandidate(queue, candidates, audit);
  }

  @Bean
  CurateField curateField(CuratedFields fields, AuditTrail audit) {
    return new TransactionalCurateField(fields, audit);
  }

  @Bean
  GetReviewItem getReviewItem(ReviewQueue queue) {
    return new GetReviewItem(queue);
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
  ListReviewItems listReviewItems(ReviewQueue queue) {
    return new ListReviewItems(queue);
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

  @Bean
  ResolveReviewItem resolveReviewItem(
      ReviewDecisions decisions, ReviewQueue queue, AuditTrail audit) {
    return new TransactionalResolveReviewItem(decisions, queue, audit);
  }

  static class TransactionalEditKnownTargetCandidate extends EditKnownTargetCandidate {

    TransactionalEditKnownTargetCandidate(
        ReviewQueue queue, KnownTargetCandidates candidates, AuditTrail audit) {
      super(queue, candidates, audit);
    }

    @Override
    @Transactional
    public Outcome edit(
        UUID itemId,
        KnownTarget.Kind kind,
        @Nullable String name,
        @Nullable List<String> otherNames,
        String by) {
      return super.edit(itemId, kind, name, otherNames, by);
    }
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

  static class TransactionalChangeRelationshipTarget extends ChangeRelationshipTarget {

    TransactionalChangeRelationshipTarget(
        RelationshipTargets targets, AddCuratedResource add, AuditTrail audit) {
      super(targets, add, audit);
    }

    @Override
    @Transactional
    public void change(UUID itemId, Target target, String by) {
      super.change(itemId, target, by);
    }
  }

  static class TransactionalCurateField extends CurateField {

    TransactionalCurateField(CuratedFields fields, AuditTrail audit) {
      super(fields, audit);
    }

    @Override
    @Transactional
    public void set(UUID resourceId, String field, String value, String by) {
      super.set(resourceId, field, value, by);
    }

    @Override
    @Transactional
    public int drop(UUID resourceId, String field, String by) {
      return super.drop(resourceId, field, by);
    }
  }

  static class TransactionalResolveReviewItem extends ResolveReviewItem {

    TransactionalResolveReviewItem(ReviewDecisions decisions, ReviewQueue queue, AuditTrail audit) {
      super(decisions, queue, audit);
    }

    @Override
    @Transactional
    public Outcome resolve(UUID id, ReviewQueue.Status decision, String by, String note) {
      return super.resolve(id, decision, by, note);
    }

    @Override
    @Transactional
    public Outcome resolve(
        UUID id, ReviewQueue.Status decision, String by, String note, @Nullable UUID batch) {
      return super.resolve(id, decision, by, note, batch);
    }
  }
}
