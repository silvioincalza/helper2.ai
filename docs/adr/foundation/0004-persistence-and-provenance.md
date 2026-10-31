# 0004 — PostgreSQL as system of record; ids from canonical URLs; source records; dates never invented; one schema migration, forward-only once data must be kept, + repeatable seeds; catalog writes off the request path; the catalog owns its tables

**Status.** Accepted. **Builds on:** [0001](0001-stack-and-runtime.md), [0002](0002-modular-monolith-hexagonal.md)

## Context
The same resource reaches Helper2 from many places: a GitHub repository, its npm package, a Hugging Face
page, a news article. Helper2 must store it once, keep what each source said, and show only dates a source
stated or Helper2 observed itself. Searches see thousands of results a day, and writing them must not slow
the search down.

## Decision
1. **PostgreSQL is the system of record.** PostgreSQL 18.6 with pgvector 0.8.6 (own image,
   `deploy/postgres/Dockerfile`). Connection: `H2_DATABASE_URL`, `H2_DATABASE_USER`, `H2_DATABASE_PASSWORD`;
   pool size `H2_DATABASE_POOL_SIZE` (default 10), connection timeout 3000 ms.
2. **Ids from canonical web addresses (URLs).** `CanonicalUrl` forces `https`, lower-cases the host, drops `www.`, the
   default port, the fragment, `utm_*` and other tracking parameters and the trailing slash, and sorts the
   query. GitHub URLs become `https://github.com/{owner}/{repo}` in lower case; a sub-directory
   (`/tree/{ref}/{path}`) is kept, so each package of a monorepo has its own URL. `ResourceId` is a universally
   unique identifier (UUID) version 5 (RFC 9562) of that URL in a fixed namespace; Structured Query Language (SQL) computes the same id with `uuid_generate_v5`.
   A resource is named by the canonical URL of one of its own hits (a GitHub URL first), never by a repository link
   alone: two npm packages of one repository are two resources with two ids, and an npm package becomes part of its
   repository's resource only when the merge joins them ([0008](../search/0008-federated-search.md)).
3. **Source records.** `source_record` keeps one row per (`source_id`, `external_id`): exactly what the
   source said, `retrieved_at` and a content `fingerprint`. `resource` is the merged view. A write never
   overwrites the fields of a resource from a stronger origin (`DEMO` > `CURATED` > `INDEXED` > `OBSERVED`). Labels and
   event dates are rebuilt from the current records on every write.
   A record moves to another resource when its source points to another URL. When all the records of the old
   resource have moved to one resource, its user reports (`issue_report`) and review items (`review_item`) move with
   them (`ResourceStore.moveReportsOfEmptiedResources`). Then `deleteOrphans` deletes an `OBSERVED` resource left
   without records, unless a report or a review item still points at it. That happens when its records went to
   several resources: the new owner is not clear, so the resource and its reports stay for the reviewer.
4. **Dates are never invented.** `DateFact` is `KNOWN`, `UNKNOWN` or `CONFLICTING`. A source date before
   1995-01-01 or more than one day after retrieval becomes unknown. `CONFLICTING` means two sources state different
   days for the same date; the merge rules are in [0010](../search/0010-filters-facets-dates-timeline.md). Date columns are nullable;
   `published_precision = 'UNKNOWN'` exactly when `published_at` is null (a table check).
   `first_discovered_at` is Helper2's own observation, labeled as such.
5. **One schema migration, then forward-only; repeatable seeds.** `V1__schema.sql` is the whole schema: the tables,
   checks, indexes, triggers and functions the code needs, and nothing it does not use. Every foreign key states its
   delete rule (`CASCADE`, `SET NULL`, `RESTRICT` or `NO ACTION`, [0034](../operations/0034-data-retention.md)).
   `V1__schema.sql` is edited in place only while no environment holds data that must be kept; such an edit needs
   an empty database in every environment ([deploy/k8s/RESET-DATABASE.md](../../../deploy/k8s/RESET-DATABASE.md)).
   Once an environment holds data that must be kept, `V1__schema.sql` is never edited again: an edit changes its
   checksum, and Flyway refuses to start. Every change is then a new versioned migration (`V2__…`), forward-only.
   After the versioned migrations, Flyway runs the seeds `R__curated_seed.sql`, `R__industry_events.sql`,
   `R__community_events.sql` and `R__courses.sql`. The seeds upsert, so Flyway re-runs one whenever it changes.
   The events seed also removes the curated events that are not in its research file ([0022](../scout/0022-next-events.md)).
6. **Catalog writes off the request path.** `SearchResources` reads first-discovery dates in one batched
   query (`ResultObserver.firstDiscovered`), then hands only the results fetched live in this search to
   `ResultObserver.record`. `ResourceCatalog.record` queues the write in `ObservationQueue`: one thread
   (`catalog-observations`), a bounded queue (`helper2.catalog.observation-queue`, 256). A full queue drops
   the new write. Meters: `helper2.catalog.observations{outcome=written|dropped|failed}` and
   `helper2.catalog.observations.queued`. The Scout writes synchronously (`ResourceCatalog.index`).
7. **The catalog owns its tables.** Other modules write through catalog ports: search through `ResultObserver`,
   the Scout through `ResourceCatalog.index`, `ReviewQueue` and `EnrichmentResults` ([0019](../scout/0019-evidence-gated-enrichment.md)).
   The SQL is in `catalog/internal` (`ResourceStore`, `JdbcEnrichmentResults`, `IssueReports`, `ReviewDecisions`);
   catalog use cases call those classes and hold no SQL of their own.

## Consequences
- A search never waits for a catalog write.
- One writer thread and rows written in canonical-URL order keep concurrent upserts from deadlocking.
- If the first-discovery read fails, the search still answers, with an `INDEX_UNAVAILABLE` notice and
  unknown discovery dates.
- On shutdown the queue gets 5 seconds to finish.
- The index can be rebuilt by the Scout and the embedding indexer. Bookmarks and reports are user data, kept by
  the backup ([0027](../operations/0027-runtime-topology.md)).
- How long each table keeps its rows, and the daily purges that enforce it, are in
  [0034](../operations/0034-data-retention.md); the foreign keys and indexes those purges use are part of `V1__schema.sql`.
- One file holds the whole schema, so a reader sees every table as it is, not a chain of changes.
- A deploy whose schema does not match the database's migration history stops at startup: the new pods never
  become ready, the deploy rolls back and the old pods keep serving ([0028](../operations/0028-ci-cd.md)).
- `SchemaContractTest` checks the migrated schema with plain JDBC: every foreign key's delete rule, an index on every
  foreign key a delete follows, every check list equal to the domain vocabulary it stores, and no table or column
  the code does not use.
- A merge of two resources never loses a user report. A resource whose records were split over several resources
  stays, without records, until a reviewer handles its reports. `ResourceStoreIT` covers both cases.
- `ObservationQueueTest` covers the queue (background write, a full queue drops the new write, shutdown). `SearchApiIT` checks the result: unknown
  discovery date on the first search, known on the next.

## Alternatives considered
- Write in the request — every search waits for a multi-row upsert, and parallel searches lock the same rows.
- An event bus or outbox for the writes — durable, but a second moving part for data that the next
  search records again anyway.
- Random ids — the same URL from two sources would become two resources, and links would not be stable.
- A document store — the data is relational (records, evidence, labels, bookmarks), and PostgreSQL
  also gives full-text search and vectors.
- One versioned migration per feature from the start — a longer chain to read and replay, and a history of fixes to
  rows that a new database never has.
- A Flyway baseline on the old history — the files would no longer describe how the database was built, and
  every environment would still carry the old columns and checks.

## Code
- `backend/src/main/kotlin/ai/helper2/shared/CanonicalUrl.kt`, `ResourceId.kt`, `DateFact.kt` — identity and dates.
- `backend/src/main/java/ai/helper2/catalog/ResourceCatalog.java` — `ResultObserver`, `index`.
- `backend/src/main/java/ai/helper2/catalog/internal/ObservationQueue.java` — background writer.
- `backend/src/main/java/ai/helper2/catalog/internal/ResourceStore.java` — upserts, origin rank, labels, moved
  reports, orphan deletion.
- `backend/src/main/java/ai/helper2/catalog/internal/JdbcEnrichmentResults.java`, `IssueReports.java`,
  `ReviewDecisions.java` — enrichment results, user reports and review decisions.
- `backend/src/main/kotlin/ai/helper2/search/usecase/SearchResources.kt` — read dates, then `record`.
- `backend/src/main/resources/db/migration/V1__schema.sql`, `R__*.sql` — schema and seeds.
- `backend/src/test/java/ai/helper2/SchemaContractTest.java` — the schema contract.
- `deploy/k8s/RESET-DATABASE.md` — empty database for an in-place edit of `V1__schema.sql`.
- `deploy/postgres/Dockerfile` — PostgreSQL with pgvector, pinned by commit and checksum.

## Diagrams
[flow](../../diagrams/flow-0004-persistence-and-provenance.svg) · [sequence](../../diagrams/sequence-0004-persistence-and-provenance.svg)

## Roadmap
- Discovery date unknown on a resource's first search: [roadmap](../../roadmap.md#6-search-and-catalog).
- Delete indexed resources left without source records: [roadmap](../../roadmap.md#6-search-and-catalog).
- Batch the per-item queries of recent items, events and evidence: [roadmap](../../roadmap.md#6-search-and-catalog).
- One owner per table (the Scout reads catalog tables for its enrichment backlog): [roadmap](../../roadmap.md#8-architecture).
- Known limits: [roadmap § 10](../../roadmap.md#10-known-limits).
