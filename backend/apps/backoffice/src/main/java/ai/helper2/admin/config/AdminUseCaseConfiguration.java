package ai.helper2.admin.config;

import ai.helper2.admin.usecase.ChangeRuntimeSetting;
import ai.helper2.admin.usecase.CheckAiWords;
import ai.helper2.admin.usecase.CheckAwesomeLists;
import ai.helper2.admin.usecase.DecideRelationshipItem;
import ai.helper2.admin.usecase.DecideRelationshipTargetGroup;
import ai.helper2.admin.usecase.DecideReviewItem;
import ai.helper2.admin.usecase.GetEffectiveSettings;
import ai.helper2.admin.usecase.GetOverview;
import ai.helper2.admin.usecase.GetReviewEvidence;
import ai.helper2.admin.usecase.GetRuntimeSettings;
import ai.helper2.admin.usecase.ListEnrichmentQueue;
import ai.helper2.admin.usecase.ListRelationshipTargetGroups;
import ai.helper2.admin.usecase.ListReviewQueue;
import ai.helper2.admin.usecase.ResetRuntimeSetting;
import ai.helper2.admin.usecase.ResolveReview;
import ai.helper2.admin.usecase.ResolveReviewItems;
import ai.helper2.admin.usecase.ReviewItemViews;
import ai.helper2.admin.usecase.ShowReviewItem;
import ai.helper2.catalog.AiCheckReport;
import ai.helper2.catalog.AwesomeListEntries;
import ai.helper2.catalog.AwesomeListSuspects;
import ai.helper2.catalog.KnownTargetCandidates;
import ai.helper2.catalog.RelationshipTargets;
import ai.helper2.catalog.ResourceRatings;
import ai.helper2.catalog.ReviewEvidence;
import ai.helper2.catalog.ReviewQueue;
import ai.helper2.catalog.enrichment.ValidationIssues;
import ai.helper2.catalog.usecase.CurateField;
import ai.helper2.catalog.usecase.GetReviewItem;
import ai.helper2.catalog.usecase.ListReviewItems;
import ai.helper2.catalog.usecase.ManageKnownTargets;
import ai.helper2.catalog.usecase.ResolveReviewItem;
import ai.helper2.examples.usecase.AcceptExampleDraft;
import ai.helper2.examples.usecase.CountHomeExamples;
import ai.helper2.examples.usecase.ReadExampleDraft;
import ai.helper2.platform.AuditTrail;
import ai.helper2.platform.llm.BudgetLedger;
import ai.helper2.platform.settings.PublicAppSettings;
import ai.helper2.platform.settings.RuntimeSetting;
import ai.helper2.platform.settings.RuntimeSettingStore;
import ai.helper2.platform.settings.RuntimeSettings;
import ai.helper2.platform.settings.SettingsGroup;
import ai.helper2.research.ResearchItems;
import ai.helper2.research.ResearchRuns;
import ai.helper2.research.usecase.FindResearchedItems;
import ai.helper2.scout.AwesomeListReader;
import ai.helper2.scout.EnrichmentBacklog;
import ai.helper2.scout.ScoutStore;
import ai.helper2.scout.usecase.CandidateItems;
import ai.helper2.scout.usecase.EditCandidate;
import ai.helper2.scout.usecase.IndexAcceptedCandidate;
import ai.helper2.scout.usecase.ShownCandidates;
import ai.helper2.search.AiTopicCheck;
import ai.helper2.search.WebSearchQuota;
import ai.helper2.shared.UnitOfWork;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.IntSupplier;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.annotation.Transactional;

@Configuration(proxyBeanMethods = false)
class AdminUseCaseConfiguration {
  static IntSupplier guideDailyModelCalls(RuntimeSettings runtime, AdminProperties admin) {
    return runtime.declare(RuntimeSetting.GUIDE_DAILY_MODEL_CALLS, admin.guideDailyModelCalls());
  }

  @Bean
  GetOverview getOverview(
      ReviewQueue reviewQueue,
      EnrichmentBacklog backlog,
      ScoutStore runs,
      BudgetLedger budgets,
      ObjectProvider<WebSearchQuota> webSearchQuota,
      CountHomeExamples homeExamples,
      RuntimeSettings runtimeSettings,
      ValidationIssues validationIssues,
      AdminProperties admin,
      Clock clock) {
    budgets.declare("guide", guideDailyModelCalls(runtimeSettings, admin));
    return new GetOverview(
        reviewQueue,
        backlog,
        runs,
        budgets,
        webSearchQuota::getIfAvailable,
        homeExamples,
        runtimeSettings,
        validationIssues,
        clock);
  }

  @Bean
  GetEffectiveSettings getEffectiveSettings(
      List<SettingsGroup> groups, PublicAppSettings publicAppSettings) {
    return new GetEffectiveSettings(groups, publicAppSettings);
  }

  @Bean
  ListEnrichmentQueue listEnrichmentQueue(EnrichmentBacklog backlog) {
    return new ListEnrichmentQueue(backlog);
  }

  @Bean
  ReviewItemViews reviewItemViews(
      ReadExampleDraft drafts, ShownCandidates candidates, KnownTargetCandidates proposals) {
    return new ReviewItemViews(drafts, candidates, proposals);
  }

  @Bean
  ListReviewQueue listReviewQueue(
      ListReviewItems items, FindResearchedItems researched, ReviewItemViews views) {
    return new ListReviewQueue(items, researched, views);
  }

  @Bean
  ShowReviewItem showReviewItem(GetReviewItem item, ReviewItemViews views) {
    return new ShowReviewItem(item, views);
  }

  @Bean
  DecideReviewItem decideReviewItem(ResolveReview resolve, ReviewItemViews views) {
    return new DecideReviewItem(resolve, views);
  }

  @Bean
  GetReviewEvidence getReviewEvidence(
      ReviewQueue queue,
      ReviewEvidence evidence,
      ReviewItemViews views,
      AwesomeListEntries listEntries,
      ResourceRatings ratings) {
    return new ReadOnlyGetReviewEvidence(queue, evidence, views, listEntries, ratings);
  }

  static class ReadOnlyGetReviewEvidence extends GetReviewEvidence {

    ReadOnlyGetReviewEvidence(
        ReviewQueue queue,
        ReviewEvidence evidence,
        ReviewItemViews views,
        AwesomeListEntries listEntries,
        ResourceRatings ratings) {
      super(queue, evidence, views, listEntries, ratings);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<View> get(UUID itemId) {
      return super.get(itemId);
    }
  }

  @Bean
  ResolveReview resolveReview(
      ReviewQueue queue,
      ResolveReviewItem resolve,
      AcceptExampleDraft acceptDraft,
      IndexAcceptedCandidate indexCandidate,
      KnownTargetCandidates targetCandidates,
      ManageKnownTargets knownTargets,
      CurateField curateField,
      UnitOfWork unitOfWork) {
    return new ResolveReview(
        queue,
        resolve,
        acceptDraft,
        indexCandidate,
        targetCandidates,
        knownTargets,
        curateField,
        unitOfWork);
  }

  @Bean
  ResolveReviewItems resolveReviewItems(
      ReviewQueue queue,
      ReviewEvidence evidence,
      ResolveReview resolve,
      AuditTrail audit,
      ResearchRuns research,
      ResearchItems researchItems,
      CandidateItems candidates,
      EditCandidate editCandidate) {
    return new ResolveReviewItems(
        queue, evidence, resolve, audit, research, researchItems, candidates, editCandidate);
  }

  @Bean
  DecideRelationshipTargetGroup decideRelationshipTargetGroup(
      RelationshipTargets targets, DecideRelationshipItem item, ManageKnownTargets knownTargets) {
    return new DecideRelationshipTargetGroup(targets, item, knownTargets);
  }

  @Bean
  DecideRelationshipItem decideRelationshipItem(
      RelationshipTargets targets, ResolveReview resolve, AuditTrail audit, UnitOfWork unitOfWork) {
    return new DecideRelationshipItem(targets, resolve, audit, unitOfWork);
  }

  @Bean
  ListRelationshipTargetGroups listRelationshipTargetGroups(RelationshipTargets targets) {
    return new ListRelationshipTargetGroups(targets);
  }

  @Bean
  CheckAiWords checkAiWords(AiCheckReport report, AiTopicCheck aboutAi, EnrichmentBacklog backlog) {
    return new CheckAiWords(report, aboutAi, backlog);
  }

  @Bean
  CheckAwesomeLists checkAwesomeLists(
      AwesomeListSuspects suspects, AwesomeListReader reader, CurateField curateField) {
    return new CheckAwesomeLists(suspects, reader, curateField);
  }

  @Bean
  GetRuntimeSettings getRuntimeSettings(
      RuntimeSettingStore store, RuntimeSettings runtime, AdminProperties admin) {
    guideDailyModelCalls(runtime, admin);
    runtime.declareSwitch(RuntimeSetting.ADS_ENABLED, admin.adsEnabled());
    return new GetRuntimeSettings(store, runtime);
  }

  @Bean
  ChangeRuntimeSetting changeRuntimeSetting(
      RuntimeSettingStore store,
      GetRuntimeSettings settings,
      RuntimeSettings runtime,
      AuditTrail audit,
      Clock clock) {
    return new TransactionalChangeRuntimeSetting(store, settings, runtime, audit, clock);
  }

  @Bean
  ResetRuntimeSetting resetRuntimeSetting(
      RuntimeSettingStore store,
      GetRuntimeSettings settings,
      RuntimeSettings runtime,
      AuditTrail audit,
      Clock clock) {
    return new TransactionalResetRuntimeSetting(store, settings, runtime, audit, clock);
  }

  static class TransactionalChangeRuntimeSetting extends ChangeRuntimeSetting {

    TransactionalChangeRuntimeSetting(
        RuntimeSettingStore store,
        GetRuntimeSettings settings,
        RuntimeSettings runtime,
        AuditTrail audit,
        Clock clock) {
      super(store, settings, runtime, audit, clock);
    }

    @Override
    @Transactional
    public Outcome change(
        String adminId,
        RuntimeSetting setting,
        long value,
        long expectedVersion,
        @Nullable String reason) {
      return super.change(adminId, setting, value, expectedVersion, reason);
    }

    @Override
    protected void applyAfterCommit(RuntimeSettings runtime) {
      SettingsReloadAfterCommit.schedule(runtime);
    }
  }

  static class TransactionalResetRuntimeSetting extends ResetRuntimeSetting {

    TransactionalResetRuntimeSetting(
        RuntimeSettingStore store,
        GetRuntimeSettings settings,
        RuntimeSettings runtime,
        AuditTrail audit,
        Clock clock) {
      super(store, settings, runtime, audit, clock);
    }

    @Override
    @Transactional
    public Outcome reset(
        String adminId, RuntimeSetting setting, long expectedVersion, @Nullable String reason) {
      return super.reset(adminId, setting, expectedVersion, reason);
    }

    @Override
    protected void applyAfterCommit(RuntimeSettings runtime) {
      SettingsReloadAfterCommit.schedule(runtime);
    }
  }
}
