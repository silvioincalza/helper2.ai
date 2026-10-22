import type { components } from './schema';

type S = components['schemas'];

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
export type Assertion = S['Assertion'];
export type ModelFamily = S['ModelFamily'];
export type ModelFamiliesResponse = S['ModelFamiliesResponse'];
export type ListedModel = S['ListedModel'];
export type Relationship = S['Relationship'];
export type ResourceDetail = S['ResourceDetail'];
export type SetupGuide = S['SetupGuide'];
export type SetupOption = S['SetupOption'];
export type SetupClient = S['SetupClient'];
export type ToolGuide = S['ToolGuide'];
export type ToolGuideRequest = S['ToolGuideRequest'];
export type IssueReportRequest = S['IssueReportRequest'];
export type IssueReportAccepted = S['IssueReportAccepted'];
export type SourceInfo = S['SourceInfo'];
export type Me = S['Me'];
export type Bookmark = S['Bookmark'];
export type BookmarkRequest = S['BookmarkRequest'];
export type ReviewItem = S['ReviewItem'];
export type ReviewStatus = S['ReviewStatus'];
export type ReviewResolution = S['ReviewResolution'];
export type ScoutRun = S['ScoutRun'];
export type Problem = S['Problem'];
export type Notice = S['Notice'];
export type GuideRequest = S['GuideRequest'];
export type GuideStreamPlanned = S['GuideStreamPlanned'];
export type GuideStreamRetrieved = S['GuideStreamRetrieved'];
export type GuideStreamChosen = S['GuideStreamChosen'];
export type GuideStreamValidated = S['GuideStreamValidated'];
export type GuideStreamChecked = S['GuideStreamChecked'];
/** One real step of the guide, as streamed by POST /guide/stream. */
export type GuideStep =
  | { kind: 'planned'; data: GuideStreamPlanned }
  | { kind: 'retrieved'; data: GuideStreamRetrieved }
  | { kind: 'chosen'; data: GuideStreamChosen }
  | { kind: 'validated'; data: GuideStreamValidated }
  | { kind: 'checked'; data: GuideStreamChecked };
export type GuideResponse = S['GuideResponse'];
export type GuideRecommendation = S['GuideRecommendation'];
