import type {
  ReviewItem,
  ReviewItemPage,
  ReviewBulkResolution,
  ReviewBulkResult,
  ReviewEvidence,
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
  AdminHomeExample,
  ExampleCheckOutcome,
  ExampleDrafts,
  HomeExampleChange,
  HomeExampleInput,
} from './types';

import { absolute, API_BASE, request, send } from './client';

/**
 * The back office's calls. Only the lazy admin pages import this module, so the admin API stays out of
 * the JavaScript a public page loads (ADR 0045).
 */
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
  /** 409 when the item was already decided (another decision, a double click, or a newer enrichment run). */
  resolveReviewItem: (id: string, body: ReviewResolution) =>
    request<ReviewItem>('PUT', `/admin/review-items/${encodeURIComponent(id)}/resolution`, {
      body,
    }),
  /** Decides up to 100 items, each on its own; one outcome per item, in the order sent (ADR 0045). */
  resolveReviewItems: (body: ReviewBulkResolution) =>
    request<ReviewBulkResult>('POST', '/admin/review-items/resolutions', { body }),
  /** What a review item is about: its run or relationship, with the quotes behind them (ADR 0045). 404 for no item. */
  getReviewEvidence: (id: string, signal?: AbortSignal) =>
    request<ReviewEvidence>('GET', `/admin/review-items/${encodeURIComponent(id)}/evidence`, {
      signal,
    }),
  listScoutRuns: (signal?: AbortSignal) =>
    request<ScoutRun[]>('GET', '/admin/scout-runs', { signal }),
  /** Starts assigning every resource's categories again, in the background (ADR 0053). */
  backfillCategories: async (): Promise<void> => {
    // 202 with no body.
    await send('POST', absolute(`${API_BASE}/admin/category-backfill`), {});
  },
  /** Answers at once with the started run's records (RUNNING); follow them with listScoutRuns. 409 while a run holds the lock. */
  startScoutRun: (sourceId?: string) =>
    request<ScoutRun[]>('POST', '/admin/scout-runs', { body: sourceId ? { sourceId } : {} }),
  /** Re-checks the stored enrichment answers by code (ADR 0056). */
  recheckEnrichments: () => request<EnrichmentRecheck>('POST', '/admin/enrichment-recheck'),
  /** Counts for the back office landing page; the server computes them at most once a minute. */
  getAdminOverview: (signal?: AbortSignal) =>
    request<AdminOverview>('GET', '/admin/overview', { signal }),
  listAdminHomeExamples: (outcome: ExampleCheckOutcome | undefined, signal?: AbortSignal) =>
    request<AdminHomeExample[]>('GET', '/admin/home-examples', {
      query: new URLSearchParams(outcome ? { outcome } : {}),
      signal,
    }),
  /** 400 invalid-request with the broken rules in its detail; 409 duplicate. */
  addHomeExample: (body: HomeExampleInput) =>
    request<AdminHomeExample>('POST', '/admin/home-examples', { body }),
  /** 409 version-conflict when someone changed it first, 409 duplicate. */
  changeHomeExample: (id: string, body: HomeExampleChange) =>
    request<AdminHomeExample>('PUT', `/admin/home-examples/${encodeURIComponent(id)}`, { body }),
  /** One model call; the drafts wait in the review queue. 503 unavailable with the reason in its detail. */
  draftHomeExamples: () => request<ExampleDrafts>('POST', '/admin/home-examples/drafts'),
  removeHomeExample: (id: string) =>
    request<void>('DELETE', `/admin/home-examples/${encodeURIComponent(id)}`),
  /**
   * One view of the enrichment queue (WAITING: the Scout's order); pass the previous page's next as after, with the
   * same view. 400 for a foreign cursor.
   */
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
  /** 400 invalid-request outside 0 to max; 409 version-conflict when someone changed it first. */
  changeRuntimeSetting: (
    key: string,
    body: { value: number; version: number; reason: string | null },
  ) =>
    request<RuntimeSettingState>('PUT', `/admin/runtime-settings/${encodeURIComponent(key)}`, {
      body,
    }),
  /** Back to the default (the H2_ value); 409 version-conflict when someone changed it first. */
  resetRuntimeSetting: (key: string, body: { version: number; reason: string | null }) =>
    request<RuntimeSettingState>(
      'POST',
      `/admin/runtime-settings/${encodeURIComponent(key)}/reset`,
      { body },
    ),
  listDiscoveryHosts: (signal?: AbortSignal) =>
    request<DiscoveryHosts>('GET', '/admin/discovery-hosts', { signal }),
  /** 400 invalid-request for a host that is not one or matches too much; 409 duplicate when it is listed. */
  addDiscoveryHost: (body: { host: string; kind: DiscoveryHostKind; reason: string | null }) =>
    request<DiscoveryHostEntry>('POST', '/admin/discovery-hosts', { body }),
  /** 404 not-found when the host is in no list. */
  removeDiscoveryHost: (host: string, reason: string | null) =>
    request<void>('POST', `/admin/discovery-hosts/${encodeURIComponent(host)}/remove`, {
      body: { reason },
    }),
  /** 409 not-applicable when the resource is neither skipped nor out of retries; 404 for an unknown one. */
  requeueEnrichment: (resourceId: string) =>
    request<void>('POST', `/admin/enrichment-queue/${encodeURIComponent(resourceId)}/requeue`),
  /** 409 not-applicable when the resource does not wait or is already skipped. */
  skipEnrichment: (resourceId: string, reason: string) =>
    request<void>('POST', `/admin/enrichment-queue/${encodeURIComponent(resourceId)}/skip`, {
      body: reason.trim() ? { reason: reason.trim() } : {},
    }),
  /** 409 not-applicable when the resource does not wait, is already at the top, or the top is full. */
  prioritiseEnrichment: (resourceId: string) =>
    request<void>('POST', `/admin/enrichment-queue/${encodeURIComponent(resourceId)}/prioritise`),
  listAuditEntries: (page: number, size: number, signal?: AbortSignal) =>
    request<AuditEntryPage>('GET', '/admin/audit', {
      query: new URLSearchParams({ page: String(page), size: String(size) }),
      signal,
    }),
};
