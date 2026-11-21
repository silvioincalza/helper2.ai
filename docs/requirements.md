# Requirements: helper2.ai

The requirements catalog of helper2.ai, derived from [the vision](vision.md). The terms are those of
[the glossary](glossary.md). Every requirement here is built and running; the use cases under `docs/use_cases/`
describe how.

## Functional Requirements

| ID     | Title                        | User Story | Priority | Status |
|--------|------------------------------|------------|----------|--------|
| FR-001 | Search Once                  | As a visitor, I want one query to search every enabled source and the catalog at once so that I see all AI resources for my need on one page. | High | Implemented |
| FR-002 | Filter and Sort Results      | As a visitor, I want to filter the results by category and type and to sort them so that I narrow a long list to what fits my need. | High | Implemented |
| FR-003 | Query in Any Language        | As a visitor, I want to search in my own language so that I find resources without knowing the English words for them. | Medium | Implemented |
| FR-004 | Source Status per Search     | As a visitor, I want to see what each source returned for my search so that I know whether the results are complete. | High | Implemented |
| FR-005 | Source Coverage              | As a visitor, I want to see the list of sources with their health and response time so that I know what helper2.ai covers and what it does not. | Medium | Implemented |
| FR-006 | Example Searches             | As a visitor, I want the home page to offer example searches so that I learn what I can ask before I type. | Low | Implemented |
| FR-007 | Ask the Guide                | As a visitor, I want to describe my need in my own words and get at most a few recommended resources so that I do not have to read every result. | High | Implemented |
| FR-008 | Quoted Reason per Pick       | As a visitor, I want each pick of the guide to show a quote from its source and a reason so that I can check why it was recommended. | High | Implemented |
| FR-009 | Keep the Search While Asking | As a visitor, I want the guide to answer on the same page without losing my query and filters so that I can go back to the results at any time. | High | Implemented |
| FR-010 | Resource Details             | As a visitor, I want a page per resource with its facts, where each fact came from, and its relationships so that I can judge it before I use it. | High | Implemented |
| FR-011 | Setup Guide                  | As a visitor, I want to see how to install a resource and connect it to my AI tools, from its own documentation, so that I can start using it without reading the whole documentation. | Medium | Implemented |
| FR-012 | New Resources and News       | As a visitor, I want a list of the newest resources and AI news so that I see what is new in the AI world. | Medium | Implemented |
| FR-013 | Upcoming Events              | As a visitor, I want a list of the next AI events, soonest first, so that I can plan which ones to attend. | Low | Implemented |
| FR-014 | Report Wrong Information     | As a visitor, I want to report wrong or outdated information on a resource so that a person corrects it. | High | Implemented |
| FR-015 | Save Resources Without Account | As a visitor, I want to save resources in my browser without signing in so that I can come back to them later. | Medium | Implemented |
| FR-016 | Sign In with GitHub          | As a visitor, I want to sign in with my GitHub account so that my saved resources follow me to every device. | Medium | Implemented |
| FR-017 | Merge Saved Resources        | As a signed-in user, I want the resources I saved in the browser to join my account when I sign in so that I lose none of them. | Medium | Implemented |
| FR-018 | Display Preferences          | As a signed-in user, I want to change my display settings so that the site looks the way I prefer on every device. | Low | Implemented |
| FR-019 | Delete My Account            | As a signed-in user, I want to delete my account and my saved resources at once so that no data about me stays behind. | High | Implemented |
| FR-020 | Hub Pages                    | As a visitor arriving from a search engine, I want one page per resource type so that I can browse that type directly. | Medium | Implemented |
| FR-021 | Policy Pages                 | As a visitor, I want to read the mission, the privacy notice and the terms, with the limits and retention periods the site applies, so that I know how my data is used. | High | Implemented |
| FR-022 | Support the Site             | As a visitor, I want to support helper2.ai through the provider's own page so that the site keeps running. | Low | Implemented |
| FR-023 | Consent to Analytics and Ads | As a visitor in Europe, I want to accept or reject analytics and personalized ads so that nothing is counted or personalized without my consent. | High | Implemented |
| FR-024 | Notify Search Engines        | As an administrator, I want changed pages to be sent to search engines once a day so that search engines index new resource pages within a day. | Low | Implemented |
| FR-025 | Collect New Resources        | As an administrator, I want the Scout to collect new resources from the sources on a schedule, and on demand, so that the catalog stays current. | High | Implemented |
| FR-026 | Discovery Hosts              | As an administrator, I want to add and remove the hosts discovery may read so that the Scout reads only sites about AI. | Medium | Implemented |
| FR-027 | List Seeds                   | As an administrator, I want to add GitHub repositories and web pages that list AI resources so that the Scout reads them too. | Medium | Implemented |
| FR-028 | Evidence-Backed Enrichment   | As a visitor, I want every description and field the AI added to a resource to rest on a checked quote so that I can trust the catalog. | High | Implemented |
| FR-029 | Enrichment Queue             | As an administrator, I want to see the enrichment queue and skip, requeue or prioritise a resource in it so that the model budget goes to the right resources first. | Medium | Implemented |
| FR-030 | Keep the Catalog About AI    | As an administrator, I want to check the catalog against the "is it about AI" words and remove what is not so that search shows only AI resources. | Medium | Implemented |
| FR-031 | Review Candidates            | As an administrator, I want to accept, reject or fix each candidate, report and conflicting fact the machine could not decide so that nothing unverified is published. | High | Implemented |
| FR-032 | Decide Several at Once       | As an administrator, I want to decide several review items in one action, and all relationship candidates of one target name at once, so that the queue does not pile up. | Medium | Implemented |
| FR-033 | Curate Resource Fields       | As an administrator, I want to set a resource's own value of a field or drop what the AI said about it so that a wrong value is corrected for good. | Medium | Implemented |
| FR-034 | Research Help for Review     | As an administrator, I want the model to research a review item and show every step it took so that I can decide faster with the evidence in front of me. | Medium | Implemented |
| FR-035 | Known Targets                | As an administrator, I want to keep the list of vendors, services and tools a relationship may name without a catalog resource so that relationships to them are not lost. | Medium | Implemented |
| FR-036 | Shadow Libraries             | As an administrator, I want to keep a list of shadow library names no search shows so that helper2.ai never leads to pirated content. | High | Implemented |
| FR-037 | Home Examples                | As an administrator, I want to add, draft with the model, change, pause and remove home examples, each checked regularly, so that every example shows good results. | Low | Implemented |
| FR-038 | Runtime Settings             | As an administrator, I want to change the spending limits and switches at runtime, and see the settings in force, so that I control costs without a deploy. | High | Implemented |
| FR-039 | Audit Log                    | As an administrator, I want a record of every administrator action, newest first, so that each change can be traced. | Medium | Implemented |
| FR-040 | Purge Expired Data           | As a visitor, I want every piece of data deleted when its retention period ends so that nothing about me is kept longer than needed. | High | Implemented |

## Non-Functional Requirements

| ID      | Title                      | Requirement | Category | Priority | Status |
|---------|----------------------------|-------------|----------|----------|--------|
| NFR-001 | Search Response Time       | At 5 search requests per second, search answers within 1 second at p50, 3 seconds at p95 and 4 seconds at p99, with less than 1% failed requests. | Performance | High | Implemented |
| NFR-002 | Guide Search Response Time | At 5 requests per second, the guide's search-only path answers within 3 seconds at p50 and 8 seconds at p95, with less than 1% failed requests. | Performance | High | Implemented |
| NFR-003 | Degraded Mode              | Search returns results when Redis, the language model, or one or more sources are down; the guide then shows the top search results instead of picks. | Availability | High | Implemented |
| NFR-004 | Verbatim Quote             | Every guide pick names a retrieved candidate, and its quote appears word for word in that candidate's text, with at least 40 characters and 6 words, or the whole text. | Security | High | Implemented |
| NFR-005 | No Invented Facts          | No date, popularity figure or fact that no source stated is shown; an unknown value is labeled as unknown. | Maintainability | High | Implemented |
| NFR-006 | Guide Rate Limits          | Each client may ask at most 10 questions per minute and 30 new questions per day, with at most 8 questions answered at a time. | Scalability | High | Implemented |
| NFR-007 | Daily Model Budget         | The language model is called at most 600 times per day in total for the guide. | Scalability | High | Implemented |
| NFR-008 | Answer Cache               | An AI answer to a repeated question is served from the cache for 24 hours without a new model call. | Performance | Medium | Implemented |
| NFR-009 | Accessibility              | Keyboard-only and screen-reader users can complete search, ask, evaluate, report and save (UC-001, UC-005, UC-007, UC-009, UC-012), and the public pages meet WCAG 2.2 level AA. | Usability | High | Implemented |
| NFR-010 | AI Text Marking            | 100% of AI-written text is labeled in the UI and marked machine-readable in the HTML and the API. | Usability | High | Implemented |
| NFR-011 | Search Quality Gate        | A search change does not let nDCG@10 on the recorded evaluation set fall more than 0.005 below the stored baseline. | Maintainability | High | Implemented |
| NFR-012 | Back Office Access         | 100% of back office requests come through the VPN and need a signed-in user with the administrator role. | Security | High | Implemented |
| NFR-013 | Model Provenance           | Every guide answer records the provider and the model ids that wrote it, and the page shows them. | Maintainability | Medium | Implemented |
| NFR-014 | Daily Enrichment Budget    | The language model is called at most 500 times per day in total for enrichment, gap rounds included; an administrator may raise the limit up to 3,000. | Scalability | High | Implemented |
| NFR-015 | Live Web Budget            | Each visitor gets at most 3 live web searches per day for search and 3 for the guide, and the Scout spends at most 40 live web credits per day. | Scalability | High | Implemented |

## Constraints

| ID    | Title                   | Constraint | Category | Source | Priority | Status |
|-------|-------------------------|------------|----------|--------|----------|--------|
| C-001 | NVIDIA Models Only      | Every language model is an NVIDIA model (Nemotron on Nebius Token Factory); the application does not start with any other model. | Technical | | High | Implemented |
| C-002 | Embedding in the Cluster | The embedding model runs inside the cluster. | Technical | | High | Implemented |
| C-003 | Model Decides Nothing True | The order is retrieval first, the model second, a deterministic validator last; the model never decides what is true. | Technical | | High | Implemented |
| C-004 | No Fine-Tuning          | No fine-tuning and no substantial model change without a new scope check under the EU AI Act. | Regulatory | EU AI Act (Regulation (EU) 2024/1689) | High | Implemented |
| C-005 | AI Text Transparency    | Every AI-written text shown to users is labeled and marked machine-readable. | Regulatory | EU AI Act Art. 50 | High | Implemented |
| C-006 | Storage Limitation      | Each kind of data is kept only as long as it serves its purpose, then deleted. | Regulatory | GDPR Art. 5(1)(e); nFADP Art. 6(4) | High | Implemented |
| C-007 | Consent for Cookies     | Analytics and advertising cookies are set only after consent in the EU. | Regulatory | ePrivacy Directive Art. 5(3) | High | Implemented |
| C-008 | Source Terms            | Every source and API is used within its own terms of use. | Regulatory | The terms of each source ([sources](architecture/sources.md)) | High | Implemented |
| C-009 | Digital Services Act    | The site meets the duties of the Digital Services Act for an intermediary service. | Regulatory | Regulation (EU) 2022/2065 (DSA) | High | Implemented |
| C-010 | EU Hosting              | Every server runs in the EU. | Operational | | High | Implemented |
| C-011 | Deployment              | The system runs on Kubernetes with Helm charts for staging and production, with no dependency on a specific cluster or infrastructure repository. | Operational | | High | Implemented |
| C-012 | Secrets in OpenBao      | Secrets live in OpenBao, never in the repository; configuration keys carry the `H2_` prefix. | Operational | | High | Implemented |
| C-013 | Open Source             | The source code is public under the Apache-2.0 license. | Business | | High | Implemented |
| C-014 | One-Person Team         | The system is built and run by one person, so each choice is weighed by its operational cost and cognitive load. | Business | | High | Implemented |
| C-015 | Disclosed Sources       | Search reads only the disclosed set of sources and never claims to search the whole web. | Business | | High | Implemented |
| C-016 | Named Recipients        | Every recipient of data is named on the Privacy page, with the basis of each transfer outside Switzerland and the EU ([data processing](architecture/data-processing.md)). | Regulatory | GDPR Art. 13(1)(e)-(f), Art. 44-49; nFADP Art. 16-17, Art. 19 | High | Implemented |
| C-017 | AI Literacy             | The people who build and run the AI features know the models, their limits, their evaluation and their cost ([AI literacy](architecture/ai-literacy.md)). | Regulatory | EU AI Act Art. 4 | High | Implemented |
