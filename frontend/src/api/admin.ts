import type {
  ReviewItem,
  ReviewItemPage,
  ReviewBulkResolution,
  ReviewBulkResult,
  AiCheckPage,
  ReviewEvidence,
  RelationshipTargetChange,
  RelationshipTargetGroupPage,
  RelationshipTargetGroupDecision,
  RelationshipTargetGroupOutcome,
  CandidateEdit,
  ResourceField,
  ResourceFieldValue,
  ReviewResolution,
  ReviewStatus,
  ReviewKind,
  ScoutRun,
  EnrichmentRecheck,
  AuditEntryPage,
  AdminOverview,
  EnrichmentQueuePage,
  EffectiveConfig,
  EnrichmentQueueView,
  RuntimeSettings,
  RuntimeSettingState,
  RuntimeSwitchState,
  DiscoveryHosts,
  DiscoveryHostEntry,
  DiscoveryHostKind,
  ListSeeds,
  ListSeedEntry,
  ShadowLibraries,
  ShadowLibraryEntry,
  KnownTargets,
  KnownTargetEntry,
  KnownTargetWrite,
  AdminHomeExample,
  ExampleCheckOutcome,
  ExampleDrafts,
  HomeExampleChange,
  HomeExampleInput,
  ReviewResearchDetail,
  ReviewResearchRun,
  ReviewResearchStep,
  ReviewResearchEstimate,
  ReviewResearchRuns,
  ReviewResearchStart,
} from './types';

import { absolute, API_BASE, request, send } from './client';
import { readSse, type SseEnd } from '@/lib/sse';

export const adminApi = {
  listReviewItems: (
    filter: {
      status?: ReviewStatus | undefined;
      kind?: ReviewKind | undefined;
      category?: string | undefined;
      research?: string | undefined;
    },
    page: number,
    size: number,
    signal?: AbortSignal,
  ) =>
    request<ReviewItemPage>('GET', '/admin/review-items', {
      query: new URLSearchParams({
        ...(filter.research ? { research: filter.research } : {}),
        ...(filter.status ? { status: filter.status } : {}),
        ...(filter.kind ? { kind: filter.kind } : {}),
        ...(filter.category ? { category: filter.category } : {}),
        page: String(page),
        size: String(size),
      }),
      signal,
    }),
  listRelationshipTargetGroups: (page: number, size: number, signal?: AbortSignal) =>
    request<RelationshipTargetGroupPage>('GET', '/admin/relationship-targets', {
      query: new URLSearchParams({ page: String(page), size: String(size) }),
      signal,
    }),
  decideRelationshipTargetGroup: (body: RelationshipTargetGroupDecision) =>
    request<RelationshipTargetGroupOutcome>('POST', '/admin/relationship-targets/decision', {
      body,
    }),
  resolveReviewItem: (id: string, body: ReviewResolution) =>
    request<ReviewItem>('PUT', `/admin/review-items/${encodeURIComponent(id)}/resolution`, {
      body,
    }),
  resolveReviewItems: (body: ReviewBulkResolution) =>
    request<ReviewBulkResult>('POST', '/admin/review-items/resolutions', { body }),
  changeRelationshipTarget: (id: string, body: RelationshipTargetChange) =>
    request<ReviewEvidence>(
      'PUT',
      `/admin/review-items/${encodeURIComponent(id)}/relationship-target`,
      { body },
    ),
  editKnownTargetProposal: (id: string, body: KnownTargetWrite) =>
    request<ReviewItem>('PUT', `/admin/review-items/${encodeURIComponent(id)}/known-target`, {
      body,
    }),
  editCandidate: (id: string, body: CandidateEdit) =>
    request<ReviewItem>('PUT', `/admin/review-items/${encodeURIComponent(id)}/candidate`, { body }),
  setResourceField: async (id: string, field: ResourceField, body: ResourceFieldValue) => {
    await send(
      'PUT',
      absolute(
        `${API_BASE}/admin/resources/${encodeURIComponent(id)}/fields/${encodeURIComponent(field)}`,
      ),
      { body },
    );
  },
  dropResourceField: async (id: string, field: ResourceField) => {
    await send(
      'POST',
      absolute(
        `${API_BASE}/admin/resources/${encodeURIComponent(id)}/fields/${encodeURIComponent(field)}/drop`,
      ),
      {},
    );
  },
  startReviewResearch: (body: ReviewResearchStart) =>
    request<ReviewResearchRuns>('POST', '/admin/review-research', { body }),
  listReviewResearch: (itemIds: string[], signal?: AbortSignal) =>
    request<ReviewResearchRuns>('GET', '/admin/review-research', {
      query: new URLSearchParams(itemIds.map((id) => ['itemId', id])),
      signal,
    }),
  estimateReviewResearch: (signal?: AbortSignal) =>
    request<ReviewResearchEstimate>('GET', '/admin/review-research/estimate', { signal }),
  getReviewResearch: (runId: string, signal?: AbortSignal) =>
    request<ReviewResearchDetail>('GET', `/admin/review-research/${encodeURIComponent(runId)}`, {
      signal,
    }),
  cancelReviewResearch: (runId: string) =>
    request<void>('POST', `/admin/review-research/${encodeURIComponent(runId)}/cancel`),
  reviewResearchEvents: (
    runId: string,
    afterSeq: number,
    on: { step: (step: ReviewResearchStep) => void; run: (run: ReviewResearchRun) => void },
    signal?: AbortSignal,
  ): Promise<SseEnd> =>
    readSse(
      (streamSignal) =>
        send(
          'GET',
          absolute(
            `${API_BASE}/admin/review-research/${encodeURIComponent(runId)}/events`,
            new URLSearchParams({ afterSeq: String(afterSeq) }),
          ),
          { accept: 'text/event-stream', signal: streamSignal },
        ),
      (e) => {
        if (e.event === 'step') on.step(JSON.parse(e.data) as ReviewResearchStep);
        if (e.event === 'status') on.run(JSON.parse(e.data) as ReviewResearchRun);
      },
      { signal },
    ),
  getReviewEvidence: (id: string, signal?: AbortSignal) =>
    request<ReviewEvidence>('GET', `/admin/review-items/${encodeURIComponent(id)}/evidence`, {
      signal,
    }),
  listScoutRuns: (signal?: AbortSignal) =>
    request<ScoutRun[]>('GET', '/admin/scout-runs', { signal }),
  backfillCategories: async (): Promise<void> => {
    await send('POST', absolute(`${API_BASE}/admin/category-backfill`), {});
  },
  startScoutRun: (sourceId?: string) =>
    request<ScoutRun[]>('POST', '/admin/scout-runs', { body: sourceId ? { sourceId } : {} }),
  recheckEnrichments: () => request<EnrichmentRecheck>('POST', '/admin/enrichment-recheck'),
  getAdminOverview: (signal?: AbortSignal) =>
    request<AdminOverview>('GET', '/admin/overview', { signal }),
  listAdminHomeExamples: (outcome: ExampleCheckOutcome | undefined, signal?: AbortSignal) =>
    request<AdminHomeExample[]>('GET', '/admin/home-examples', {
      query: new URLSearchParams(outcome ? { outcome } : {}),
      signal,
    }),
  addHomeExample: (body: HomeExampleInput) =>
    request<AdminHomeExample>('POST', '/admin/home-examples', { body }),
  changeHomeExample: (id: string, body: HomeExampleChange) =>
    request<AdminHomeExample>('PUT', `/admin/home-examples/${encodeURIComponent(id)}`, { body }),
  draftHomeExamples: () => request<ExampleDrafts>('POST', '/admin/home-examples/drafts'),
  removeHomeExample: (id: string) =>
    request<void>('DELETE', `/admin/home-examples/${encodeURIComponent(id)}`),
  listEnrichmentQueue: (
    view: EnrichmentQueueView,
    after: string | undefined,
    size: number,
    signal?: AbortSignal,
  ) =>
    request<EnrichmentQueuePage>('GET', '/admin/enrichment-queue', {
      query: new URLSearchParams({
        ...(view === 'WAITING' ? {} : { view }),
        ...(after ? { after } : {}),
        size: String(size),
      }),
      signal,
    }),
  getEffectiveConfig: (signal?: AbortSignal) =>
    request<EffectiveConfig>('GET', '/admin/config', { signal }),
  listRuntimeSettings: (signal?: AbortSignal) =>
    request<RuntimeSettings>('GET', '/admin/runtime-settings', { signal }),
  changeRuntimeSetting: (
    key: string,
    body: { value: number; version: number; reason: string | null },
  ) =>
    request<RuntimeSettingState>('PUT', `/admin/runtime-settings/${encodeURIComponent(key)}`, {
      body,
    }),
  resetRuntimeSetting: (key: string, body: { version: number; reason: string | null }) =>
    request<RuntimeSettingState>(
      'POST',
      `/admin/runtime-settings/${encodeURIComponent(key)}/reset`,
      { body },
    ),
  changeRuntimeSwitch: (
    key: string,
    body: { on: boolean; version: number; reason: string | null },
  ) =>
    request<RuntimeSwitchState>('PUT', `/admin/runtime-switches/${encodeURIComponent(key)}`, {
      body,
    }),
  resetRuntimeSwitch: (key: string, body: { version: number; reason: string | null }) =>
    request<RuntimeSwitchState>(
      'POST',
      `/admin/runtime-switches/${encodeURIComponent(key)}/reset`,
      { body },
    ),
  listDiscoveryHosts: (signal?: AbortSignal) =>
    request<DiscoveryHosts>('GET', '/admin/discovery-hosts', { signal }),
  addDiscoveryHost: (body: { host: string; kind: DiscoveryHostKind; reason: string | null }) =>
    request<DiscoveryHostEntry>('POST', '/admin/discovery-hosts', { body }),
  removeDiscoveryHost: (host: string, reason: string | null) =>
    request<void>('POST', `/admin/discovery-hosts/${encodeURIComponent(host)}/remove`, {
      body: { reason },
    }),
  readDirectoryLists: (host: string, read: boolean, reason: string | null) =>
    request<DiscoveryHostEntry>(
      'POST',
      `/admin/discovery-hosts/${encodeURIComponent(host)}/list-reading`,
      { body: { read, reason } },
    ),
  listListSeeds: (signal?: AbortSignal) =>
    request<ListSeeds>('GET', '/admin/list-seeds', { signal }),
  addListSeed: (body: { address: string; reason: string | null }) =>
    request<ListSeedEntry>('POST', '/admin/list-seeds', { body }),
  removeListSeed: (id: string, reason: string | null) =>
    request<void>('POST', `/admin/list-seeds/${encodeURIComponent(id)}/remove`, {
      body: { reason },
    }),
  listShadowLibraries: (signal?: AbortSignal) =>
    request<ShadowLibraries>('GET', '/admin/shadow-libraries', { signal }),
  addShadowLibrary: (body: { name: string; reason: string | null }) =>
    request<ShadowLibraryEntry>('POST', '/admin/shadow-libraries', { body }),
  removeShadowLibrary: (name: string, reason: string | null) =>
    request<void>('POST', `/admin/shadow-libraries/${encodeURIComponent(name)}/remove`, {
      body: { reason },
    }),
  listKnownTargets: (signal?: AbortSignal) =>
    request<KnownTargets>('GET', '/admin/known-targets', { signal }),
  addKnownTarget: (body: KnownTargetWrite) =>
    request<KnownTargetEntry>('POST', '/admin/known-targets', { body }),
  changeKnownTarget: (id: string, body: KnownTargetWrite) =>
    request<KnownTargetEntry>('PUT', `/admin/known-targets/${encodeURIComponent(id)}`, { body }),
  removeKnownTarget: (id: string) =>
    request<void>('POST', `/admin/known-targets/${encodeURIComponent(id)}/remove`),
  requeueEnrichment: (resourceId: string) =>
    request<void>('POST', `/admin/enrichment-queue/${encodeURIComponent(resourceId)}/requeue`),
  checkAiWords: (after: string | null, size: number) =>
    request<AiCheckPage>('GET', '/admin/ai-check', {
      query: new URLSearchParams({ ...(after ? { after } : {}), size: String(size) }),
    }),
  skipEnrichment: (resourceId: string, reason: string) =>
    request<void>('POST', `/admin/enrichment-queue/${encodeURIComponent(resourceId)}/skip`, {
      body: reason.trim() ? { reason: reason.trim() } : {},
    }),
  removeResourceNotAboutAi: (resourceId: string, reason: string) =>
    request<void>('POST', `/admin/ai-check/${encodeURIComponent(resourceId)}/remove`, {
      body: { reason: reason.trim() },
    }),
  prioritiseEnrichment: (resourceId: string) =>
    request<void>('POST', `/admin/enrichment-queue/${encodeURIComponent(resourceId)}/prioritise`),
  listAuditEntries: (page: number, size: number, signal?: AbortSignal) =>
    request<AuditEntryPage>('GET', '/admin/audit', {
      query: new URLSearchParams({ page: String(page), size: String(size) }),
      signal,
    }),
};
