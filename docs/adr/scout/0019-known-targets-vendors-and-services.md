# 0019 — Known targets: vendors and services

**Depends on:** [0004](../foundation/0004-persistence-and-the-schema.md), [0011](0011-scout-runner.md), [0014](0014-evidence-gated-enrichment.md), [0015](0015-reference-projects.md), [0033](../guide/0033-marking-ai-written-text-research-trail.md), [0038](../backoffice/0038-back-office-and-review.md)

## Context
A relationship says what a resource works with. Its target is a catalog resource, or a vendor: a company with no
page of its own ([0014](0014-evidence-gated-enrichment.md) §6). As a list in code, the vendors would have two
problems.

- **A new vendor needs a code change.** The list reflects one day's view. A company the list does not hold is an
  unverified target and one more review item, each time a resource names it.
- **A service has no place.** Slack, Discord, GitHub or Docker are not AI resources and not AI vendors. They are
  called services here, not platforms: a platform is already where a resource runs (the `PLATFORM` label: python,
  desktop, cli). A service is what it connects to. The catalog
  will never hold them, so every relationship that names one waits in the review list for ever.

The names must be data, a person must be able to change them, and the system should find the new ones itself. But
a name the system finds is a guess: nothing becomes a known target without a person.

### Measurement
Staging, 11 Nov 2026, 35,147 resources, 587 relationships, 536 resources enriched.

- 434 relationships had no target (321 names). 249 of these names were in no catalog resource. Among them:
  Slack 5 relationships, Discord 4, Telegram 4, GitHub 3, Docker 2, Chrome 2, WhatsApp 2.
- **Owners name the vendors.** Resources whose title is `owner/name`, MCP servers left out, counted per owner:
  `deepseek-ai` 105, `nvidia` 66, `microsoft` 53, `openai` 34, `jinaai` 34, `facebook` 31, `google` 27,
  `mistralai` 21, `meta-llama` 20. The same count also returns `sebis` 30, `awinml` 19 and `tanstack` 14: a
  university chair, a person and a web framework's organisation. A count finds candidates; it does not decide.
- **MCP server names do not name the services.** The name after the slash, counted per publisher: `mcp` 324,
  `site` 290, `mcp server` 101, `slack` 1. Code cannot tell a service from a common word.
- Not measured: how often the model states the right kind of a target.

## Decision
1. **One table holds the known targets.** `known_target`: a name as shown ("Hugging Face"), a kind (`VENDOR` or
   `SERVICE`), where it came from (`origin`, `CURATED`: a person typed it, and the first 31; `DISCOVERED`: a rule
   proposed it and a person approved it), who added it and when. `known_target_name` holds every name that means
   it, the shown one included, as a key: lower case, spaces as one space, a final "'s" removed (`KnownTarget.key`,
   and the same rule in SQL, `KnownTarget.KEY_OF_TITLE_SQL`). A key belongs to one target. The catalog owns both
   tables; the first migration fills them with the 31 vendors and their other names. There is no list in code. The table says who added a target and when (`migration` for the first 31).
2. **A service is a target like a vendor.** `relationship.target_kind` allows `SERVICE`. A relationship that names
   a known service is `VERIFIED` with no target resource, keeps the service's one name as its title and opens no
   review item. It says "works with service X". Every rule of a vendor target applies
   ([0014](0014-evidence-gated-enrichment.md) §6): one row per resource, kind and name; the caps; a name only the
   product's own page states stays `UNVERIFIED`. A known target is looked up before a catalog resource of the same
   name.
3. **The validator stays pure.** `CatalogEnrichmentStore.load` reads the known names once per resource into the
   input, as it reads the known resources. `EvidenceValidator` asks the input, never the database.
4. **A person edits the table on the Settings page.** `/admin/settings` gets "Known targets": the list with kind,
   names, who added each and how many relationships carry it; add, rename, change the other names, remove.
   `GET` and `POST /api/v1/admin/known-targets`, `PUT /api/v1/admin/known-targets/{id}` (the body sets the kind
   and every name) and `POST /api/v1/admin/known-targets/{id}/remove`, ADMIN only (`ManageKnownTargets`). A name is 2
   to 60 characters on one plain line, with at most 20 other names; a key another target holds is a 409, and so is
   a full list (1,000). Each call is one audit entry (`ADD_KNOWN_TARGET`, `EDIT_KNOWN_TARGET`,
   `REMOVE_KNOWN_TARGET`) with its outcome and, for a change or a removal, how many relationships it released.
   - **A change is a removal for the names it drops.** The relationships that carried a dropped name are released
     as below; the ones under a kept name take the new kind.
   - **Adding or approving a name confirms what waits.** The step at the end of a Scout run that confirms the open
     candidates naming a known vendor ([0014](0014-evidence-gated-enrichment.md), the evidence ladder) reads the
     table and handles both kinds: `VERIFIED`, `target_kind`, item `SUPERSEDED`.
   - **Removing a name takes the confirmation back, and sends its relationships to review.** In the same
     transaction, the relationships that carry it lose `target_kind`, become `UNVERIFIED`, and each gets an open
     `RELATIONSHIP_CANDIDATE` with the reason "Its target is no longer a known vendor" (or service). So the rule
     of [0014](0014-evidence-gated-enrichment.md) §9 still holds: an unverified relationship has an item a person
     can decide. A Scout run would not decide them again: the resource's text did not change, so its run is
     `UNCHANGED` and makes no model call ([0014](0014-evidence-gated-enrichment.md) §2).
   - **Adding the name again confirms them again**: they are open candidates, which the confirming step reads.
   - **A change and an enrichment's save do not cross.** An enrichment reads the known names before the model call
     and saves its answer later, after a change may have committed. So an add, change or removal first takes a
     transaction lock on the known targets (`pg_advisory_xact_lock`, `KnownTargetLock`). The save of an answer, and
     the confirming step, take the same lock shared, then read the names again. Each takes the lock before it
     touches a relationship, so a change and a save never wait on each other's rows. A name
     removed or dropped in between: the relationship is saved `UNVERIFIED`, without `target_kind`, with an open
     `RELATIONSHIP_CANDIDATE` and the reason of a removal. A kind changed in between: the relationship takes the new
     kind, like the ones a change keeps. No relationship stays `VERIFIED` under a name that is gone, so the
     `UNCHANGED` run has nothing stale to keep.
   - **The page asks first.** Before a removal the Settings page says how many relationships carry the name. The
     items it opens can be decided in bulk ([0038](../backoffice/0038-back-office-and-review.md) §9).
5. **The owner rule proposes vendors.** At the end of a Scout run, after the linking step, one SQL statement counts
   the resources per owner (`JdbcKnownTargetCandidates.proposeOwners`): `INDEXED` or `CURATED` resources with a
   record of a GitHub or Hugging Face source (`ScoutConfiguration.OWNER_SOURCES`) whose title is `owner/name`. The
   record's source decides, not its address: an MCP Registry record often has a GitHub address. Owners that differ
   only in case are one owner, shown as most of its records write it. An owner with
   `H2_SCOUT_KNOWN_TARGETS_OWNER_MIN_RESOURCES` (5) resources or more, whose name is no known target and was never
   proposed, becomes a proposal of kind `VENDOR`. No model call. MCP Registry namespaces and npm scopes are not
   owners: anyone can register one. A failure of this step is logged and does not fail the run.
6. **The model states the kind of a target, and agreement proposes it.**
   - The enrichment prompt (label `enrich-v10`) asks for one more value per relationship, `targetKind`, decided
     from the target's name alone: `VENDOR` when the name is only a company's or an organisation's name
     ("OpenAI"), also when the text means its API or its models; `SERVICE` when it names a general service or
     tool that is not an AI resource itself (Slack, GitHub, Docker); `PRODUCT` for everything else, a named
     product of a company included ("OpenAI API"). A missing or unknown value is `PRODUCT`. The prompt
     does not say "when unsure, PRODUCT": measured on staging (12 Nov 2026, 81 resources and 104 relationships),
     that wording stated `VENDOR` for none, and `PRODUCT` for OpenAI (twice), Anthropic, Hugging Face and Docker (twice); 6 of
     its 7 `SERVICE` were right.
   - The kind is a judgement, like audience ([0014](0014-evidence-gated-enrichment.md) §7): it needs no quote, it is
     stored on the relationship as `stated_target_kind`, it never makes a relationship `VERIFIED` and it is not shown
     on the site.
   - At the end of a Scout run, one SQL statement looks for agreement: a name that relationships of at least
     `H2_SCOUT_KNOWN_TARGETS_MIN_AGREEING` (3) resources of 3 different owners state as `VENDOR`, or as `SERVICE`,
     and more often than as any other kind. The name is no known target, names no catalog resource by the name rule
     and was never proposed. It becomes a proposal of that kind.
   - The fingerprint does not change ([0014](0014-evidence-gated-enrichment.md) §2): a new prompt alone sends
     nothing to the model. A resource enriched before keeps its relationships with no stated kind until its text
     changes. Almost all of the catalog is still to enrich, so no resource is enriched again for this.
7. **A proposal is a review item, never a known target.** Kind `KNOWN_TARGET_CANDIDATE`, with no resource. Its
   details are JSON: the name's key, the name, the kind, the rule that found it (`OWNER` or `AGREEMENT`), the count,
   the other names and up to 5 resources as examples, the most popular first. One item per key
   (`review_item_known_target_idx`).
   - **The cap.** At most `H2_SCOUT_KNOWN_TARGETS_PER_DAY` (25) new ones a UTC day, the owners of the most resources
     first; 0 turns the proposals off. The rest wait for a later run. Counters `helper2.scout.targets.proposed` and
     `helper2.scout.targets.held`.
   - **Accept adds the target** with origin `DISCOVERED` and the admin as the one who added it, in the transaction
     of the decision (`ResolveReview`, `ManageKnownTargets.addDiscovered`), with the audit entry of an add. A name
     another target holds by then is a 409 and the item stays open. A proposal is accepted one at a time, never in
     bulk ([0038](../backoffice/0038-back-office-and-review.md) §9); it can be rejected in bulk.
   - **The admin may change the name, the kind and the other names before accepting** (`meta-llama` becomes
     "Meta", with `meta-llama` as another name): `PUT /api/v1/admin/review-items/{id}/known-target`, ADMIN only
     (`EditKnownTargetCandidate`), the same checks as a name typed on the Settings page, one audit entry
     (`EDIT_CANDIDATE`). The item keeps the key it was proposed under, so the decision is remembered for the
     proposed name whatever the admin wrote.
   - **A name an existing target has extends that target.** When the accepted name is one a known target of the
     same kind already holds, the proposal's other names are added to that target, which keeps its name, its
     origin and who added it; the audit entry is an edit. A target of another kind, or another name held
     elsewhere, is a 409 and the item stays open.
   - **A decision is remembered** by the name's key (`candidate_decision`, a one-way key), accepted or rejected:
     the name is not proposed again, also after its item is deleted. A person's account is rejected: a person is
     not a vendor.
8. **What the site shows.** A relationship to a known target is shown as today's vendor relationship. Its kind of
   relationship is still the model's, with its quote and its AI label
   ([0033](../guide/0033-marking-ai-written-text-research-trail.md)). That its target is a vendor or a service is a
   fact a person put in the table. The public API does not expose `target_kind` (Known limits).

### Code
- `backend/libs/schema/src/main/resources/db/migration/V1__schema.sql`
  (`known_target`, `known_target_name`, the 31 vendors, `relationship.target_kind`)
- `backend/libs/catalog/src/main/java/ai/helper2/catalog/KnownTarget.java` (`key`, `KEY_OF_TITLE_SQL`),
  `KnownTargets.java`, `catalog/internal/JdbcKnownTargets.java` (`named`)
- `catalog/internal/JdbcRelationshipTargets.java` (`confirmOpenKnownTargetCandidates`),
  `catalog/usecase/LinkKnownRelationshipTargets.java`, `catalog/internal/JdbcEnrichmentResults.java`
- `backend/apps/backoffice/src/main/kotlin/ai/helper2/scout/EvidenceValidator.kt` (`relationship`),
  `scout/Enrichment.kt` (`EnrichmentInput.knownTargets`), `scout/usecase/EnrichResource.kt` (`run`),
  `backend/apps/backoffice/src/main/java/ai/helper2/scout/internal/CatalogEnrichmentStore.java` (`knownTargets`)

- `V1__schema.sql` (`known_target.added_by`, `added_at`, `known_target_name.name`, three audit
  actions)
- `backend/libs/catalog/src/main/java/ai/helper2/catalog/KnownTargetList.java`,
  `catalog/internal/JdbcKnownTargetList.java` (`release`, `setKind`), `catalog/usecase/ManageKnownTargets.java`
- `catalog/internal/KnownTargetLock.java`, `catalog/internal/JdbcEnrichmentResults.java` (`knownNow`, `asKnownNow`),
  `catalog/internal/JdbcRelationshipTargets.java` (`confirmOpenKnownTargetCandidates` takes the lock)
- `backend/apps/backoffice/src/main/java/ai/helper2/admin/web/KnownTargetsController.java`, `api/admin.yaml`

- `frontend/src/admin/KnownTargetsPanel.tsx`, `SettingsPage.tsx`, `frontend/src/api/admin.ts`,
  `frontend/src/i18n/locales/en.admin.json` (`admin.settings.targets`)

- `V1__schema.sql` (`known_target.origin`, `KNOWN_TARGET_CANDIDATE` in `review_item.kind` and
  `candidate_decision.kind`, `review_item_known_target_idx`)
- `backend/libs/catalog/src/main/java/ai/helper2/catalog/KnownTargetCandidates.java`,
  `catalog/internal/JdbcKnownTargetCandidates.java` (`proposeOwners`, `of`), `catalog/usecase/ProposeKnownTargets.java`,
  `catalog/usecase/ManageKnownTargets.java` (`addDiscovered`), `catalog/internal/JdbcReviewDecisions.java`
- `backend/apps/backoffice/src/main/kotlin/ai/helper2/scout/usecase/RunScout.kt` (`proposeKnownTargetsQuietly`),
  `backend/apps/backoffice/src/main/java/ai/helper2/scout/internal/ScoutConfiguration.java` (`OWNER_SOURCES`),
  `scout/config/ScoutProperties.java` (`KnownTargets`), `admin/usecase/ResolveReview.java` (`addKnownTarget`)
- `frontend/src/admin/ReviewItemDetails.tsx` (`KnownTargetProposal`), `KnownTargetsPanel.tsx` ("proposed by a rule")

- `catalog/usecase/EditKnownTargetCandidate.java`, `catalog/internal/JdbcKnownTargetCandidates.java` (`change`),
  `catalog/usecase/ManageKnownTargets.java` (`addDiscovered` extends a target), `admin/web/ReviewController.java`
  (`editKnownTargetCandidate`), `api/admin.yaml`
- `frontend/src/admin/KnownTargetProposalForm.tsx`; the names of a target are typed one per line
  (`KnownTargetsPanel.namesOf`), so a name with a comma stays whole

- `V1__schema.sql` (`relationship.stated_target_kind`)
- `backend/apps/backoffice/src/main/resources/prompts/scout/enrich.txt`,
  `scout/internal/SpringAiEnrichmentModel.java` (`Rel.targetKind`, `PROMPT_VERSION`),
  `scout/EvidenceValidator.kt` (`statedKindOf`), `catalog/internal/JdbcEnrichmentResults.java`

- `backend/apps/backoffice/src/main/kotlin/ai/helper2/scout/usecase/RunScout.kt` (the agreement step)

## Alternatives considered
- **The list stays in code.** No table, no page. Every new name is a pull request, and services still have no place.
- **Services as a fixed list, vendors from the owner rule.** Smaller. The system never finds a service.
- **The rules add names without a person.** No review work. One group of repositories could put any name on many
  pages.
- **Wikidata tells the kind of an unknown name.** Facts with a source and no model. A new recipient for every
  unknown name, and names like "Cursor" or "Linear" match many entries.
- **A vendor or service as a resource.** One model for every target, with a page. The catalog would hold things
  that are not AI resources, and search would return them.

## Consequences
- A new vendor or service is a row, not a release.
- The review list gets a new kind of item, and loses the relationship candidates that name a known service.
- The owner rule will propose organisations that are not vendors (a university chair, a framework). Rejecting is one
  click, once per name.
- The agreement rule finds nothing until enough resources are enriched with the new prompt.
- One more value in the model's answer: a few output tokens per relationship.
- Removing a name un-verifies its relationships at once and opens one review item for each: a name many resources
  carry fills the review list. The pages that showed them lose the target's mark with the next read.
- Two more reads per enrichment (the known names) and two more statements per Scout run.
- The save of an answer takes a shared lock, and reads the names a second time when it names a known target. A change of
  the table waits for the saves that hold it, and they wait for the change: each is one short transaction.

### Known limits
- The public API does not expose `relationship.target_kind`: no search filter "works with" and no page per vendor or service reads it.

### STRIDE
| Threat | Answer |
|---|---|
| Spoofing | The admin API is ADMIN only, on the back office host behind the VPN ([0036](../identity/0036-identity-and-the-admin-host.md)). |
| Tampering | Several repositories can be made to name a spam "service", and a README can tell the model which kind to state. The rules only propose: three resources of three different owners, a daily cap, and a person approves. Names are length-checked, stored as text and shown escaped. |
| Repudiation | Every add, edit, remove and decision is an audit entry with the admin's id. |
| Information disclosure | A proposal holds a name, a count and resource ids. An owner name can be a person's public account name; it is already the first part of a public resource title, and no profile is fetched. |
| Denial of service | Two indexed statements per run, a daily cap on proposals, a rejected name is never proposed again. |
| Elevation of privilege | No new role. The Scout writes proposals; only an admin writes the table. |

### AI architecture check
| # | Layer | State |
|---|---|---|
| 1 | Model | Covered: the reasoning model of the enrichment, one more value in its answer. |
| 2 | Agent Framework | Deferred, safe: no agent. |
| 3 | Orchestration | Covered: two steps at the end of the Scout run, after the linking step. |
| 4 | Tool & API | Covered: four admin endpoints; no new outbound call. |
| 5 | Memory | Covered: rejected names are remembered (`candidate_decision`). Nothing about users. |
| 6 | Knowledge & RAG | Covered: the catalog's relationships gain verified targets. |
| 7 | Data | Covered: two tables, one column, one more value in two checks; seed in the first migration. |
| 8 | Browser & Computer Use | Deferred, safe: no page is fetched. |
| 9 | Guardrails | Covered: the stated kind never verifies; agreement of different owners; a person approves; name checks. |
| 10 | Observability | Covered: `helper2.scout.targets.proposed` and `helper2.scout.targets.held`, one log line per run that proposes. |
| 11 | Evaluation | Gap: how often the stated kind is right is not measured. Measure on the first 200 enriched resources before trusting the agreement rule's numbers. |
| 12 | Deployment | Covered: three settings with defaults, no chart change. Rollback: per-day 0 stops the proposals. |
| 13 | Security & Identity | Covered: ADMIN only, audit, no new secret. |
| 14 | Human-in-the-Loop | Covered: nothing reaches the table without a person. |
| 15 | Cost & Reliability | Covered: no new model call; a few output tokens per relationship. |

### Points for a lawyer
- An owner name the rule proposes can be a natural person's account name. It is public and already shown in the
  resource's title; the proposal adds a count. Confirm that this needs no entry of its own in the record of
  processing.
- Naming a company as a "vendor" or a service as a "service" a resource works with is a statement about that
  company's product. It rests on the resource's own text, with its quote. No new point compared with today's vendor
  relationships.

## Diagrams
[flow](../../diagrams/flow-0019-known-targets.svg) · [sequence](../../diagrams/sequence-0019-known-targets.svg)

## Backlog
- Decides: [F2.9](https://github.com/silvioincalza/helper2.ai/issues/39).
- Open issues that change it: none.
