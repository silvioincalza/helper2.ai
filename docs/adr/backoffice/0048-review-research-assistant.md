# 0048 — Review research assistant

**Depends on:** [0011](../scout/0011-scout-runner.md), [0014](../scout/0014-evidence-gated-enrichment.md),
[0038](0038-back-office-and-review.md), [0043](../operations/0043-runtime-topology-helm-chart-three-deployables.md),
[0046](../operations/0046-evaluation-guide-and-search.md).

## Context

The review queue holds about 11,000 open items on staging, most of them resource candidates found by the Scout's
discovery. To decide one, the admin opens the page, checks that it is a product and not an article or a list, looks for
the official site, the repository, the package, the licence and the price, and checks that the catalog does not list it
already. This takes minutes per item, and the candidate text alone is often wrong: a title with the vendor's tagline,
the wrong type, no description.

A model can do the searching, but its answer cannot be trusted as it is: a model can invent a licence or a quote, and the
pages it reads are untrusted text that can carry instructions. The admin must see what the model asked and what each
tool answered, and must confirm every value before it reaches the catalog.

## Decision

1. **Research, then confirm.** For a RESOURCE_CANDIDATE or a COURSE_CANDIDATE the back office runs a research agent.
   The agent proposes a verdict (keep, reject with a reason, duplicate of a listed resource) and a value for each
   attribute. Nothing changes until the admin confirms, edits or keeps each changed attribute and then accepts the item.
   Other kinds are out of scope until this one has paid for itself.
2. **The admin starts it, the runs are async.** The admin starts research for one item, a selection, the current filter
   page, or every open item of the two kinds; for more than one page the count and the estimated tokens are shown first.
   There is no background job that researches the queue on its own. A run is a row in `review_research` with the status
   `QUEUED`, `RUNNING`, `DONE`, `FAILED` or `CANCELLED`. Workers on virtual threads take the oldest queued row with
   `FOR UPDATE SKIP LOCKED`, so two back office pods never run the same item. The number of workers is the runtime
   setting `review-research.concurrency` (start: 4). The admin can cancel a queued or running run and start it again.
3. **Read-only tools, from the adapters we have.** The agent calls these tools and nothing else:
   `read_page` (the page reader, its daily credits and its Redis cache), `web_search` (the live web search),
   `find_package` (deps.dev: npm, PyPI and the other ecosystems), `github_repo` (the GitHub client), `huggingface`
   (the Hugging Face client), `catalog_lookup` (canonical URL, host, name, known targets) and `check_url` (status and
   final URL after redirects; private, loopback and link-local addresses are refused). A shadow library is never read
   (`ShadowLibraries`). The back office reaches the source connectors only through ports of the new module, as for the
   AI check.
4. **Our loop, the model's tool calls.** The agent uses Spring AI tool calling with internal tool execution turned off:
   our code runs each tool, stores the step and sends the result back. So every step is stored before the next one, the
   limits are ours (at most 12 tool calls, 3 minutes, the daily token budget), and the loop works with any
   OpenAI-compatible model. A model that cannot call tools fails the eval and is not used.
5. **Every step is stored and shown.** `review_research_step` holds, per step: the sequence, the kind (`MODEL_CALL`,
   `TOOL_CALL`, `CHECK`), the request, the response, the duration, the tokens and the error. A response is cut at
   200 KB. The admin sees the steps as a timeline, live while the run is going, and expands any request or response.
   Everything is shown as plain text, never as HTML.
6. **The status of an attribute is computed by code, not by the model.** Every tool response is evidence with an id,
   and reaches the model inside `UntrustedText` tags, as in enrichment ([0014](../scout/0014-evidence-gated-enrichment.md) §4). The model returns,
   for each attribute, a value and its citations (evidence id and excerpt). Then the `CHECK` step runs the rules of
   `EvidenceValidator` (0014 §6), shared and not copied: the allowed values, the fields the type keeps, an excerpt of
   20–300 characters found verbatim in that evidence, and for licence, open source, pricing and type an excerpt that
   states the value itself. On top of it, per attribute:
   - `CONFIRMED`: an authoritative source supports the value (the registry or GitHub for a licence or a repository, the
     product's own site for its name, price and description), or two sources on different registrable domains agree;
   - `SINGLE_SOURCE`: one non-authoritative source; `CONFLICT`: supported values disagree, all are shown;
     `NOT_FOUND`: no supported value. A citation that fails the rules is dropped, and the step says why.
   The attributes are the resource's catalog fields: verdict, canonical URL, title, type, audience, tech level, pricing,
   open source, licence, description, tags, language, published date, duplicate of.
7. **Applying.** The research result fills the candidate edit form. The admin confirms, edits or keeps each changed
   attribute; Accept is enabled when every changed attribute has a decision. The confirmed values go into the candidate
   (`ResourceCandidateJson`, extended with the new fields) and reach the catalog through the existing accept path. A
   description written by the model keeps `description_origin = AI_SUMMARY`, so the public site labels it as AI-written
   (EU AI Act Art. 50).
8. **Batch apply: rejects only.** On a selection, the admin can reject the items whose verdict is reject. The list of
   items with their reasons is shown first; nothing is rejected before the admin confirms it. Keep and duplicate are
   decided one item at a time.
9. **Live view over SSE.** `GET /admin/review-research/events` streams the status of the runs on the admin's page, and
   `GET /admin/review-research/{itemId}/events` streams the steps of one run. The endpoint reads new rows from Postgres
   once a second, so it works with any number of back office pods and needs no broker.
10. **Measured before it is used.** Before the UI ships, an offline eval runs the agent on 100 items the admin has
    already decided, once with `H2_NEBIUS_FAST_MODEL` and once with `H2_NEBIUS_REASONING_MODEL`. It reports verdict
    agreement, accuracy per attribute, the share of `CONFIRMED` values that are right, time per item and tokens per
    item. The admin chooses the model and the bar from these numbers. In production every research result is kept next
    to the admin's final decision, so the same numbers are measured on live use.
11. **Retention.** Runs and steps are deleted 90 days after the item is decided, or 90 days after the run when the item
    is still open. The steps hold public web pages and catalog data; no user data is sent to the model.

## Alternatives considered

- **One model call per item, no tools.** Cheap and fast, but the model would guess the licence, the repository and the
  duplicates from the candidate text alone. Rejected: the point is to check facts.
- **A background job that researches the whole queue under a daily budget.** Instant for the admin, but it pays for
  items nobody opens and its results go stale. Rejected: research runs only for the items the admin starts it for.
- **Spring AI tool calling with internal execution.** Less code, but the loop runs inside the client: the steps are not
  stored one by one, and the limits are harder to hold.
- **An agent framework (LangGraph4j, Embabel).** More features than one loop with seven read-only tools needs, and one
  more dependency to follow. Revisit if the agent grows several roles.
- **A queue in Redis or a broker.** Redis is disposable in this stack ([0043](../operations/0043-runtime-topology-helm-chart-three-deployables.md)):
  a run must survive a restart. Postgres already holds the review queue.
- **The model sets the status.** Rejected: a model can say "confirmed" about a quote it invented.
- **A second evidence check for research.** Rejected: two rule sets would drift. Research uses `EvidenceValidator`.

## Consequences

- The admin sees the evidence for every value and decides faster; the catalog gets checked licences, repositories and
  descriptions.
- New tables `review_research` and `review_research_step`; a new back office module with ports to the sources; the first
  SSE endpoint of the back office.
- Cost: model tokens and page-reader credits per item. The daily budget stops new runs when it is spent; the estimate is
  shown before a large start.
- Security: page text is untrusted. The tools only read, the model's output is a validated schema, the quote check
  drops what was not fetched, and the agent can never accept or reject an item. `check_url` refuses private addresses.
- Privacy: a new processing purpose (research on public pages with a model provider) and a 90-day retention. The
  Privacy page and the record of processing change in the same pull request as the code.
- AI Act: a description written by the model is labelled on the public site (`AI_SUMMARY`) and in the API.

### AI architecture check

| Layer | Covered by |
|---|---|
| Model | Nebius, fast and reasoning model compared by the eval (10) |
| Agent framework | Our loop over Spring AI tool calls (4) |
| Orchestration | Postgres job queue, workers, cancel (2) |
| Tool & API | Seven read-only tools from existing adapters (3) |
| Memory | None across runs: each run starts from the item. Safe to defer |
| Knowledge & RAG | `catalog_lookup` for duplicates; no vector search. Safe to defer |
| Data | `review_research`, `review_research_step`, retention (5, 11) |
| Browser & computer use | None: `read_page` returns text. Safe to defer |
| Guardrails | Read-only tools, schema output, quote check, shadow libraries, private addresses (3, 6) |
| Observability | Every step stored and shown; tokens and time per step (5) |
| Evaluation | 100-item offline eval; live result vs decision (10) |
| Deployment | Back office only, behind the VPN; concurrency setting (2) |
| Security & identity | Admin only; GitHub sign-in; no write tool (3) |
| Human-in-the-loop | Per-attribute confirmation; batch only for rejects, after the list (7, 8) |
| Cost & reliability | Step and time limits, daily token budget, page-reader credits, retry by the admin (2, 4) |

## Diagrams

- Flowchart: [flow-0048-review-research.svg](../../diagrams/flow-0048-review-research.svg)
- Sequence: [sequence-0048-review-research.svg](../../diagrams/sequence-0048-review-research.svg)
- Software architecture: [architecture-review-research.svg](../../diagrams/architecture-review-research.svg)

## Backlog

- Features: [F6.2](https://github.com/silvioincalza/helper2.ai/issues/82) Review queue and actions (bulk, evidence, fix
  before accept). Decides [#587](https://github.com/silvioincalza/helper2.ai/issues/587) S6.2.15 (this ADR).
- Open issues that change this decision:
  - [#588](https://github.com/silvioincalza/helper2.ai/issues/588) S6.2.16 The research agent measured on 100 decided
    items, fast and reasoning model
  - [#589](https://github.com/silvioincalza/helper2.ai/issues/589) S6.2.17 The research assistant runs in the backend,
    stored and streamed step by step
  - [#590](https://github.com/silvioincalza/helper2.ai/issues/590) S6.2.18 The back office shows research per candidate
    and applies it attribute by attribute
