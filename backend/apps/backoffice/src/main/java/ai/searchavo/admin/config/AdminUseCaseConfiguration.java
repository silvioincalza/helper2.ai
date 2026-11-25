package ai.searchavo.admin.config;

import ai.searchavo.admin.usecase.ChangeRuntimeSetting;
import ai.searchavo.admin.usecase.CheckAiWords;
import ai.searchavo.admin.usecase.CheckAwesomeLists;
import ai.searchavo.admin.usecase.DecideRelationshipItem;
import ai.searchavo.admin.usecase.DecideRelationshipTargetGroup;
import ai.searchavo.admin.usecase.DecideReviewItem;
import ai.searchavo.admin.usecase.GetEffectiveSettings;
import ai.searchavo.admin.usecase.GetOverview;
import ai.searchavo.admin.usecase.GetReviewEvidence;
import ai.searchavo.admin.usecase.GetRuntimeSettings;
import ai.searchavo.admin.usecase.ListEnrichmentQueue;
import ai.searchavo.admin.usecase.ListRelationshipTargetGroups;
import ai.searchavo.admin.usecase.ListReviewQueue;
import ai.searchavo.admin.usecase.ResetRuntimeSetting;
import ai.searchavo.admin.usecase.ResolveReview;
import ai.searchavo.admin.usecase.ResolveReviewItems;
import ai.searchavo.admin.usecase.ReviewItemViews;
import ai.searchavo.admin.usecase.ShowReviewItem;
import ai.searchavo.catalog.AiCheckReport;
import ai.searchavo.catalog.AwesomeListEntries;
import ai.searchavo.catalog.AwesomeListSuspects;
import ai.searchavo.catalog.KnownTargetCandidates;
import ai.searchavo.catalog.RelationshipTargets;
import ai.searchavo.catalog.ResourceRatings;
import ai.searchavo.catalog.ReviewEvidence;
import ai.searchavo.catalog.ReviewQueue;
import ai.searchavo.catalog.enrichment.ValidationIssues;
import ai.searchavo.catalog.usecase.CurateField;
import ai.searchavo.catalog.usecase.GetReviewItem;
import ai.searchavo.catalog.usecase.ListReviewItems;
import ai.searchavo.catalog.usecase.ManageKnownTargets;
import ai.searchavo.catalog.usecase.ResolveReviewItem;
import ai.searchavo.examples.usecase.AcceptExampleDraft;
import ai.searchavo.examples.usecase.CountHomeExamples;
import ai.searchavo.examples.usecase.ReadExampleDraft;
import ai.searchavo.platform.AuditTrail;
import ai.searchavo.platform.llm.BudgetLedger;
import ai.searchavo.platform.settings.PublicAppSettings;
import ai.searchavo.platform.settings.RuntimeSetting;
import ai.searchavo.platform.settings.RuntimeSettingStore;
import ai.searchavo.platform.settings.RuntimeSettings;
import ai.searchavo.platform.settings.SettingsGroup;
import ai.searchavo.research.ResearchItems;
import ai.searchavo.research.ResearchRuns;
import ai.searchavo.research.usecase.FindResearchedItems;
import ai.searchavo.scout.AwesomeListReader;
import ai.searchavo.scout.EnrichmentBacklog;
import ai.searchavo.scout.ScoutStore;
import ai.searchavo.scout.usecase.CandidateItems;
import ai.searchavo.scout.usecase.EditCandidate;
import ai.searchavo.scout.usecase.IndexAcceptedCandidate;
import ai.searchavo.scout.usecase.ShownCandidates;
import ai.searchavo.search.AiTopicCheck;
import ai.searchavo.search.WebSearchQuota;
import ai.searchavo.shared.UnitOfWork;
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
