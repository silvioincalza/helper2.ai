# Entity Model

## Modules

| Module                                    | Description                                                                 | Entities | Depends on |
|-------------------------------------------|-----------------------------------------------------------------------------|----------|------------|
| [Search](entity_model/search.md)          | Finds AI resources across the catalog and live sources with one query       | LIVE_WEB_QUERY_RESULT, SHADOW_LIBRARY, HOME_EXAMPLE | - |
| [AI Guide](entity_model/ai-guide.md)      | Answers needs only from retrieved results, and writes setup guides          | GUIDE_SIMILAR_QUESTION | - |
| [Catalog](entity_model/catalog.md)        | The stored AI resources, where each fact came from, and how they relate     | SOURCE, RESOURCE, RESOURCE_LABEL, SOURCE_RECORD, EVIDENCE, RELATIONSHIP, RELATIONSHIP_EVIDENCE, AWESOME_LIST, AWESOME_LIST_ENTRY, DIRECTORY_LIST_ENTRY, KNOWN_TARGET, KNOWN_TARGET_NAME, ISSUE_REPORT, REMOVED_RESOURCE | Enrichment |
| [Accounts](entity_model/accounts.md)      | Optional GitHub sign-in, saved resources, and the record of administrator actions | APP_USER, BOOKMARK, ADMIN_AUDIT | - |
| [Site](entity_model/site.md)              | The public pages around search: hubs, policy pages and support             | PUBLIC_APP_SETTING | - |
| [Discovery](entity_model/discovery.md)    | Collects new AI resources from the sources and admits them as candidates   | SCOUT_RUN, SCOUT_SCHEDULE, SOURCE_CURSOR, DISCOVERY_HOST, LIST_SEED, LIST_SEED_LAST_READ | Catalog |
| [Enrichment](entity_model/enrichment.md)  | Adds descriptions, fields and embeddings to resources, each backed by a quote | ENRICHMENT_RUN, FIELD_ASSERTION, FIELD_ASSERTION_EVIDENCE, SUMMARY_CLAIM, SUMMARY_CLAIM_EVIDENCE, ENRICHMENT_QUEUE_CONTROL, RESOURCE_EMBEDDING, RESOURCE_EMBEDDING_REFUSED | Catalog |
| [Review](entity_model/review.md)          | A person decides what the machine could not                                | REVIEW_ITEM, CANDIDATE_DECISION, REVIEW_RESEARCH, REVIEW_RESEARCH_STEP | Catalog, Enrichment |
| [Operations](entity_model/operations.md)  | Runtime settings and the daily removal of data past its retention period   | RUNTIME_SETTING | - |
