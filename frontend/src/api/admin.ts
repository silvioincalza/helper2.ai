import type {
  ReviewItem,
  ReviewItemPage,
  ReviewBulkResolution,
  ReviewBulkResult,
  ReviewEvidence,
  RelationshipTargetChange,
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
  DiscoveryHosts,
  DiscoveryHostEntry,
  DiscoveryHostKind,
  KnownTargets,
  KnownTargetEntry,
  KnownTargetWrite,
  AdminHomeExample,
  ExampleCheckOutcome,
  ExampleDrafts,
  HomeExampleChange,
  HomeExampleInput,
} from './types';

import { absolute, API_BASE, request, send } from './client';

export const adminApi = {
  listReviewItems: (
    filter: {
      status?: ReviewStatus | undefined;
      kind?: ReviewKind | undefined;
      category?: string | undefined;
    },
    page: number,
    size: number,
    signal?: AbortSignal,
  ) =>
    request<ReviewItemPage>('GET', '/admin/review-items', {
      query: new URLSearchParams({
        ...(filter.status ? { status: filter.status } : {}),
        ...(filter.kind ? { kind: filter.kind } : {}),
        ...(filter.category ? { category: filter.category } : {}),
        page: String(page),
        size: String(size),
      }),
      signal,
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
  listDiscoveryHosts: (signal?: AbortSignal) =>
    request<DiscoveryHosts>('GET', '/admin/discovery-hosts', { signal }),
  addDiscoveryHost: (body: { host: string; kind: DiscoveryHostKind; reason: string | null }) =>
    request<DiscoveryHostEntry>('POST', '/admin/discovery-hosts', { body }),
  removeDiscoveryHost: (host: string, reason: string | null) =>
    request<void>('POST', `/admin/discovery-hosts/${encodeURIComponent(host)}/remove`, {
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
  skipEnrichment: (resourceId: string, reason: string) =>
    request<void>('POST', `/admin/enrichment-queue/${encodeURIComponent(resourceId)}/skip`, {
      body: reason.trim() ? { reason: reason.trim() } : {},
    }),
  prioritiseEnrichment: (resourceId: string) =>
    request<void>('POST', `/admin/enrichment-queue/${encodeURIComponent(resourceId)}/prioritise`),
  listAuditEntries: (page: number, size: number, signal?: AbortSignal) =>
    request<AuditEntryPage>('GET', '/admin/audit', {
      query: new URLSearchParams({ page: String(page), size: String(size) }),
      signal,
    }),
};
