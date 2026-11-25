package ai.searchavo.catalog.config;

import ai.searchavo.catalog.AwesomeListIndex;
import ai.searchavo.catalog.CatalogRetention;
import ai.searchavo.catalog.CatalogWriter;
import ai.searchavo.catalog.CategoryInputs;
import ai.searchavo.catalog.CategoryLabels;
import ai.searchavo.catalog.CuratedFields;
import ai.searchavo.catalog.GoneLists;
import ai.searchavo.catalog.GoneWebLists;
import ai.searchavo.catalog.JobMarkers;
import ai.searchavo.catalog.KnownTarget;
import ai.searchavo.catalog.KnownTargetCandidates;
import ai.searchavo.catalog.KnownTargetList;
import ai.searchavo.catalog.ListNamings;
import ai.searchavo.catalog.RelationshipTargets;
import ai.searchavo.catalog.ResourceRemoval;
import ai.searchavo.catalog.Resources;
import ai.searchavo.catalog.ReviewDecisions;
import ai.searchavo.catalog.ReviewQueue;
import ai.searchavo.catalog.usecase.AddCuratedResource;
import ai.searchavo.catalog.usecase.BackfillCategories;
import ai.searchavo.catalog.usecase.ChangeRelationshipTarget;
import ai.searchavo.catalog.usecase.CurateField;
import ai.searchavo.catalog.usecase.EditKnownTargetCandidate;
import ai.searchavo.catalog.usecase.ForgetGoneLists;
import ai.searchavo.catalog.usecase.ForgetGoneWebList;
import ai.searchavo.catalog.usecase.ForgetWithdrawnRecords;
import ai.searchavo.catalog.usecase.GetReviewItem;
import ai.searchavo.catalog.usecase.IndexAwesomeLists;
import ai.searchavo.catalog.usecase.IndexResources;
import ai.searchavo.catalog.usecase.LinkKnownRelationshipTargets;
import ai.searchavo.catalog.usecase.ListReviewItems;
import ai.searchavo.catalog.usecase.ListWellKnownResources;
import ai.searchavo.catalog.usecase.LookUpResourceTitles;
import ai.searchavo.catalog.usecase.ManageKnownTargets;
import ai.searchavo.catalog.usecase.NameResourceByList;
import ai.searchavo.catalog.usecase.PurgeExpiredData;
import ai.searchavo.catalog.usecase.RemoveResource;
import ai.searchavo.catalog.usecase.ResolveReviewItem;
import ai.searchavo.platform.AuditTrail;
import ai.searchavo.platform.ClusterLocks;
import ai.searchavo.search.DescribedSource;
import ai.searchavo.shared.CategoryCatalog;
import ai.searchavo.shared.UnitOfWork;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.modulith.NamedInterface;
import org.springframework.transaction.annotation.Transactional;

@NamedInterface("backoffice")
@EnableCatalogBackOfficeAdapters
public class CatalogBackOfficeConfiguration {

  @Bean
  AddCuratedResource addCuratedResource(
      CatalogWriter writer,
      Resources resources,
      @Qualifier("curatedSearchSource") DescribedSource curated,
      Clock clock) {
    return new AddCuratedResource(writer, resources, curated.getDescriptor(), clock);
  }

  @Bean
  BackfillCategories backfillCategories(
      CategoryInputs inputs,
      CategoryLabels labels,
      JobMarkers markers,
      CategoryCatalog catalog,
      ClusterLocks locks,
      AuditTrail audit,
      MeterRegistry meters) {
    return new BackfillCategories(
        inputs,
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
      RelationshipTargets targets,
      AddCuratedResource add,
      AuditTrail audit,
      UnitOfWork unitOfWork) {
    return new ChangeRelationshipTarget(targets, add, audit, unitOfWork);
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
  CurateField curateField(CuratedFields fields, AuditTrail audit, Clock clock) {
    return new TransactionalCurateField(fields, audit, clock);
  }

  @Bean
  RemoveResource removeResource(ResourceRemoval removal, AuditTrail audit) {
    return new TransactionalRemoveResource(removal, audit);
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
  ForgetGoneLists forgetGoneLists(GoneLists lists, CategoryLabels labels, UnitOfWork unitOfWork) {
    return new ForgetGoneLists(lists, labels, unitOfWork);
  }

  @Bean
  ForgetGoneWebList forgetGoneWebList(
      GoneWebLists lists, CategoryLabels labels, UnitOfWork unitOfWork) {
    return new ForgetGoneWebList(lists, labels, unitOfWork);
  }

  @Bean
  NameResourceByList nameResourceByList(ListNamings namings, CategoryLabels labels) {
    return new NameResourceByList(namings, labels);
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
            periods.observedUnseen()));
  }

  @Bean
  ResolveReviewItem resolveReviewItem(
      ReviewDecisions decisions, ReviewQueue queue, AuditTrail audit) {
    return new TransactionalResolveReviewItem(decisions, queue, audit);
  }

  static class TransactionalRemoveResource extends RemoveResource {

    TransactionalRemoveResource(ResourceRemoval removal, AuditTrail audit) {
      super(removal, audit);
    }

    @Override
    @Transactional
    public Outcome remove(UUID resourceId, @Nullable String reason, String by) {
      return super.remove(resourceId, reason, by);
    }
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

  static class TransactionalCurateField extends CurateField {

    TransactionalCurateField(CuratedFields fields, AuditTrail audit, Clock clock) {
      super(fields, audit, clock);
    }

    @Override
    @Transactional
    public Outcome set(UUID resourceId, String field, String value, String by) {
      return super.set(resourceId, field, value, by);
    }

    @Override
    @Transactional
    public Outcome setDescription(
        UUID resourceId, String description, boolean writtenByAi, String by) {
      return super.setDescription(resourceId, description, writtenByAi, by);
    }

    @Override
    @Transactional
    public Outcome setPublished(UUID resourceId, LocalDate publishedAt, String by) {
      return super.setPublished(resourceId, publishedAt, by);
    }

    @Override
    @Transactional
    public DropOutcome drop(UUID resourceId, String field, String by) {
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
