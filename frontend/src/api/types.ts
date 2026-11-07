import type { components as AdminComponents } from './admin-schema';
import type { components, operations } from './schema';

/** Public API types (api/public.yaml). */
type S = components['schemas'];
/** Back office types (api/admin.yaml), used only by the lazy admin pages (ADR 0045). */
type A = AdminComponents['schemas'];

/** Query parameters of GET /search, from the contract. */
export type SearchQuery = NonNullable<operations['search']['parameters']['query']>;

export type ResourceType = S['ResourceType'];
export type SortOrder = S['SortOrder'];
export type AudiencePath = S['AudiencePath'];
export type TriState = S['TriState'];
export type SourceMode = S['SourceMode'];
export type ValueOrigin = S['ValueOrigin'];
export type Verification = S['Verification'];
export type DateFact = S['DateFact'];
export type ResultCard = S['ResultCard'];
export type TrailRemoved = S['GuideTrailRemoved'];
export type SourceStatus = S['SourceStatus'];
export type SkipReason = S['SkipReason'];
export type SearchResponse = S['SearchResponse'];
export type RecentResponse = S['RecentResponse'];
export type UpcomingEventsResponse = S['UpcomingEventsResponse'];
export type Facets = S['Facets'];
export type FacetValue = S['FacetValue'];
export type Evidence = S['Evidence'];
export type Relationship = S['Relationship'];
export type Assertion = S['Assertion'];
export type ResourceDetail = S['ResourceDetail'];
export type SetupGuide = S['SetupGuide'];
export type SetupOption = S['SetupOption'];
export type SetupClient = S['SetupClient'];
export type ToolGuide = S['ToolGuide'];
export type IssueReportRequest = S['IssueReportRequest'];
export type IssueReportAccepted = S['IssueReportAccepted'];
export type SourceInfo = S['SourceInfo'];
export type Policy = S['Policy'];
export type WebSearchLimits = S['WebSearchLimits'];
export type Category = S['Category'];
export type Me = S['Me'];
export type Preferences = S['Preferences'];
export type PreferencesChange = S['PreferencesChange'];
export type Bookmark = S['Bookmark'];
export type BookmarkRequest = S['BookmarkRequest'];
export type ReviewItem = A['ReviewItem'];
export type ReviewItemPage = A['ReviewItemPage'];
export type ReviewStatus = A['ReviewStatus'];
export type ReviewKind = A['ReviewKind'];
export type ReviewResolution = A['ReviewResolution'];
export type ReviewEvidence = A['ReviewEvidence'];
export type RelationshipTargetChange = A['RelationshipTargetChange'];
export type NewResource = A['NewResource'];
export type CandidateEdit = A['CandidateEdit'];
export type CandidateType = A['CandidateType'];
export type ResourceField = A['ResourceField'];
export type ResourceFieldValue = A['ResourceFieldValue'];
export type ReviewQuote = A['ReviewQuote'];
export type ReviewBulkResolution = A['ReviewBulkResolution'];
export type ReviewBulkResult = A['ReviewBulkResult'];
export type ScoutRun = A['ScoutRun'];
export type EnrichmentRecheck = A['EnrichmentRecheck'];
export type HomeExamplePool = S['HomeExamplePool'];
export type AdminHomeExample = A['AdminHomeExample'];
export type HomeExampleInput = A['HomeExampleInput'];
export type HomeExampleChange = A['HomeExampleChange'];
export type ExampleCheckOutcome = A['ExampleCheckOutcome'];
export type ExampleDrafts = A['ExampleDrafts'];
export type AuditEntryPage = A['AuditEntryPage'];
export type AdminOverview = A['AdminOverview'];
export type EnrichmentQueuePage = A['EnrichmentQueuePage'];
export type EffectiveConfig = A['EffectiveConfig'];
export type ConfigSetting = A['ConfigSetting'];
export type EnrichmentQueueEntry = A['EnrichmentQueueEntry'];
export type EnrichmentQueueView = A['EnrichmentQueueView'];
export type RuntimeSettings = A['RuntimeSettings'];
export type RuntimeSettingState = A['RuntimeSettingState'];
export type DiscoveryHosts = A['DiscoveryHosts'];
export type DiscoveryHostEntry = A['DiscoveryHostEntry'];
export type DiscoveryHostKind = A['DiscoveryHostKind'];
export type Problem = S['Problem'];
export type Notice = S['Notice'];
export type GuideRequest = S['GuideRequest'];
/** The data each step event of POST /guide/stream carries (the event table of that operation). */
interface GuideStepData {
  planned: S['GuideStreamPlanned'];
  understood: S['GuideStreamUnderstood'];
  clarify: S['GuideClarification'];
  retrieved: S['GuideStreamRetrieved'];
  chosen: S['GuideStreamChosen'];
  validated: S['GuideStreamValidated'];
  checked: S['GuideStreamChecked'];
}
/** Every event of the contract but the last one (`answer` or `error`) is a step. */
export type GuideStepEvent = Exclude<S['GuideStreamEvent'], 'answer' | 'error'>;
/** One real step of the guide, as streamed by POST /guide/stream. */
export type GuideStep = {
  [K in GuideStepEvent]: { kind: K; data: GuideStepData[K] };
}[GuideStepEvent];
export type GuideResponse = S['GuideResponse'];
export type Monetisation = S['Monetisation'];
