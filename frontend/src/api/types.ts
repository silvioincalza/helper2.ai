import type { components, operations } from './schema';

type S = components['schemas'];

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
export type Me = S['Me'];
export type Preferences = S['Preferences'];
export type PreferencesChange = S['PreferencesChange'];
export type Bookmark = S['Bookmark'];
export type BookmarkRequest = S['BookmarkRequest'];
export type ReviewItem = S['ReviewItem'];
export type ReviewItemPage = S['ReviewItemPage'];
export type ReviewStatus = S['ReviewStatus'];
export type ReviewKind = S['ReviewKind'];
export type ReviewResolution = S['ReviewResolution'];
export type ScoutRun = S['ScoutRun'];
export type EnrichmentRecheck = S['EnrichmentRecheck'];
export type HomeExamplePool = S['HomeExamplePool'];
export type AdminHomeExample = S['AdminHomeExample'];
export type HomeExampleInput = S['HomeExampleInput'];
export type HomeExampleChange = S['HomeExampleChange'];
export type ExampleCheckOutcome = S['ExampleCheckOutcome'];
export type ExampleDrafts = S['ExampleDrafts'];
export type AuditEntryPage = S['AuditEntryPage'];
export type AdminOverview = S['AdminOverview'];
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
