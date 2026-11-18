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
   page (at most 500 ids), or every open item of the two kinds; for every open item the count and the most model calls
   it can take (14 an item) are shown first (`GET /admin/review-research/estimate`, with how many open items are already researched). Every open item means every one
   whose newest run is not `DONE`, at most 20,000, chosen by the server. Research again on one item runs it again.
   There is no background job that researches the queue on its own. A run is a row in `review_research` with the status
   `QUEUED`, `RUNNING`, `DONE`, `FAILED` or `CANCELLED`. Workers on virtual threads take the oldest queued row with
   `FOR UPDATE SKIP LOCKED`, so two back office pods never run the same item, and a partial unique index allows one
   queued or running run per item. The number of workers per pod is `H2_RESEARCH_CONCURRENCY` (4); the daily cap of
   model calls is the runtime setting `research.dailyModelCalls` (`H2_RESEARCH_DAILY_MODEL_CALLS`, 3,000), and page
   reads have their own cap (`H2_RESEARCH_DAILY_PAGE_READS`, 300). A worker claims a run only while 14 model calls,
   one run's most, are left today; otherwise the runs stay `QUEUED` and start when the budget resets. The back office
   does not start with a daily cap of 1 to 13. While research is off (`H2_RESEARCH_ENABLED=false`), starting a run
   answers 503 and queues nothing. A run that the budget stops half way goes
   back to `QUEUED` without its steps. A spent page-read cap does not stop a run: `read_page` answers with an error
   and the model goes on. A running run with no step for 5 minutes lost its worker and fails (`FailStaleResearch`). The admin can cancel a queued or running run and start it again. The tables come with
   `V2026.11.6.3__review_research.sql`, after `V2026.11.6.2__shadow_library.sql`.
3. **Read-only tools, from the adapters we have.** The agent calls these tools and nothing else:
   `read_page` (the page reader, with its own daily cap), `web_search` (the live web search, on the Scout's live-web
   credits; its results are leads, not evidence: the model reads a page before it cites it),
   `find_package` (deps.dev: npm, PyPI and the other ecosystems), `github_repo` (the GitHub client), `huggingface`
   (the Hugging Face client), `catalog_lookup` (a resource listed at the URL or at its site's root, a known target by name) and `check_url` (status and
   final URL after redirects, https only, since the cluster reaches the web on port 443 only). Private, loopback,
   link-local and reserved addresses are refused, also when an IPv6 address carries one (IPv4-compatible, mapped,
   NAT64, 6to4; Teredo and 64:ff9b:1::/48 are refused whole), and so are the documentation ranges. `check_url` looks
   a host up once per hop, tries at most 2 of the addresses it checked, and stops the whole call, redirects included,
   after `H2_RESEARCH_CHECK_URL_DEADLINE` (20 seconds). A shadow library is never read
   (`ShadowLibraries`). `ResearchTool` is the research module's port: `catalog_lookup` lives in the module, the other
   six in the sources module (`ai.helper2.sources.research`), which implements the port, as for the AI check. The
   module runs SQL only on its own two tables; it reads the review queue and the catalog through the catalog's ports
   (`ReviewQueue`, `KnownTargets`).
4. **Our loop, the model's tool calls.** The agent sends the tool definitions through Spring AI; its chat model
   returns the tool calls and runs none of them. Our code (`RunResearch`) runs each tool, stores the step and sends the
   result back. So every step is stored before the next one, the limits are ours (at most 12 tool calls, 3 minutes,
   the daily cap of model calls, one more call when the final answer is not the JSON asked for), and the loop works
   with any OpenAI-compatible model. After the 12th tool call the model gets one more call with no tools and is told
   to answer with what it has read. The run keeps its evidence instead of failing, and the check still drops every
   value it cannot cite. On that call the model must answer in the JSON schema of the answer when the provider
   supports it (`H2_NEBIUS_NATIVE_SCHEMA`), so the answer can be read. A tool call past the 12th is not run: it is
   stored with the error "not run". So is the same call made a second time, and a call past a tool's own limit for
   the run (`web_search`: 4); both still count among the 12. A model that still asks for a tool fails the run. A model that cannot call tools fails the eval and is not used. The prompt is
   `prompts/research/research.txt`.
5. **Every step is stored and shown.** `review_research_step` holds, per step: the sequence, the kind (`MODEL_CALL`,
   `TOOL_CALL`, `CHECK`), the request, the response, the duration, the tokens and the error. A response is cut at
   200 KB. The admin sees the steps as a timeline, live while the run is going, and expands any request or response.
   Everything is shown as plain text, never as HTML.
6. **The status of an attribute is computed by code, not by the model.** Every tool response is evidence with an id.
   `RunResearch` cleans it with `UntrustedPageText.clean` (hidden text, tags, links, contacts and instruction lines
   dropped, as in enrichment ([0014](../scout/0014-evidence-gated-enrichment.md) §4)) and escapes it with
   `UntrustedText`, so no tool text can open or close a block. That cleaned text is both what the model sees and the
   evidence the check quotes from; a response with no text left is no evidence. The model returns,
   for each attribute, a value and its citations (evidence id and excerpt). Then the `CHECK` step runs the rules of
   `EvidenceValidator` (0014 §6), shared and not copied: the allowed values, the fields the type keeps, an excerpt of
   20–300 characters found verbatim in that evidence, and for licence, open source, pricing and type an excerpt that
   states the value itself. On top of it, per attribute:
   - `CONFIRMED`: an authoritative source supports the value, or two sources on different hosts (`www.` ignored) agree.
     For a licence or open source, the GitHub or Hugging Face record of the item itself is authoritative: its URL is
     the item's URL. A package record is not, nor a repository whose homepage names the item: their owner sets those
     links. For the URL, name, type, price, description, language and date, the item's own site read with `read_page`
     is authoritative. On a shared host (code forges, package registries, Hugging Face, blogs and social sites, listed
     in `CheckResearch`) a page is the item's own only under the same owner: the first path segments match. A path with dot segments or an encoded slash never matches;
   - `SINGLE_SOURCE`: one non-authoritative source; `CONFLICT`: supported values disagree, all are shown;
     `NOT_FOUND`: no supported value. A citation that fails the rules is dropped, and the step says why.
   The attributes are the resource's catalog fields: verdict, canonical URL, title, type, audience, tech level, pricing,
   open source, licence, description, tags, language, published date, duplicate of.
7. **Applying.** The detail pane of the review queue shows the verdict and one row per attribute: the value now, the
   proposed value, its status and its quotes. The admin confirms, edits or keeps each changed attribute (keys `c`, `e`
   and `u`: `k` stays "previous item"); a conflict needs a pick first. Accept is enabled when every changed attribute
   has a decision, and says how many are left. On Accept, every confirmed value goes into the candidate through
   `PUT /admin/review-items/{id}/candidate`, then the item is accepted through the existing accept path
   ([0038](0038-back-office-and-review.md) §19 to §20): the candidate takes the confirmed title, type and tags, it is
   indexed at the confirmed canonical URL, and the other values become the resource's curated values. A description
   the model wrote and the admin confirmed as it is stays AI-written (`AI_SUMMARY`); one the admin rewrote is
   `CURATED`. A course candidate holds the same structured candidate, so its confirmed values reach the catalog the
   same way ([0038](0038-back-office-and-review.md) §19). The queue's research filter is
   `GET /admin/review-items?research=KEEP|REJECT|DUPLICATE|ACTIVE|NONE`, answered by the server across pages. The pane is marked
   `data-ai-generated` with the model, and labelled "Proposed with AI" (EU AI Act Art. 50).
8. **Batch apply: rejects only.** The research bar offers to reject the items of the page whose verdict is reject. The
   list of items with their reasons is shown first, and the admin can untick any; nothing is rejected before the admin
   confirms it. Keep and duplicate are decided one item at a time. The research bar also starts research for the
   picked items, the page, or every open candidate after the count and the most model calls are shown. Each row
   shows its research (queued, running with its step, the verdict, failed with the reason, cancel, research again),
   read every 2 seconds while a run is active. The Research filter (done keep, done reject, not researched) reads the
   loaded page only.
9. **Live view over SSE.** `GET /admin/review-research/{runId}/events` streams the steps of one run and its status
   changes, and ends with the run or when the browser leaves. It reads new rows from Postgres once a second, so it
   works with any number of back office pods and needs no broker. A comment line every 15 seconds keeps a quiet
   stream open. A pod serves `H2_RESEARCH_MAX_STREAMS` (8) streams at a time and answers 429 beyond them. The page `/admin/review/research/{runId}` shows every step, request and response
   as plain text, filtered by kind or errors, and follows a running run on this stream. The queue rows read their
   status from `GET /admin/review-research?itemId=…`. A
   result carries `aiWritten: true`, the machine-readable AI marking.
10. **Measured before it is used.** Research runs on staging and locally (`H2_RESEARCH_ENABLED`, default true). Prod
    runs it once the #588 eval passes: until then the Helm chart sets `backoffice.researchEnabled: false` for prod. The
    eval runs the agent on 100 items the admin has already decided, once with `H2_NEBIUS_FAST_MODEL` and once with
    `H2_NEBIUS_REASONING_MODEL`. It reports verdict
    agreement, accuracy per attribute, the share of `CONFIRMED` values that are right, time per item and tokens per
    item. The admin chooses the model and the bar from these numbers. In production every research result is kept next
    to the admin's final decision, so the same numbers are measured on live use. The labels are the admin's decisions
    (`docs/eval/research-labels.tsv`, exported by `scripts/research-eval-labels.sql`) and items a person labels by
    hand (`research-labels-manual.tsv`). `ResearchEvalRecorder` (`evalRecord`) runs the real tools and model on them;
    the eval's catalog is empty, so a duplicate verdict is not measured. The report says when the set is under 100
    items or 20 rejects; then it decides nothing. How to run it: `docs/eval/README.md`.
11. **Retention.** Runs and their steps are deleted 90 days after the run ends (the daily job `review-research`), and
    with their review item. The steps hold public web pages and catalog data; no user data is sent to the model. The
    public app's database role cannot read the two tables.

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
- Cost: model calls, page reads and web search credits per item. When the daily model calls are spent, the runs stay
  `QUEUED` and start when the budget resets; the estimate is shown before a large start.
- Security: page text is untrusted. The tools only read, the model's output is a validated schema, the quote check
  drops what was not fetched, and the agent can never accept or reject an item. `check_url` refuses private addresses
  and connects only to the address it checked.
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
| Cost & reliability | Step and time limits, daily caps of model calls and page reads, retry by the admin (2, 4) |

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
