# 0066 — Curated field: an admin sets a resource's own value, over every source and every run

**Status.** Accepted. **Builds on:** [0004](../foundation/0004-persistence-and-provenance.md), [0019](../scout/0019-evidence-gated-enrichment.md), [0022](../scout/0022-next-events.md), [0045](0045-back-office.md), [0056](../scout/0056-evidence-ladder.md), [0062](../scout/0062-writing-about-a-resource.md)

## Context
An enrichment check shows what the model said about a resource and the quotes behind it. Sometimes the model is wrong
and the quote does not help: the price is the subject's, the licence is an old one, the type is a guess. The admin
could accept or reject the check, and nothing else. The wrong value stayed until a new run, and a new run could say
the same thing again. A value a source states could not be fixed at all.

## Decision
1. **Set a value:** `PUT /admin/resources/{id}/fields/{field}` with `value`, for the fields that have a column of
   their own: type, pricing, license, openSource, techLevel and audience. The value must be one the field takes
   (an SPDX id for a licence; no OTHER type, no UNKNOWN audience).
   - The value is written to the resource's column, and the field is listed in `resource.curated_fields`.
   - A CURATED, VERIFIED field assertion says where it came from; the page shows "Curated by Helper2".
   - The AI's values of the field are rejected.
2. **It wins everywhere, and stays.**
   - No record changes it: the catalog upsert keeps a listed column, whatever the origin of the record
     (`ResourceStore`).
   - No run changes it: the value goes into the run's stated facts, so the evidence ladder does not read more for
     it, and the validator drops a model value for it without an error, so no review item opens for it.
   - The resource page shows it before a source conflict, the source's value and the enrichment (`FieldStates`).
     Filters and ranking read the column. A card is merged from the source records, then takes the curated
     columns over them and drops the sources' conflict on those fields (`ResourceCards`), so the page card shows it.
     In a search, the catalog's hit carries the curated fields, and the merge takes its values over the other
     sources' with no conflict (`ResultMerger`), so the search cards show it too.
   - The event seeds replace only the fields they assert (eventDates, eventFormat), so they leave an admin's value
     on the same event alone (`scripts/industry-events-seed.mjs`).
3. **Drop the AI's value:** `POST /admin/resources/{id}/fields/{field}/drop`, for any field the AI writes, use case
   and platforms too. It rejects the AI's values, as the code re-check does
   ([0056](../scout/0056-evidence-ladder.md)). A value a source states or an admin set stays. The page then shows the
   source's value, or "Not stated": a rejected estimate is not shown (`FieldStates`).
4. **Audit:** `SET_FIELD` (`SET <field>`) and `DROP_FIELD` (`DROPPED <field> <count>`), with the resource id.
5. **Back office:** next to each field of an enrichment check, "Set the value" (a list of the field's values, or the
   licence id) and "Drop the AI value". Both read the evidence again.

## Consequences
- The admin fixes a wrong fact once, and it stays fixed: the Engadget item can be set to NEWS, its pricing dropped.
- A curated value is not AI-written: no AI label, no `data-ai-generated` mark.
- Known limits (roadmap):
  - platforms and use cases are labels rebuilt from the records: they can be dropped, not set;
  - there is no "back to the sources" yet: an admin sets another value instead;
  - the editorial seeds (`R__curated_seed`, `R__courses`, the event seeds) write their own columns when they run
    again, over an admin's value on the same entry. They are editorial too; a seed change is a deliberate edit.

## Security and privacy (STRIDE)
- **Spoofing / elevation**: ADMIN only, admin host behind the VPN, CSRF ([0035](0035-admin-host.md)).
- **Tampering**: the field is one of a fixed list, the column is the code's constant, the value is checked against
  the field's values; SQL takes the value only as a parameter.
- **Repudiation**: every change is in the append-only audit trail.
- **Information disclosure**: none new; the values are public facts of a public resource. The catalog holds no
  administrator id.
- **Denial of service**: one admin, one row per change.

## Alternatives considered
- **Only a CURATED assertion, read when shown** — the page could show it, but search cards, filters and ranking read
  the columns: the wrong value would still be found and filtered on.
- **A new data origin per field** — the row-level origin rank cannot say "this one field"; a list of curated fields
  can, and costs one array column.
- **Reject the whole check and wait for a better run** — the next run reads the same pages and can say the same
  thing.

## Code
- `backend/libs/catalog/src/main/java/ai/helper2/catalog/usecase/CurateField.java`, `catalog/internal/CuratedFields.java`.
- `backend/libs/catalog/src/main/java/ai/helper2/catalog/internal/ResourceStore.java` (the upsert keeps a curated column),
  `ResourceCards.java` and `backend/libs/search/src/main/kotlin/ai/helper2/search/Model.kt` (`withCurated`: the card takes it;
  `SourceHit.curatedFields`), `search/ResultMerger.kt` (a search merge takes it), `CatalogSearchSource.java`,
  `JdbcEnrichmentCandidates.java`, `catalog/enrichment/EnrichmentCandidates.java` (the curated values).
- `backend/apps/helper2/src/main/java/ai/helper2/scout/internal/CatalogEnrichmentStore.java`,
  `backend/apps/helper2/src/main/kotlin/ai/helper2/scout/Enrichment.kt` (`curatedFields`), `EvidenceValidator.kt`.
- `backend/apps/helper2/src/main/java/ai/helper2/admin/web/ReviewController.java`; `api/admin.yaml` (`ResourceField`,
  `ResourceFieldValue`, audit actions).
- `backend/libs/schema/src/main/resources/db/migration/V22__curated_fields.sql`; `scripts/industry-events-seed.mjs` and the two
  event seeds it writes.
- `frontend/src/components/results/FieldStates.tsx`, `frontend/src/pages/admin/FieldCuration.tsx`,
  `ReviewEvidence.tsx`.
- Tests: `CuratedFieldApiIT`, `CatalogEnrichmentStoreTest`, `EvidenceValidatorWritingTest`, `ResourcePage.test.tsx`,
  `FieldCuration.test.tsx`.
- Diagrams: [flow](../../diagrams/flow-0066-curated-field.svg) · [sequence](../../diagrams/sequence-0066-curated-field.svg).
