# 0065 — Candidate edit in the review list: title, type and tags before accepting

**Status.** Accepted. **Builds on:** [0045](0045-back-office.md), [0051](../scout/0051-tavily-discovery.md), [0058](../scout/0058-awesome-deep-search.md), [0061](../scout/0061-linkedin-via-tavily.md), [0062](../scout/0062-writing-about-a-resource.md)

## Context
A resource candidate is what its collector guessed from a URL and a title: "Pricing | Open WebUI", PRODUCT, with the
category tags of the search that found it. Often the guess is close but not right. A blog post comes in as a product,
or a page title still carries the site's name. The admin could only accept it as it was, or reject it and lose the
page.

The type was also locked to the finders: a discovery category declares only its product type and ARTICLE
([0061](../scout/0061-linkedin-via-tavily.md) §5), so a news item it found could never be accepted as NEWS.

## Decision
1. **One call edits an open resource candidate:** `PUT /admin/review-items/{id}/candidate` with at least one of
   `title` (1 to 300 characters, one line), `type` (any candidate type: products and tools, MODEL, DATASET,
   ARTICLE, NEWS, EVENT; never COURSE or OTHER) and `tags` (at most 12, tag-shaped). What is left out stays as the
   collector wrote it. It answers the item, with its new reason line. The item stays open.
2. **The edit is marked in the details** (`edited`: the fields the admin changed).
   - When another collector finds the page later ([0051](../scout/0051-tavily-discovery.md) §8,
     [0058](../scout/0058-awesome-deep-search.md)), it joins the candidate's finders. It does not change an edited
     type or edited tags, and never the title.
   - Accepting indexes what the admin wrote. A type the admin set need not be one a finder declares; it must still be
     a candidate type.
   - The accepted record keeps the list of edited fields in its extra (never shown). Who edited is in the audit
     trail, not in the catalog.
3. **No lost update.**
   - The form sends back the details it showed (`expectedDetails`). The edit applies only to those: when the
     candidate changed since the form opened (a collector, another admin), it is 409 `version-conflict`: reload,
     edit again.
   - The edit and a collector's merge both save only when the item still holds the details they read
     (`updateCandidate` compares them). A merge that lost the race is skipped and logged; the next run of that
     collector joins it again.
   - The new details and the `EDIT_CANDIDATE` entry are saved in one transaction: no edit without its audit entry.
   - Accepting locks the item's row first (`ReviewQueue.lock`), so what is indexed is what is decided. An edit or a
     merge at the same moment waits, then finds the item no longer open, and saves nothing.
4. **Refusals change nothing:** 400 for no field or an unusable value, 404 for no item, 409 `already-decided` for an
   item no longer open, 409 `not-applicable` for another kind.
5. **Audit:** `EDIT_CANDIDATE` with the item id and `EDITED <fields>`.
6. **Back office:** "Edit the candidate" in the details of an open resource candidate: title, type and tags (comma
   separated). Only changed fields are sent. The details then say "Changed by an admin: title, type."

## Consequences
- A post a category found can be accepted as the article or news item it is, under a clean title; its enrichment
  then names what it is about ([0062](../scout/0062-writing-about-a-resource.md)).
- An edited type holds for the candidate, not for the resource. Once in the catalog, the resource's type comes from
  all its records, the most specific first: a later record from another source can still change it. Fixing a
  resource's own fields is the next step (roadmap).

## Security and privacy (STRIDE)
- **Spoofing / elevation**: ADMIN only, admin host behind the VPN, CSRF ([0035](0035-admin-host.md)).
- **Tampering**: the values are checked here and again on accept; the details are rewritten as JSON by code, never
  taken as text from the client.
- **Repudiation**: every edit is in the append-only audit trail.
- **Information disclosure**: none new. The record's extra lists field names only, no administrator id. The Privacy
  page says the record keeps which fields a person changed, for as long as the resource is listed.
- **Denial of service**: one admin, one row per edit.

## Alternatives considered
- **Edit while accepting** — one call, but a mistake is in the catalog at once, and a collector's merge in between
  would be lost silently.
- **Edit the resource after accepting** — the right tool for a resource's own facts (next step), but it lets a wrong
  type reach the catalog and the enrichment first.
- **Lock the item while an admin edits** — long locks for a form left open; the compare-and-save keeps both writers
  safe without one. The only lock is the short one of an accept, for the length of its transaction.

## Code
- `backend/src/main/kotlin/ai/helper2/scout/usecase/EditCandidate.kt`; `IndexAcceptedCandidate.kt`
  (`CandidateRules`, `typeChosenByAdmin`).
- `backend/src/main/java/ai/helper2/scout/internal/ResourceCandidateJson.java` (`edit`, `merge`, `read`, `reason`),
  `ReviewQueueCandidateItems.java`, `ReviewQueueCandidateReview.java`, `ScoutConfiguration.java`.
- `backend/src/main/java/ai/helper2/catalog/ReviewQueue.java` (`updateCandidate` compares the details, `lock`);
  `backend/src/main/java/ai/helper2/admin/usecase/ResolveReview.java` (locks an accepted item first).
- `backend/src/main/java/ai/helper2/admin/web/ReviewController.java`; `api/openapi.yaml` (`CandidateEdit`,
  `CandidateType`, audit action).
- `backend/src/main/resources/db/migration/V21__candidate_edit.sql`.
- `frontend/src/pages/admin/CandidateEditForm.tsx`, `ReviewItemDetails.tsx`, `ReviewQueuePage.tsx`.
- Tests: `EditCandidateTest`, `ResourceCandidateJsonTest`, `IndexAcceptedCandidateTest`, `CandidateEditApiIT`,
  `CandidateEditForm.test.tsx`.
- Diagrams: [flow](../../diagrams/flow-0065-candidate-edit-in-review.svg) · [sequence](../../diagrams/sequence-0065-candidate-edit-in-review.svg).
