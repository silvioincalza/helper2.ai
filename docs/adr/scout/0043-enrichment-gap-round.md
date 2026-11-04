# 0043 — Enrichment gap round: read the README or model card once when a run leaves fields unproven

**Status.** Accepted. **Builds on:** [0018](0018-scout-runner.md), [0019](0019-evidence-gated-enrichment.md), [0020](0020-scout-collectors.md), [0034](../operations/0034-data-retention.md)

## Context
Enrichment ([0019](0019-evidence-gated-enrichment.md)) sees only what the sources store: a title and a short
description. Licence, platforms and relationships are rarely in there, so many runs end `PARTIAL` or `REJECTED`, and
the review queue fills with fields nobody can prove. The resource's own README or model card usually states them.
The idea comes from iterative research tools: look at what is still missing, then fetch more. Here code, not the model,
decides where the extra text comes from, and the same evidence gate judges it.

## Decision
1. **When.** Inside the same `EnrichResource` attempt, after the first run is validated. Only when the outcome is
   `PARTIAL` or `REJECTED` and the resource names its own repository or model. One gap round per attempt, never a loop.
2. **Where the text comes from** (`GapTargets.select`, pure code in `scout`):
   - a `github` or `github-awesome` record: that repository's README;
   - an `hf-models`, `hf-trending` or `hf-provider-orgs` record: that model's card;
   - an `mcp-registry` record: its repository's README, only when the entry's namespace is `io.github.<owner>` and
     `<owner>` owns the repository. Anyone can publish an entry that links any repository; without this check a famous
     README would prove facts about someone else's server.
   - Links must match `https://github.com/<owner>/<repo>` or `https://huggingface.co/<owner>/<name>` exactly, so no
     link can reach another API path with our token.
3. **Reading** (port `ExtraEvidence`, adapter `sources/evidence/ReadmeEvidence`):
   - GitHub: `GET /repos/{owner}/{repo}/readme` (raw) and `GET /repos/{owner}/{repo}` for the licence GitHub states.
   - Hugging Face: `GET /{owner}/{name}/raw/main/README.md`; the licence is the `license:` line of the card's front matter.
   - Owner and name are URI template variables. At most 512 KB. Policy `collector-gap-evidence` (circuit breaker and
     retry, like a collector). A missing README is no text; any other failure leaves the first run as it is.
4. **Cleaning** (`ReadmeText`, before the model or the validator sees it): the first 64 KB only; zero-width, direction
   and tag characters removed first; Unicode NFKC; HTML comments, scripts, styles, tags, images, inline and reference
   link targets removed; email addresses replaced by `[email]`. Every pattern is bounded, so a hostile README cannot
   make cleaning slow. A
   window of 4000 characters keeps the top, then the licence, install and platform sections, then the rest.
5. **Second run.** The model gets the source records plus the README as one more `Material` with its own evidence id.
   The licence the GitHub API or the card states is a source fact, so a README that says something else is
   `CONFLICTING`. README text may support fields and relationships, never summary claims: a claim is the model's own
   sentence, and it could cite any true excerpt.
6. **Which run is kept.** The one with more accepted items (verified fields, relationships not rejected, claims). A gap
   run that keeps no more is dropped. One save per attempt.
7. **Fingerprint.** The saved run carries the fingerprint of the source records alone. The README is not stored, so the
   next run with unchanged records is `UNCHANGED`: the README is read once per fingerprint, and fields do not flip back
   and forth. The run stores which text it used in `enrichment_run.extra_evidence` (`github-readme:owner/repo`).
8. **Evidence.** A README excerpt is an `evidence` row with a link a person can open (`github.com/owner/repo#readme`,
   `huggingface.co/owner/model/blob/main/README.md`) and the read time, anchored to the record it
   was read for, so it goes when that record goes ([0034](../operations/0034-data-retention.md)). The README text
   itself is not stored: only the 20–300-character excerpts the validator accepted. The detail page names the source
   of such a quote: `owner/repo · README`, `owner/model · model card`.
9. **Cost.** `H2_SCOUT_GAP_DAILY_CALLS` (400) gap model calls a day, a share of the 500 enrichment calls
   (`H2_SCOUT_DAILY_ENRICHMENT_CALLS`, raised from 300 with this decision), not on top (both caps count them). At
   least 100 calls a day stay for first runs of new resources. A gap cap above the enrichment cap has no effect. Taken only when there is text to send; when none is left today, or with `0` (off), no README is read either. The per-run enrichment
   budget ([0018](0018-scout-runner.md) §6) counts resources, not gap calls: a gap call is part of its resource's
   attempt, so a run can make up to its budget plus the gap calls left today. Reading a README needs `H2_GITHUB_TOKEN`
   (without it GitHub allows 60 calls an hour); a model card needs no token. Gauge
   `helper2.scout.gap.budget.remaining`; counter `helper2.scout.gap` (source, outcome: `filled`, `not_better`,
   `no_text`, `source_failed`, `model_failed`, `skipped_cap`).

## Consequences
- Fewer unproven fields and fewer review items, at most 400 extra model calls a day, inside the 500.
- A resource is read again only when its source records change, or the prompt or model changes.
- The demo profile has no adapter, so it has no gap round.
- A README that fails to load (timeout, 5xx) is not tried again until the source records change: the run is saved
  under the same fingerprint. A redirect to another host is refused and counts as a failure.
- A gap slot is spent when the second model call fails.
- Resources enriched before this change get a gap round only when their fingerprint changes (a new prompt version
  re-enriches the catalog). See the roadmap.

## Alternatives considered
- Let the model write new search queries for the collectors — the collectors read feeds and cursors, they take no
  query; and the model would choose what to fetch.
- Store the README — copies third-party text we do not need; the excerpts are enough.
- Fold a README hash into the fingerprint — needs a GitHub call on every run just to compute it.
- Run the gap round as a separate backlog step — two saves per resource, and a worse run could replace a better one.
- Trust any MCP Registry link — the entry's author chooses it.

## Code
- `backend/src/main/kotlin/ai/helper2/scout/GapRound.kt` — `GapTargets`, `ReadmeText`, ports `ExtraEvidence`, `GapBudget`.
- `backend/src/main/kotlin/ai/helper2/scout/usecase/EnrichResource.kt` — the gap round.
- `backend/src/main/kotlin/ai/helper2/scout/Enrichment.kt` — `Material.anchorRecordId`, `supportsClaims`; claims may not cite README text.
- `backend/src/main/java/ai/helper2/sources/evidence/ReadmeEvidence.java`; bean in `sources/config/SourcesConfiguration.java`.
- `backend/src/main/java/ai/helper2/scout/internal/JdbcEnrichmentStore.java` (gap target), `ScoutConfiguration.java` (budget).
- `backend/src/main/java/ai/helper2/catalog/internal/JdbcEnrichmentResults.java` — evidence with its own URL; `V2__enrichment_extra_evidence.sql`.
- `frontend/src/lib/evidenceSource.ts`, `frontend/src/components/EvidenceList.tsx`.
- Tests: `GapRoundTest`, `EnrichResourceGapRoundTest`, `ReadmeEvidenceTest`, `JdbcEnrichmentStoreIT`, `evidenceSource.test.ts`.

## Diagrams
[flow](../../diagrams/flow-0043-enrichment-gap-round.svg) · [sequence](../../diagrams/sequence-0043-enrichment-gap-round.svg)

## Roadmap
- [Gap round for resources enriched before it](../../roadmap.md#4-ai-quality-and-cost)
- [Lawyer review of README and model-card quotes](../../roadmap.md#product-and-legal)
