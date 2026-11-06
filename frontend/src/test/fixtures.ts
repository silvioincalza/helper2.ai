import stagingSources from './staging-sources.json';
import type {
  DateFact,
  Me,
  Policy,
  RecentResponse,
  ResourceDetail,
  ResultCard,
  ReviewEvidence,
  ReviewItem,
  SearchResponse,
  Category,
  SourceInfo,
  SourceStatus,
} from '@/api/types';

export const known = (value: string, sourceId = 'github'): DateFact => ({
  value,
  precision: 'DAY',
  status: 'KNOWN',
  sourceId,
});
export const unknownDate: DateFact = {
  value: null,
  precision: 'UNKNOWN',
  status: 'UNKNOWN',
  alternatives: [],
};

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
    categories: ['translation'],
    compatibleWith: [],
    license: 'MIT',
    pricing: 'FREEMIUM',
    platforms: ['web'],
    openSource: true,
    url: 'https://www.acme.example/translator',
    sources: [
      {
        sourceId: 'github',
        sourceName: 'GitHub',
        url: 'https://github.com/acme/translator',
        mode: 'LIVE',
        retrievedAt: '2026-10-20T10:00:00Z',
      },
    ],
    timeline: {
      published: known('2025-03-04T00:00:00Z'),
      updated: known('2026-09-01T00:00:00Z'),
      discovered: known('2026-10-20T10:00:00Z', 'helper2'),
    },
    popularity: [
      { metric: 'GITHUB_STARS', value: 1234, sourceId: 'github', asOf: '2026-10-20T10:00:00Z' },
    ],
    freshness: 'LIVE',
    conflicts: [],
    ...over,
  };
}

export function makeStatus(over: Partial<SourceStatus> = {}): SourceStatus {
  return {
    sourceId: 'github',
    name: 'GitHub',
    mode: 'LIVE',
    outcome: 'SUCCEEDED',
    servedFrom: 'LIVE',
    resultCount: 1,
    latencyMs: 120,
    checkedAt: '2026-10-22T09:00:00Z',
    cachedAt: null,
    message: null,
    ...over,
  };
}

export function makeSearch(over: Partial<SearchResponse> = {}): SearchResponse {
  const sources = over.sources ?? [makeStatus()];
  return {
    query: 'translate',
    path: 'USE_AI',
    sort: 'RELEVANCE',
    executedAt: '2026-10-22T09:00:00Z',
    coverage: {
      partial: false,
      sourcesConfigured: sources.length,
      sourcesQueried: sources.length,
      sourcesSucceeded: sources.filter((s) => s.outcome === 'SUCCEEDED').length,
      note: 'Coverage is limited to the listed sources.',
    },
    sources,
    results: [makeCard()],
    facets: {
      type: [
        { value: 'PRODUCT', count: 1 },
        { value: 'MODEL', count: 2 },
      ],
      source: [{ value: 'github', count: 1 }],
      license: [
        { value: 'MIT', count: 1 },
        { value: 'unknown', count: 3 },
      ],
      openSource: [
        { value: 'true', count: 1 },
        { value: 'unknown', count: 2 },
      ],
      pricing: [{ value: 'FREEMIUM', count: 1 }],
      platform: [{ value: 'web', count: 1 }],
      useCase: [{ value: 'translation', count: 1 }],
      techLevel: [
        { value: 'BEGINNER', count: 1 },
        { value: 'unknown', count: 1 },
      ],
      audience: [{ value: 'USE_AI', count: 1 }],
      category: [
        { value: 'translation', count: 1 },
        { value: 'unknown', count: 2 },
      ],
    },
    total: 1,
    page: 0,
    size: 20,
    notices: [],
    interpretation: { expandedTerms: [], detectedUseCases: [] },
    ...over,
  };
}

export function makeDetail(over: Partial<ResourceDetail> = {}): ResourceDetail {
  return {
    card: makeCard(),
    assertions: [],
    relationships: [],
    summary: null,
    sourceRecords: [
      {
        sourceId: 'github',
        externalId: 'acme/translator',
        url: 'https://github.com/acme/translator',
        retrievedAt: '2026-10-20T10:00:00Z',
        fingerprint: 'abcdef0123456789',
      },
    ],
    enrichmentStatus: 'NOT_RUN',
    ...over,
  };
}

/**
 * The 16 sources of staging on 5 Nov 2026, with a mix of last checks (answered, slow, limited, failed):
 * 13 external, and helper2's own index, search by meaning and curated list.
 */
export const STAGING_SOURCES = stagingSources as SourceInfo[];

export const SOURCES: SourceInfo[] = [
  {
    id: 'github',
    name: 'GitHub',
    shortName: 'GitHub',
    external: true,
    mode: 'LIVE',
    enabled: true,
    metered: false,
    homepage: 'https://github.com',
    termsUrl: 'https://docs.github.com/terms',
    attribution: 'Data from GitHub',
    resourceTypes: ['LIBRARY', 'MCP_SERVER'],
    lastOutcome: 'SUCCEEDED',
    lastCheckedAt: '2026-10-22T09:00:00Z',
  },
  {
    id: 'seed',
    name: 'Helper2 seed',
    shortName: 'Curated',
    external: false,
    mode: 'CURATED',
    enabled: true,
    metered: false,
    homepage: 'https://helper2.ai',
    termsUrl: null,
    attribution: null,
    resourceTypes: ['PRODUCT'],
    lastOutcome: null,
    lastCheckedAt: null,
  },
];

/** Every health field of GET /sources: response time, an open and a half-open circuit, the metered source's credits. */
export const HEALTH_SOURCES: SourceInfo[] = [
  { ...SOURCES[0]!, responseTimeMs: 240, circuit: 'CLOSED', circuitOpenedAt: null },
  {
    id: 'npm',
    name: 'npm',
    shortName: 'npm',
    external: true,
    mode: 'LIVE',
    enabled: true,
    metered: false,
    homepage: 'https://www.npmjs.com',
    termsUrl: null,
    attribution: null,
    resourceTypes: ['LIBRARY'],
    lastOutcome: 'SUCCEEDED',
    lastCheckedAt: '2026-10-22T08:30:00Z',
    responseTimeMs: 1250,
    circuit: 'OPEN',
    circuitOpenedAt: '2026-10-22T09:14:05Z',
  },
  {
    id: 'hf',
    name: 'Hugging Face',
    shortName: 'HF models',
    external: true,
    mode: 'LIVE',
    enabled: true,
    metered: false,
    homepage: 'https://huggingface.co',
    termsUrl: null,
    attribution: null,
    resourceTypes: ['MODEL'],
    lastOutcome: 'FAILED',
    lastCheckedAt: '2026-10-22T08:40:00Z',
    responseTimeMs: null,
    circuit: 'HALF_OPEN',
    circuitOpenedAt: null,
  },
  {
    id: 'tavily',
    name: 'Tavily web search (AI news)',
    shortName: 'Web',
    external: true,
    mode: 'LIVE',
    enabled: true,
    metered: true,
    homepage: 'https://tavily.com',
    termsUrl: null,
    attribution: null,
    resourceTypes: ['NEWS'],
    lastOutcome: 'SUCCEEDED',
    lastCheckedAt: '2026-10-22T08:50:00Z',
    responseTimeMs: 800,
    circuit: 'CLOSED',
    circuitOpenedAt: null,
    webCredits: { usedToday: 312, dailyCap: 1000 },
  },
  { ...SOURCES[1]!, circuit: null, webCredits: null },
];

const LIMITS: Me['limits'] = {
  guideQuestions: { limit: 30, remaining: 30 },
  webSearches: { search: { limit: 3, remaining: 3 }, guide: { limit: 3, remaining: 3 } },
  resetsAt: '2026-10-23T00:00:00Z',
};

/** Every monetisation part off (ADR 0057), as with empty settings. */
const MONETISATION_OFF: Me['monetisation'] = {
  githubSponsorsUrl: null,
  stripePaymentUrl: null,
  ga4MeasurementId: null,
  ethicalAdsPublisher: null,
  adsenseClient: null,
  adsenseSlot: null,
};

export const ANON: Me = {
  authenticated: false,
  roles: [],
  loginProviders: [{ id: 'github', name: 'GitHub' }],
  limits: LIMITS,
  monetisation: MONETISATION_OFF,
};
export const USER: Me = {
  authenticated: true,
  displayName: 'Ada',
  roles: ['USER'],
  loginProviders: [],
  limits: LIMITS,
  preferences: { radarMotion: true },
  monetisation: MONETISATION_OFF,
};
export const ADMIN: Me = {
  authenticated: true,
  displayName: 'Root',
  roles: ['USER', 'ADMIN'],
  loginProviders: [],
  limits: LIMITS,
  preferences: { radarMotion: true },
  monetisation: MONETISATION_OFF,
};

export function makeRecent(over: Partial<RecentResponse> = {}): RecentResponse {
  return {
    items: [
      makeCard({
        id: '33333333-3333-5333-8333-333333333333',
        title: 'Fresh MCP server',
        type: 'MCP_SERVER',
      }),
    ],
    total: 1,
    since: '2026-10-08T09:00:00Z',
    generatedAt: '2026-10-22T09:00:00Z',
    ...over,
  };
}

/** GET /policy with the defaults of application.yaml (ADR 0052). */
export const POLICY: Policy = {
  guide: { maxCandidates: 24, maxPicks: 3 },
  dailyLimits: {
    guideQuestions: 30,
    webSearches: { guest: { search: 3, guide: 3 }, registered: null },
  },
  retention: {
    cachedSearchesSeconds: 86_400,
    cachedGuideAnswersSeconds: 86_400,
    translationsSeconds: 86_400,
    queryVectorsSeconds: 7 * 86_400,
    storedWebResultsSeconds: 7 * 86_400,
    inactiveAccountsSeconds: 730 * 86_400,
    adminRecordsSeconds: 730 * 86_400,
    handledReportsSeconds: 365 * 86_400,
    runtimeSettingChangesSeconds: 730 * 86_400,
  },
};

/**
 * GET /admin/review-items/{id}/evidence for [item] (ADR 0045): a run with a conflicting pricing quoted twice and a
 * verified licence; no relationship.
 */
export function makeReviewEvidence(
  item: ReviewItem,
  over: Partial<ReviewEvidence> = {},
): ReviewEvidence {
  return {
    item,
    run: {
      id: '88888888-8888-5888-8888-888888888888',
      provider: 'nebius',
      model: 'nvidia/nemotron-test',
      promptVersion: 'enrich-v9',
      outcome: 'PARTIAL',
      startedAt: '2026-11-01T10:00:00Z',
      finishedAt: '2026-11-01T10:00:05Z',
      extraEvidence: 'github-readme:acme/tool',
    },
    validationErrors: ['pricing: the quotes disagree'],
    fields: [
      {
        field: 'pricing',
        value: 'FREE',
        origin: 'AI_EXTRACTED',
        verification: 'CONFLICTING',
        quotes: [
          {
            excerpt: 'Free for everyone.',
            sourceUrl: 'https://acme.example/pricing',
            sourceId: 'github',
            retrievedAt: '2026-11-01T09:00:00Z',
          },
          {
            excerpt: 'Pro plan: 20 USD a month.',
            sourceUrl: 'javascript:alert(1)',
            sourceId: null,
            retrievedAt: '2026-11-01T09:00:00Z',
          },
        ],
      },
      {
        field: 'license',
        value: 'MIT',
        origin: 'AI_EXTRACTED',
        verification: 'VERIFIED',
        quotes: [],
      },
    ],
    relationship: null,
    aiContent: {
      aiGenerated: true,
      provider: 'nebius',
      models: ['nvidia/nemotron-test'],
      fields: ['fields[].value'],
      policy: '/mission',
    },
    ...over,
  };
}

/** A few categories as GET /categories answers them (ADR 0053), in the configured order. */
export const CATEGORIES: Category[] = [
  { id: 'writing', name: 'Writing', shortName: 'Writing', family: 'everyday-apps' },
  { id: 'translation', name: 'Translation', shortName: 'Translation', family: 'everyday-apps' },
  { id: 'coding', name: 'Coding', shortName: 'Coding', family: 'developers' },
];
