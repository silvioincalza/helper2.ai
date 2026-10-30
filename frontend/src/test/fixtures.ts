import type { DateFact, GuideResponse, Me, RecentResponse, ResourceDetail, ResultCard, SearchResponse, SourceInfo, SourceStatus } from '@/api/types';

export const known = (value: string, sourceId = 'github'): DateFact => ({ value, precision: 'DAY', status: 'KNOWN', sourceId });
export const unknownDate: DateFact = { value: null, precision: 'UNKNOWN', status: 'UNKNOWN', alternatives: [] };

export function makeCard(over: Partial<ResultCard> = {}): ResultCard {
  return {
    id: '11111111-1111-5111-8111-111111111111',
    title: 'Acme Translator',
    description: 'Translates documents between languages.',
    descriptionOrigin: 'SOURCE_PROVIDED',
    type: 'PRODUCT',
    audience: 'USE_AI',
    techLevel: 'BEGINNER',
    useCases: ['translation'],
    tags: ['translation', 'docs'],
    compatibleWith: [],
    license: 'MIT',
    pricing: 'FREEMIUM',
    platforms: ['web'],
    openSource: true,
    url: 'https://www.acme.example/translator',
    sources: [{ sourceId: 'github', sourceName: 'GitHub', url: 'https://github.com/acme/translator', mode: 'LIVE', retrievedAt: '2026-10-20T10:00:00Z' }],
    timeline: { published: known('2025-03-04T00:00:00Z'), updated: known('2026-09-01T00:00:00Z'), discovered: known('2026-10-20T10:00:00Z', 'helper2') },
    popularity: [{ metric: 'GITHUB_STARS', value: 1234, sourceId: 'github', asOf: '2026-10-20T10:00:00Z' }],
    freshness: 'LIVE',
    conflicts: [],
    ...over,
  };
}

export function makeStatus(over: Partial<SourceStatus> = {}): SourceStatus {
  return {
    sourceId: 'github', name: 'GitHub', mode: 'LIVE', outcome: 'SUCCEEDED', servedFrom: 'LIVE', resultCount: 1,
    latencyMs: 120, checkedAt: '2026-10-22T09:00:00Z', cachedAt: null, message: null, ...over,
  };
}

export function makeSearch(over: Partial<SearchResponse> = {}): SearchResponse {
  const sources = over.sources ?? [makeStatus()];
  return {
    query: 'translate', path: 'USE_AI', sort: 'RELEVANCE', executedAt: '2026-10-22T09:00:00Z',
    coverage: { partial: false, sourcesConfigured: sources.length, sourcesQueried: sources.length, sourcesSucceeded: sources.filter((s) => s.outcome === 'SUCCEEDED').length, note: 'Coverage is limited to the listed sources.' },
    sources,
    results: [makeCard()],
    facets: {
      type: [{ value: 'PRODUCT', count: 1 }, { value: 'MODEL', count: 2 }],
      source: [{ value: 'github', count: 1 }],
      license: [{ value: 'MIT', count: 1 }, { value: 'unknown', count: 3 }],
      openSource: [{ value: 'true', count: 1 }, { value: 'unknown', count: 2 }],
      pricing: [{ value: 'FREEMIUM', count: 1 }],
      platform: [{ value: 'web', count: 1 }],
      useCase: [{ value: 'translation', count: 1 }],
      techLevel: [{ value: 'BEGINNER', count: 1 }, { value: 'unknown', count: 1 }],
      audience: [{ value: 'USE_AI', count: 1 }],
    },
    total: 1, page: 0, size: 20, notices: [], interpretation: { expandedTerms: [], detectedUseCases: [] },
    ...over,
  };
}

export function makeDetail(over: Partial<ResourceDetail> = {}): ResourceDetail {
  return {
    card: makeCard(), assertions: [], relationships: [], summary: null,
    sourceRecords: [{ sourceId: 'github', externalId: 'acme/translator', url: 'https://github.com/acme/translator', retrievedAt: '2026-10-20T10:00:00Z', fingerprint: 'abcdef0123456789' }],
    enrichmentStatus: 'NOT_RUN', ...over,
  };
}

export const SOURCES: SourceInfo[] = [
  { id: 'github', name: 'GitHub', mode: 'LIVE', enabled: true, homepage: 'https://github.com', termsUrl: 'https://docs.github.com/terms', attribution: 'Data from GitHub', resourceTypes: ['LIBRARY', 'MCP_SERVER'], lastOutcome: 'SUCCEEDED', lastCheckedAt: '2026-10-22T09:00:00Z' },
  { id: 'seed', name: 'Helper2 seed', mode: 'CURATED', enabled: true, homepage: 'https://helper2.ai', termsUrl: null, attribution: null, resourceTypes: ['PRODUCT'], lastOutcome: null, lastCheckedAt: null },
];

export const ANON: Me = { authenticated: false, roles: [], loginProviders: [{ id: 'github', name: 'GitHub' }] };
export const USER: Me = { authenticated: true, displayName: 'Ada', roles: ['USER'], loginProviders: [] };
export const ADMIN: Me = { authenticated: true, displayName: 'Root', roles: ['USER', 'ADMIN'], loginProviders: [] };

export function makeRecent(over: Partial<RecentResponse> = {}): RecentResponse {
  return {
    items: [makeCard({ id: '33333333-3333-5333-8333-333333333333', title: 'Fresh MCP server', type: 'MCP_SERVER' })],
    since: '2026-10-08T09:00:00Z',
    generatedAt: '2026-10-22T09:00:00Z',
    ...over,
  };
}

/** A guide answer with one AI pick, Postgres MCP (id …2222), chosen by the reasoning model. */
export function makeGuide(over: Partial<GuideResponse> = {}): GuideResponse {
  return {
    status: 'OK',
    searchQuery: 'mcp server postgres',
    recommendations: [{
      resourceId: '22222222-2222-5222-8222-222222222222',
      title: 'Postgres MCP',
      url: 'https://github.com/acme/postgres-mcp',
      type: 'MCP_SERVER',
      reason: 'Lets an agent run read-only SQL against Postgres.',
      quote: 'MCP server that gives agents read-only access to a PostgreSQL database',
      sourceIds: ['github'],
      aiReasoned: true,
      reasonCheck: 'NOT_CHECKED',
    }],
    prompt: null,
    model: { provider: 'nebius-token-factory', fastModel: 'nvidia/nemotron-nano', reasoningModel: 'nvidia/nemotron-3-super' },
    aiContent: {
      aiGenerated: true, provider: 'nebius-token-factory', models: ['nvidia/nemotron-nano', 'nvidia/nemotron-3-super'],
      fields: ['searchQuery', 'recommendations[].reason'], policy: '/mission',
    },
    candidatesConsidered: 12,
    rejected: 0,
    sources: [makeStatus()],
    notices: [],
    generatedAt: '2026-10-25T10:00:00Z',
    ...over,
  };
}

