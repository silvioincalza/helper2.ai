import type { components, operations } from './schema';

type S = components['schemas'];

/** Query parameters of GET /search, from the contract. */
export type SearchQuery = NonNullable<operations['search']['parameters']['query']>;

export type AudiencePath = S['AudiencePath'];
export type Audience = S['Audience'];
export type ResourceType = S['ResourceType'];
export type SortOrder = S['SortOrder'];
export type TriState = S['TriState'];
export type SourceMode = S['SourceMode'];
export type SourceOutcome = S['SourceOutcome'];
export type ServedFrom = S['ServedFrom'];
export type Freshness = S['Freshness'];
export type ValueOrigin = S['ValueOrigin'];
export type Verification = S['Verification'];
export type DateFact = S['DateFact'];
export type ResultCard = S['ResultCard'];
export type PopularityMetric = S['PopularityMetric'];
export type SourceStatus = S['SourceStatus'];
export type SearchResponse = S['SearchResponse'];
export type RecentResponse = S['RecentResponse'];
export type UpcomingEventsResponse = S['UpcomingEventsResponse'];
export type Facets = S['Facets'];
export type FacetValue = S['FacetValue'];
export type Evidence = S['Evidence'];
export type Relationship = S['Relationship'];
export type ResourceDetail = S['ResourceDetail'];
export type SetupGuide = S['SetupGuide'];
export type SetupOption = S['SetupOption'];
export type SetupClient = S['SetupClient'];
export type ToolGuide = S['ToolGuide'];
export type IssueReportRequest = S['IssueReportRequest'];
export type IssueReportAccepted = S['IssueReportAccepted'];
export type SourceInfo = S['SourceInfo'];
export type Me = S['Me'];
export type Bookmark = S['Bookmark'];
export type BookmarkRequest = S['BookmarkRequest'];
export type ReviewItem = S['ReviewItem'];
export type ReviewItemPage = S['ReviewItemPage'];
export type ReviewStatus = S['ReviewStatus'];
export type ReviewResolution = S['ReviewResolution'];
export type ScoutRun = S['ScoutRun'];
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
export type GuideStep = { [K in GuideStepEvent]: { kind: K; data: GuideStepData[K] } }[GuideStepEvent];
export type GuideStreamChecked = S['GuideStreamChecked'];
export type GuideResponse = S['GuideResponse'];
export type GuideRecommendation = S['GuideRecommendation'];
