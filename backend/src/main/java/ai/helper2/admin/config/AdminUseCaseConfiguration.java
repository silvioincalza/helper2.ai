package ai.helper2.admin.config;

import ai.helper2.admin.ExampleDrafts;
import ai.helper2.admin.usecase.GetOverview;
import ai.helper2.admin.usecase.GetReviewEvidence;
import ai.helper2.admin.usecase.ListEnrichmentQueue;
import ai.helper2.admin.usecase.ResolveReview;
import ai.helper2.admin.usecase.ResolveReviewItems;
import ai.helper2.catalog.ReviewEvidence;
import ai.helper2.catalog.ReviewQueue;
import ai.helper2.catalog.usecase.ResolveReviewItem;
import ai.helper2.identity.AuditTrail;
import ai.helper2.examples.usecase.AddHomeExample;
import ai.helper2.examples.usecase.CountHomeExamples;
import ai.helper2.identity.AuditTrail;
import ai.helper2.platform.ApiProblem;
import ai.helper2.platform.llm.BudgetLedger;
import ai.helper2.scout.EnrichmentBacklog;
import ai.helper2.scout.ScoutStore;
import ai.helper2.scout.usecase.IndexAcceptedCandidate;
import ai.helper2.search.WebSearchQuota;
import java.time.Clock;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.annotation.Transactional;

/**
 * The back office's use cases: plain classes over other modules' use cases and ports (ADR 0002).
 * The transactions of ResolveReview and GetReviewEvidence are declared here, on subclasses, so the
 * use cases themselves stay free of Spring.
 */
@Configuration(proxyBeanMethods = false)
class AdminUseCaseConfiguration {

  @Bean
  GetOverview getOverview(
      ReviewQueue reviewQueue,
      EnrichmentBacklog backlog,
      ScoutStore runs,
      BudgetLedger budgets,
      ObjectProvider<WebSearchQuota> webSearchQuota,
      CountHomeExamples homeExamples,
      Clock clock) {
    return new GetOverview(
        reviewQueue, backlog, runs, budgets, webSearchQuota::getIfAvailable, homeExamples, clock);
  }

  @Bean
  ListEnrichmentQueue listEnrichmentQueue(EnrichmentBacklog backlog) {
    return new ListEnrichmentQueue(backlog);
  }

  @Bean
  GetReviewEvidence getReviewEvidence(ReviewQueue queue, ReviewEvidence evidence) {
    return new ReadOnlyGetReviewEvidence(queue, evidence);
  }

  /** The item and its evidence read in one read-only transaction, so they agree. */
  static class ReadOnlyGetReviewEvidence extends GetReviewEvidence {

    ReadOnlyGetReviewEvidence(ReviewQueue queue, ReviewEvidence evidence) {
      super(queue, evidence);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<View> get(UUID itemId) {
      return super.get(itemId);
    }
  }

  @Bean
  ResolveReviewItems resolveReviewItems(
      ReviewQueue queue, ReviewEvidence evidence, ResolveReview resolve, AuditTrail audit) {
    return new ResolveReviewItems(queue, evidence, resolve, audit);
  }

  @Bean
  ResolveReview resolveReview(
      ReviewQueue queue,
      ResolveReviewItem resolve,
      AddHomeExample addExample,
      IndexAcceptedCandidate indexCandidate,
      ExampleDrafts drafts) {
    return new TransactionalResolveReview(queue, resolve, addExample, indexCandidate, drafts);
  }

  @Bean
  ResolveReviewItems resolveReviewItems(
      ReviewQueue queue, ReviewEvidence evidence, ResolveReview resolve, AuditTrail audit) {
    return new ResolveReviewItems(queue, evidence, resolve, audit);
  }

  /**
   * The addition and the decision in one transaction. A refused addition (an {@link ApiProblem})
   * does not roll back: its audit entry stays.
   */
  static class TransactionalResolveReview extends ResolveReview {

    TransactionalResolveReview(
        ReviewQueue queue,
        ResolveReviewItem resolve,
        AddHomeExample addExample,
        IndexAcceptedCandidate indexCandidate,
        ExampleDrafts drafts) {
      super(queue, resolve, addExample, indexCandidate, drafts);
    }

    @Override
    @Transactional(noRollbackFor = ApiProblem.class)
    public ResolveReviewItem.Outcome resolve(
        UUID id, ReviewQueue.Status decision, String adminId, @Nullable String note) {
      return super.resolve(id, decision, adminId, note);
    }

    @Override
    @Transactional(noRollbackFor = ApiProblem.class)
    public ResolveReviewItem.Outcome resolve(
        UUID id,
        ReviewQueue.Status decision,
        String adminId,
        @Nullable String note,
        @Nullable UUID batch) {
      return super.resolve(id, decision, adminId, note, batch);
    }
  }
}
