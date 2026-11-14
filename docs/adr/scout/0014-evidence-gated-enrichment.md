# 0014 — Evidence-gated enrichment

**Depends on:** [0004](../foundation/0004-persistence-and-the-schema.md), [0006](../foundation/0006-outbound-calls-and-resilience.md), [0008](../foundation/0008-llm-integration.md), [0010](../foundation/0010-data-retention.md), [0011](0011-scout-runner.md), [0012](0012-scout-collectors-and-events.md), [0013](0013-discovery.md), [0027](../search/0027-live-web-store-first-daily-quota.md), [0033](../guide/0033-marking-ai-written-text-research-trail.md)

## Context
Sources give a title, a description and a few facts. Search, filters and the resource page work better with more:
audience, technical level, pricing, use cases, platforms, licence, relationships to other tools, a short summary. A
model can extract them from the source text, but it can also invent them, copy instructions planted in the text, or
contradict what the source states. Every published value must point to the text it came from, and a person must decide
the unclear cases.

The goal is every field filled, with no hallucinations. But the sources store little: a title and a short excerpt.
Licence, platforms and relationships are rarely in there; a product's price is on its pricing page. The resource's own
README or model card, and the product's own pages, usually state them. The idea comes from iterative research tools:
look at what is still missing, then fetch more. Here code, not the model, decides where the extra text comes from, and
the same evidence gate judges it.

Facts (price, licence, open source, platforms, type, use case, relationships) need a quote that states them, or they
stay "Not stated". Who a resource is for and how technical it is are judgements: a model may estimate them, and the
page says so.

Much of what the Scout finds is writing about other things: a news item about a new model, a blog post about a launch,
a paper about a method. Asked the same questions as a product, an article gets the facts of what it talks about: a
news item about "Gemini 4 Carbon" came back with Gemini's pricing. The article is *about* a model, and there must be a
place to say that.

## Decision
### The gate
1. **Input.** `CatalogEnrichmentStore.load` reads the resource through the catalog's port `EnrichmentCandidates`, with
   its type, and turns each source record (title, a line break, description) into one `Material` with an evidence id.
   The values the sources state (licence, type unless `OTHER`, open source) are
   `sourceFacts`. The platforms and use cases the sources state (the resource's `PLATFORM` and `USE_CASE` labels) are
   `sourceStated`. The first 16 bytes of a SHA-256 of the material text are its text hash.
2. **The fingerprint and the UNCHANGED skip.** The input fingerprint (`EnrichmentMaterial.fingerprintFor`) is the same
   kind of hash over the text hash, a fixed epoch (`FINGERPRINT_EPOCH`, `enrich-v3`) and the model id. The prompt's
   label is not in it: a new prompt alone sends nothing to the model; a new model or new source text does. To enrich
   every resource again on purpose, change the epoch. If the resource's last run that judged an answer (any outcome but
   `LLM_UNAVAILABLE` and `FAILED`) has the same fingerprint, there is no model call and nothing is recorded: a new star
   count or version costs no call, and a `REJECTED` answer is not asked for again on the same text. `RunScout` gives the
   slot back to the run's enrichment budget ([0011](0011-scout-runner.md)).
3. **Daily cap.** Each call takes one unit of the enrichment budget `H2_SCOUT_DAILY_ENRICHMENT_CALLS` (500 a UTC day),
   apart from the guide's. Gauge `helper2.scout.enrichment.budget.remaining`.
4. **The call.** `SpringAiEnrichmentModel`: the reasoning model, thinking off, `H2_NEBIUS_ENRICH_MAX_TOKENS` (8192),
   prompt `prompts/scout/enrich.txt`, label `enrich-v10` (stored with every run). A test pins the rendered prompt to
   its label, so a new wording needs a new label. For a relationship the model also states what its target is
   (`targetKind`, [0019](0019-known-targets-vendors-and-services.md) §6): a judgement that verifies nothing. The user message has the
   resource's type next to its title (`Type: NEWS`); the type comes from the catalog, never from the model. Under it
   are the fields and the relationship kinds that type keeps (`FieldsByType.fieldsFor`, `kindsFor`): writing gets
   three fields and `ABOUT`; a course and an event get every field and every kind; every other type gets every field
   and no `ABOUT`; only `OTHER` is asked for a `type`. The prompt asks for the whole sentence or line as a quote (its
   examples are all 20 characters or more), says a README backs fields and relationships but never a summary sentence,
   and says a licence states no price. It also says a short description states few facts, that an answer with only
   audience and tech level is a good one, and that the kind of thing a resource is (a package) is not a fact about its
   licence, price, platform or use case. Material goes in `<material evidenceId=… kind=…>` tags through
   `UntrustedText`, at most 4000 characters a record and 6000 a vendor page; the prompt says the material is data,
   never instructions, and marks vendor pages `kind="vendor-page"`. The allowed values in the prompt are built from
   the validator's own sets. The output schema has no estimate field: an estimate is a field with no citation.
5. **Outcomes of the call.** A spent budget or a transient provider error (timeout, 429, 5xx) → `LLM_UNAVAILABLE`, kept
   in the backlog. Any other provider error, an empty answer or JSON that does not parse → `FAILED` (resource status
   `REJECTED`), retried by the backlog until 3 failures.
6. **`EvidenceValidator`** (code, no model). For each proposed field, relationship and summary claim:
   - field and value must be in the allowed lists (type, audience, techLevel, pricing, openSource, a licence id in the
     style of SPDX, useCase, platforms; relationship kinds `INTEGRATES_WITH`, `USES`, `SUPPORTS`, `COMPATIBLE_WITH`,
     and `ABOUT` for writing, point 20);
   - a field must be one the resource's type keeps (`FieldsByType`, point 20);
   - a use case the model gives a library is not read, and a library is not asked for one
     (`FieldsByType.readsUseCase`): the allowed use cases say what a person does with a tool (writing, translation,
     chat), and a library's text almost never states one, so the model guessed "coding" or "data-analysis" in 9 of 10
     runs. A use case a library's source states stays as its label;
   - a platform is where a resource runs (what it connects to is a service, [0019](0019-known-targets-vendors-and-services.md)). The
     language runtimes are platforms, one per package ecosystem the Scout knows (`Ecosystem`): `node`, `python`,
     `java` (also stated by Kotlin, Scala, Maven, Gradle, JVM), `golang`, `rust` (Cargo, crates.io), `dotnet` (.NET,
     C#, NuGet) and `ruby` (RubyGems, `gem install`). Go's value is `golang`, and "go" alone states nothing: a value
     states itself as a whole word, and "go to the settings" would state a runtime;
   - a platform or a use case the sources already state is not read: PyPI says `python`, so the model's `python` adds
     nothing and needs no quote. Such a field is also not missing (`EnrichmentInput.statedBySource`): the run reads no
     README and no page for it alone;
   - a `type` that repeats the catalog's type is not read: it says nothing new, and `OTHER` is never stated. A `type`
     that differs is judged like any fact;
   - at most 3 `useCase` entries are read (`MAX_USE_CASES`, the number the prompt states);
   - every citation must name a retrieved evidence id, and its excerpt (20–300 characters) must appear verbatim (case
     and spacing ignored) in that material;
   - a field that differs from a source-stated value is `CONFLICTING`, never an overwrite;
   - for `license`, `openSource`, `platforms`, `pricing`, `type` and `useCase` the excerpt itself must state the value,
     as a whole word or a known synonym ("MIT", "open-source", "command-line"); otherwise `UNVERIFIED`. Pricing: FREE
     "free", "$0", "no cost"; FREEMIUM "free plan", "free tier", "free forever", "freemium"; PAID a currency sign before
     a number, "per month", "/mo", "per seat", "per user", "contact sales"; OPEN_SOURCE_SELF_HOSTED "open source",
     "self-host". "Free trial" (and "try for free", "14-day free") is removed before matching: a trial states no price.
     Type and use case have their own word lists; `OTHER` is never stated;
   - a relationship whose target names a kind of thing, not a product ("agent", "tool", "CLI", "AI model", "MCP
     servers"), is not read: no relationship, no error, no review item (`namesAKindOfThing`);
   - a relationship's excerpt must name its target, outside a clause that says what the resource comes from or
     stands in for: "fork of", "forked from", "port of", "ported from", "clone of", "alternative to", "replacement for",
     "inspired by", "instead of", "unlike", "compared to/with", "vs", "versus", up to the next `,`, `;` or end of
     sentence. "A fork of Audio Diffusion - PyTorch" does not name PyTorch as something the resource uses; "a fork
     of Audio Diffusion, rewritten to use PyTorch" does (`Lineage`). "VS Code" is a product, not "vs". A target named
     only in such a clause is `REJECTED`. `ABOUT` keeps every mention: writing that compares two products is about
     both. It is `VERIFIED` only when the target (4 characters or more) names
     exactly one `INDEXED` or `CURATED` resource (`JdbcEnrichmentCandidates.titled`), else `UNVERIFIED` and a review
     item. A resource is named by its title, when it is a tool, model, library, agent, skill or dataset. An MCP server
     is named by its title only when the name itself says MCP ("chrome-devtools-mcp", "Browserless MCP"): a server
     titled "Linear" or "Supabase" is a server for that product, not the product. A
     product, model, library, agent or dataset is also named by its repository name, the part of an `owner/name` title
     after the slash, with `-`, `_`, `.` and spaces read as one space: "Claude Code" names `anthropics/claude-code`. An
     MCP server or a skill is never a target by its repository name: a registry entry `com.example/slack` is not Slack.
     A name two such resources share is ambiguous; a page only a search saw, an article or an event is never a target
     by name alone;
   - a target that names a known vendor (the table `known_target`, [0019](0019-known-targets-vendors-and-services.md): OpenAI,
     Anthropic, Google, about 30 companies, each with its other names; case, spaces and a final "'s" ignored) is `VERIFIED` with no target
     resource: the relationship keeps the vendor's one name as its title and `target_kind = 'VENDOR'`, and opens no
     review item. A vendor's name is the vendor, even when the catalog holds a package of that name (`openai`,
     `huggingface`): the vendor is looked up first. A vendor only the product's own page names stays
     `UNVERIFIED`, like any self-declared target. A vendor is a name, not an entity: it has no page of its own.
     - **Several vendors.** Each vendor is its own relationship row, so one resource can have several:
       `INTEGRATES_WITH OpenAI`, `INTEGRATES_WITH Anthropic`, `SUPPORTS Google`.
     - **One row per resource, kind and vendor.** "OpenAI", "Open AI" and "OpenAI's" become the one name "OpenAI", so
       the same vendor is not stored twice for the same kind.
     - **Limits.** The caps of every relationship apply: at most 3 from the product's own page, at most 3 `ABOUT` a
       run. Other relationships have no cap.
     - **Meaning.** The relationship says "works with vendor X", which is many to many. "Made by vendor X", one owner
       per resource, is another fact and is not stored;
   - summary claims need valid citations: at most 3 are read (`MAX_SUMMARY_CLAIMS`, the number the prompt states), 300
     characters each. Every capitalised name and every number in the sentence is in its cited excerpts (or the title),
     and at least half of its content words are; no superlative, certificate, popularity or comparison. A sentence that
     fails is dropped.

   Run outcome: `REJECTED` when nothing is accepted, `VALIDATED` with no error and no unverified relationship, else
   `PARTIAL`.
7. **Judgement fields.** `audience` and `techLevel` may be proposed without a quote. They are stored with origin
   `AI_ESTIMATED` and verification `UNVERIFIED`, never `VERIFIED`, and never counted as accepted. A fact is never
   `AI_ESTIMATED`. As in [0033](../guide/0033-marking-ai-written-text-research-trail.md): the page labels them
   "AI-estimated" and marks them `data-ai-generated`; the API says `origin: AI_ESTIMATED` (OpenAPI enum `ValueOrigin`).
8. **Storage, owned by the catalog.** The Scout hands each run to the catalog's port `EnrichmentResults`
   (`record(run, checked items)`); the SQL is in `catalog/internal/JdbcEnrichmentResults`, which also reads the results
   back for the detail page. Every run is an `enrichment_run` row: provider, model, prompt label, fingerprint, outcome,
   errors, and the extra text it used (`extra_evidence`). In the same transaction, a run with model output:
   - closes the resource's `OPEN` review items of earlier runs as `SUPERSEDED` (resolved by the system, with a note
     naming the new run);
   - deletes the resource's older AI field assertions, and its AI relationships that no reviewer decided (no `ACCEPTED`
     or `REJECTED` item), each with the evidence rows only they used;
   - writes the non-rejected fields with their verification state and origin, relationships, claims and evidence. A
     relationship a reviewer decided stays as decided, and the same proposal does not reopen it. A citation of a source
     record that went away during the run (a withdrawal, a purge) is dropped, and a field, relationship or claim left
     with no evidence is not written: the rest of the run is saved.

   The detail page shows the summary claims of the newest `VALIDATED` or `PARTIAL` run that has claims. A run without
   model output (`LLM_UNAVAILABLE`, `FAILED`) leaves the last output and its review items as they are.
9. **Review queue** (`ReviewQueue`). A validation error a person can act on, or a `CONFLICTING` field, opens an
   `ENRICHMENT_VALIDATION` item. A value its quote does not state is not one of them (`ValidationResult.forReview`): the
   field stays `UNVERIFIED` and shows "Not stated", the line stays in the run's errors, and the run stays `PARTIAL`, but
   no item opens for it alone. A short source text makes the model guess (a library is "coding", a package is "MIT"), and
   the only decision left to a person would be to reject. When an item opens for another reason, it lists these lines
   too. The re-check (point 23) closes the items an earlier rule opened for such values alone: `SUPERSEDED`, resolved
   by `scout`. An
   `UNVERIFIED` relationship opens a `RELATIONSHIP_CANDIDATE`; it becomes `VERIFIED` when a person accepts it, or when
   the catalog comes to hold its target (below).
   Statuses: `OPEN`, `ACCEPTED`, `REJECTED` (a person's decision) and `SUPERSEDED` (a newer run, or a target the
   catalog now holds). Admins use
   `GET /api/v1/admin/review-items` (newest first, filtered by status, `page` and `size` up to 100, with the total) and
   `PUT /api/v1/admin/review-items/{id}/resolution` (`ACCEPTED` or `REJECTED`); the decision's SQL is in
   `catalog/internal/JdbcReviewDecisions`. Gauge `helper2.review.open_items` counts `OPEN` items; the alert
   `Helper2ReviewBacklog` fires when more than 50 wait for a day.

### The evidence ladder
   **A candidate whose target arrives later is linked by code.** At the end of every Scout run
   ([0011](0011-scout-runner.md) §7), `LinkKnownRelationshipTargets` reads the `OPEN` relationship candidates with no
   target and looks each name up by the rule of point 6 (title or repository name, exactly one `INDEXED` or `CURATED`
   resource, never the resource itself, 4 characters or more). A match sets the relationship's target and makes it
   `VERIFIED`; its item becomes `SUPERSEDED`, resolved by `scout`, with the note "The target is now in the catalog:
   linked by its name". `ACCEPTED` stays a person's decision. Left for a person: a self-declared relationship, a target
   an admin renamed or linked, a name with no match or more than one. One SQL statement
   (`JdbcRelationshipTargets.linkOpenCandidates`); the name rule is one fragment shared with the run's own lookup
   (`TargetNames`). Counter `helper2.scout.targets.linked`. The same step first confirms the open candidates that name a
   known vendor (point 6): `VERIFIED`, `target_kind = 'VENDOR'`, item `SUPERSEDED` with the note "The target is a
   known vendor"; the title stays as the model wrote it until the resource's next run.
10. **When it climbs.** After the first run, inside the same `EnrichResource` attempt, when the run is `PARTIAL` or
    `REJECTED`, or a target fact of the type is still missing (`FieldTargets.forType`, pure code):
    - `PRODUCT`, `AGENT`: pricing, platforms, use case;
    - `MCP_SERVER`, `SKILL`: licence, open source, platforms, use case;
    - `LIBRARY`: licence, open source, platforms (no use case, point 6);
    - `MODEL`, `DATASET`: licence, open source, use case; `PROMPT`: use case;
    - reading types (articles, papers, news, events, courses): none, their facts come from their sources.

    A fact the sources state is not missing. Audience and tech level never make it climb.
11. **The steps.** Each step is one more model call with more material; at most three model calls an attempt, never a
    loop:
    - step 1: the records;
    - step 2: the README or model card (point 12);
    - step 3: the product's own pages (point 14).

    Each step keeps the run with more accepted items (verified fields, relationships not rejected, claims); a step that
    keeps no more is dropped. The attempt saves once, under the fingerprint of the records alone. The extra text is not
    stored, so the next run with unchanged records is `UNCHANGED`: the README and the pages are read once per
    fingerprint, and fields do not flip back and forth. The run stores which text it used in
    `enrichment_run.extra_evidence` (`github-readme:owner/repo`, `official-page:<host>`).
12. **Where a README comes from** (`GapTargets.select`, pure code):
    - a `github` or `github-awesome` record: that repository's README;
    - a discovery record (`discovery-<category>`, `WebSources.isDiscovery`) whose URL is `github.com/<owner>/<repo>`: a
      person accepted it;
    - an `hf-models`, `hf-trending` or `hf-provider-orgs` record: that model's card;
    - an `mcp-registry` record: its repository's README, only when the entry's namespace is `io.github.<owner>` and
      `<owner>` owns the repository. Anyone can publish an entry that links any repository; without this check a famous
      README would prove facts about someone else's server.
    - an `npm` or `pypi` record that links a repository, when no record above gives one: that repository is
      visited. Anyone can publish a package that links a famous repository, so its README is evidence only when it
      tells how to install this package (`GapTargets.namesPackage`: `npm install <name>`, `pip install <name>` and
      the like, the whole name); otherwise the visit is counted `package_not_named` and no text is used.
    - Every visit of a repository records on the source record if GitHub says it is archived
      ([0020](0020-resource-vitality.md) §7). That fact can only hide a result, so it needs no proof of
      ownership.
    - Links must match `https://github.com/<owner>/<repo>` or `https://huggingface.co/<owner>/<name>` exactly, so no
      link can reach another API path with our token.
13. **Reading a README** (port `ExtraEvidence`, adapter `sources/evidence/ReadmeEvidence`):
    - GitHub: `GET /repos/{owner}/{repo}/readme` (raw) and `GET /repos/{owner}/{repo}` for the licence GitHub states.
    - Hugging Face: `GET /{owner}/{name}/raw/main/README.md`; the licence is the `license:` line of the card's front
      matter.
    - Owner and name are URI template variables. At most 512 KB. Policy `collector-gap-evidence` (circuit breaker and
      retry, like a collector). A missing README is no text; any other failure leaves the first run as it is. A redirect
      to another host is refused and counts as a failure. Reading a README needs `H2_GITHUB_TOKEN` (without it GitHub
      allows 60 calls an hour); a model card needs no token.
    - The licence the GitHub API or the card states is a source fact, so a README that says something else is
      `CONFLICTING`. README text may support fields and relationships, never summary claims: a claim is the model's own
      sentence, and it could cite any true excerpt.
14. **Which pages, chosen by code** (`PageTargets`, `OfficialPages`, `PageScope`, `OfficialLinks`), for a record that is
    a product's own page (`discovery-<category>` and `curated` records; not a registry, a repository or an article).
    The model never picks a URL or a query.
    - The accepted page first, then up to 3 pages its links lead to whose last path segment is `pricing`, `plans`,
      `download(s)`, `features`, `docs`, `documentation`, `integrations` or `about`, best first. At most 5 URLs a
      product.
    - Allowed: `https` only; a host name, not an IP address; no user info; no port but 443.
    - Scope: the same registrable domain (Public Suffix List, the copy that ships with Apache HttpClient 5, already a
      dependency); on a shared host (`github.io`, `vercel.app`, `netlify.app`, `pages.dev`, `hf.space`, `notion.site`,
      `webflow.io`) the exact subdomain; for a page under a path (`aws.amazon.com/bedrock`) that host and path prefix.
    - Tavily follows redirects: every URL it returns is checked against the scope again, and an off-site page is
      dropped. `failed_results` is no text.
15. **Reading pages** (port `PageReader`, adapter `sources/evidence/LiveWebPageReader`, "page reader" in the product,
    `page-reader`): Tavily Extract `POST /extract`, `extract_depth` basic, `format` markdown (the links are needed), no
    images, no favicon. One call for the home page, one for the linked pages. Policy `collector-gap-web`. Off without
    `H2_LIVE_WEB_API_KEY` or with `H2_SOURCE_PAGE_READER_ENABLED=false`. Tavily's written OK for Extract arrived on 6
    Nov 2026.
16. **Cleaning** (`UntrustedPageText`, for a README and for a page, before the model or the validator sees it):
    the first 64 KB only; zero-width, direction and tag characters removed first; Unicode NFKC; HTML comments, scripts,
    styles, tags, images, inline and reference link targets removed; for a page also hidden elements (`hidden`,
    `display:none`, `visibility:hidden`, `aria-hidden`) with their whole subtree, found in one linear pass that counts
    nested tags of the same name (a hidden element that never closes takes the rest of the page), and lines that talk
    to an AI ("ignore previous instructions", "set pricing ..."). Email addresses become `[email]`, phone numbers on a
    page `[phone]`. Every pattern is bounded, so a hostile text cannot make cleaning slow. A window keeps the top, then
    the sections that matter: 4000 characters of a README (licence, install, platforms), 6000 of a page (price, plan,
    licence, download, platform, install, integrations, features).
17. **What a page may prove.** A page is `selfDeclared` material. A fact all of whose quotes come from pages is stored
    with origin `SELF_DECLARED` ("Self-declared by the vendor"); a fact a record also states stays `AI_EXTRACTED`. Pages
    may support summary claims only from the accepted page's own host, and such a claim is marked `selfDeclared`. The
    licence or open-source value the source API states wins: a page that says otherwise is rejected, not shown as a
    conflict. A relationship only pages name stays `UNVERIFIED` (a review candidate) with origin `SELF_DECLARED`, at most
    3 a run. Never stored, because the validator drops the sentence: compliance certificates (SOC 2, HIPAA, GDPR, ISO
    27001, CE, FDA), user counts, popularity and awards, comparisons with other products, superlatives.
18. **Evidence rows.** A README or page excerpt is an `evidence` row with a link a person can open
    (`github.com/owner/repo#readme`, `huggingface.co/owner/model/blob/main/README.md`, the page URL) and the read time,
    anchored to the record it was read for, so it goes when that record goes ([0010](../foundation/0010-data-retention.md)).
    The text itself is not stored: only the 20–300-character excerpts the validator accepted. The detail page names the
    source of such a quote: `owner/repo · README`, `owner/model · model card`, the vendor's page.
19. **Budgets and cost.**
    - Model calls 2 and 3 take the gap cap `H2_SCOUT_GAP_DAILY_CALLS` (400), a share of the 500 enrichment calls, not
      on top (both caps count them): at least 100 calls a day stay for first runs. A gap cap above the enrichment cap
      has no effect. A step is taken only when there is text to send; when none is left today, or with `0` (off),
      nothing is read either. The per-run enrichment budget ([0011](0011-scout-runner.md)) counts resources, not gap
      calls: a run can make up to its budget plus the gap calls left today. Gauge `helper2.scout.gap.budget.remaining`.
    - Extract credits: `helper2.scout.evidence.daily-page-read-credits` (`H2_SCOUT_PAGE_READER_DAILY_CREDITS`, 20). Each
      credit also counts on the global Tavily credit counter of live search
      ([0027](../search/0027-live-web-store-first-daily-quota.md), `H2_LIVE_WEB_DAILY_CREDITS`), and on
      `helper2.live-web.credits{feature=scout-evidence}`. When the global counter cannot answer, no page is read. Gauge
      `helper2.scout.evidence.credits.remaining`.
    - **The one exception to "no page text stored".** The cleaned text of a product's pages (what `UntrustedPageText`
      left) is kept in Redis only, under a SHA-256 of the accepted page's URL, for `page-text-ttl`
      (`H2_SCOUT_EVIDENCE_PAGE_TEXT_TTL`, PT24H; more than P1D stops startup). Only to retry: when the model is down or
      the gap cap is spent, the next attempt within the day reads the cache and pays no credit. The raw page is never
      kept, and the text never goes to Postgres, logs or metrics. A cache that cannot be read or written does not stop
      the run: the read is a miss, and a warning names the class of the failure, never the URL or the text.
    - A page a saved run read is not read again for 30 days (`page-read-ttl`, `H2_SCOUT_EVIDENCE_PAGE_READ_TTL`): the
      shared cache (`ai_cache`, name `scout-page-read`) keeps a hash of the URL and a hash of its text, never the text.
      It is written only after the run is saved: a failed model call leaves no entry, so the page can be read again once
      the 24-hour text is gone.
    - Products a person accepted from the review queue go first in the enrichment backlog.

### Writing about a resource
20. **Writing is ARTICLE, NEWS and PAPER** (`FieldsByType`). It keeps three fields: `audience` and `techLevel` (who it is
    for) and `useCase` (its topic). Pricing, licence, open source, platforms and type are facts of what it is about: the
    validator refuses them for writing ("not a fact of a NEWS, but of what it is about"), whatever the quote says. Every
    other type keeps every field.
21. **ABOUT links writing to its subject.** A relationship kind from the article to the model, product or tool it is
    mainly about. It follows the rules of every relationship (point 6):
    - the quote must name the target;
    - a target the catalog has by that title or repository name (point 6), once, among its indexed or curated
      resources, is linked and `VERIFIED`;
    - an unknown target stays `UNVERIFIED` and opens a `RELATIONSHIP_CANDIDATE` review item;
    - at most 3 a run (`MAX_ABOUT`): an article is mainly about a few things, not every name it drops;
    - writing has no other kind;
    - a course and an event keep every field and every kind, and also say what they teach or present with ABOUT,
      under the same cap: a course on LangChain is about LangChain;
    - every other type never has ABOUT: a tool is not about another tool.
22. **Discovery keeps posts about products** as ARTICLE candidates, under their own URL ([0013](0013-discovery.md) §5).
    Every discovery category declares ARTICLE, so a post can be accepted; its enrichment then names what it is about.

### The re-check of stored answers
23. **`RecheckEnrichments`** applies the rules above to what earlier runs stored, by code, with no model call:
    - a verified pricing, type or use case whose quotes do not state the value is set to `REJECTED` (it shows "Not
      stated");
    - a field writing does not keep (point 20) is rejected, whatever its verification;
    - a summary sentence that fails the claim check is dropped, with the evidence only it used;
    - an open `ENRICHMENT_VALIDATION` item whose run has no issue but values the quote does not state, and no
      `CONFLICTING` field, is closed (`closeItemsOfUnstatedValues`; counter result `items_closed`);
    - a resource goes back to the model only when it still misses a target fact (`FieldTargets`), has more to read (a
      README or model card, or its own pages) and its last run used an older prompt label. It is set back to `NOT_RUN`:
      the backlog takes it, accepted products first, inside the daily caps. `resource.enrichment_rerun_requested_at`
      makes the runs judged before the request not count for the UNCHANGED skip; the next run after it does again.

    It runs once after the first start where the Scout is scheduled, under the Scout's run lock (`scout-run`), so it
    never changes answers a run is writing; the marker `recheck-enrichment-enrich-v7` in `job_marker` is written when it
    is done. A start while a run holds the lock, or a re-check that fails half way, leaves no marker: the next start
    tries again. It also runs whenever an admin presses "Re-check stored answers"
    (`POST /api/v1/admin/enrichment-recheck`, audit action `RECHECK_ENRICHMENT`), under the same lock: while a Scout
    run holds it, the call answers 409 `run-in-progress` ("A Scout run is in progress. Try again later."), changes
    nothing and writes no audit entry. Idempotent: a second run changes
    nothing. Log line and counter `helper2.scout.recheck{result=checked|fields_rejected|claims_dropped|runs_requested}`.
    A prompt with new rules gets a new label and a new marker, so the re-check runs once for it.

### What people see
24. **Observability.** Counter `helper2.scout.fields{type,field,state,step}` per target and judgement field of the kept
    run: `state` = `verified`, `ai_estimated`, `rejected` or `unknown`; `step` = `source`, `records`, `readme` or
    `pages`. Counter `helper2.scout.gap{source,outcome}`: sources `github-readme`, `hf-model-card`, `official-page`; outcomes
    `filled`, `not_better`, `no_text`, `source_failed`, `model_failed`, `skipped_cap`, and for pages `skipped_seen`,
    `not_allowed`, and `cached` when a retry used the 24-hour text.
25. **The page.** The resource page has a "Fields" panel: each field shows its value and where it comes from ("From the
    source", "Self-declared by the vendor (extracted by AI)", "Extracted by AI", "AI-estimated"), or "No", "Sources
    disagree", or "Not stated". A fact without a quote that states it is "Not stated". Cards show only what the sources
    state. An AI-written description is headed "About (AI-written)". An article shows what it is about ("About Gemini 4
    Carbon"), not the subject's price or licence.

**An admin's edit wins.** A field an admin set is a stated fact, and the model's value for it is dropped. An admin
links, adds or renames an ABOUT target in the review list ([0038](../backoffice/0038-back-office-and-review.md)).

### Code
- `backend/apps/backoffice/src/main/kotlin/ai/helper2/scout/usecase/EnrichResource.kt` — the attempt, the ladder,
  `helper2.scout.fields`.
- `backend/apps/backoffice/src/main/kotlin/ai/helper2/scout/Enrichment.kt` — proposal and result types,
  `EnrichmentInput.type`, `Material.anchorRecordId`, `supportsClaims`, `FINGERPRINT_EPOCH`; `EvidenceValidator.kt` —
  stating words, `PricingWords`, `ClaimCheck`, judgement fields, self-declared rules, fields and kinds by type, the
  ABOUT cap; `FieldsByType.kt`.
- `backend/apps/backoffice/src/main/kotlin/ai/helper2/scout/GapRound.kt` — `GapTargets`, `GapKind`,
  `UntrustedPageText`, ports `ExtraEvidence`, `GapBudget`; `OfficialPages.kt` — `FieldTargets`, `PageTargets`,
  `PageScope`, `OfficialLinks`, `OfficialPages`, ports `PageReader`, `ExtractBudget`, `PageLedger`, `PageTextCache`,
  `DomainRoots`.
- `backend/apps/backoffice/src/main/kotlin/ai/helper2/scout/usecase/RecheckEnrichments.kt` — the re-check, port
  `StoredAnswers`; `scout/internal/CatalogStoredAnswers.java`.
- `backend/apps/backoffice/src/main/java/ai/helper2/scout/internal/SpringAiEnrichmentModel.java` (`PROMPT`,
  `PROMPT_VERSION`, material kind), `CatalogEnrichmentStore.java`, `ScoutConfiguration.java` (budgets,
  `officialPages`), `RedisPageTextCache.java`, `PageReaderCreditBudget.java`, `SharedCachePageLedger.java`.
- `backend/apps/backoffice/src/main/resources/prompts/scout/enrich.txt`.
- `backend/apps/backoffice/src/main/java/ai/helper2/sources/evidence/ReadmeEvidence.java`, `LiveWebPageReader.java`,
  `PublicSuffixDomains.java`; beans in `sources/config/scout/CollectorsConfiguration.java`.
- `backend/libs/catalog/src/main/java/ai/helper2/catalog/enrichment/EnrichmentCandidates.java`, `EnrichmentResults.java`,
  `StoredEnrichments.java` (named interface `enrichment`); `catalog/internal/JdbcEnrichmentCandidates.java` (each source
  text with its source id, external id and repository link; accepted products first), `JdbcEnrichmentResults.java`
  (origins, evidence with its own URL, the stored type).
- `backend/libs/catalog/src/main/java/ai/helper2/catalog/ReviewQueue.java`, `catalog/internal/JdbcReviewDecisions.java`,
  `catalog/usecase/ListReviewItems.java`, `GetReviewItem.java`, `ResolveReviewItem.java`;
  `backend/apps/backoffice/src/main/java/ai/helper2/admin/web/AdminController.java`.
- `backend/libs/kernel/src/main/kotlin/ai/helper2/shared/Vocabulary.kt` — `ValueOrigin.AI_ESTIMATED`, `SELF_DECLARED`.
- `backend/libs/schema/src/main/resources/db/migration/V1__schema.sql` — `enrichment_run` (`extra_evidence`), origins,
  relationship kind ABOUT, `job_marker`; `api/common.yaml`, `api/public.yaml` — `ValueOrigin`, `Relationship.origin`,
  `SummaryClaim.selfDeclared`, kind ABOUT.
- `frontend/src/features/resource/FieldStates.tsx`, `frontend/src/features/resource/ResourcePage.tsx`, `frontend/src/lib/aiMark.ts`,
  `evidenceSource.ts`, `frontend/src/features/resource/EvidenceList.tsx`, `frontend/src/admin/ScoutRunsPage.tsx`.
- `deploy/monitoring/helper2-rules.yaml` — `Helper2ReviewBacklog`.
- Tests: `SpringAiEnrichmentModelTest` (`theWordingOfThePromptIsPinnedToItsLabel`), `EnrichResourceTest`,
  `GapRoundTest`, `EnrichResourceGapRoundTest`, `ReadmeEvidenceTest`, `FieldTargetsTest`, `EvidenceValidatorLadderTest`,
  `EvidenceValidatorWritingTest`, `OfficialPagesTest`, `EnrichResourceLadderTest` (adversarial pages),
  `LiveWebPageReaderTest`, `PublicSuffixDomainsTest`, `PageReaderCreditBudgetTest`, `RecheckEnrichmentsTest`,
  `RedisPageTextCacheIT`, `RedisPageTextCacheTest`, `ScoutPropertiesBindingTest`, `CatalogEnrichmentStoreIT`, `CatalogEnrichmentStoreTest`,
  `JdbcEnrichmentCandidatesIT`, `SecurityIT`, `ResourcePage.test.tsx`, `Admin.test.tsx`, `evidenceSource.test.ts`.

## Alternatives considered
- **Trust the model's output, or its confidence scores** — no traceability; invented licences would be published;
  confidence is not calibrated.
- **Let the model overwrite source values, or change the type** — sources are the record of truth for what they state;
  a news item about a model is still news.
- **Keep the prompt label in the fingerprint** — simple, but every prompt change re-enriches the whole catalog: days of
  the daily cap for resources that miss nothing. **Fingerprint the source text alone** — a better model would never
  reach resources already enriched.
- **Keep every run's relationships and items** — pages and the queue would show proposals the newest run does not make.
- **Model tool calling** (the model asks for pages or searches) — the model would choose what to read, an injected page
  could steer it to any URL, and the cost would not be bounded by code.
- **Fetch pages ourselves** — no third party, but we would need SSRF protection, robots and rate limits per site,
  JavaScript-heavy pages would be empty, and our servers' address would visit every product site.
- **Tavily site search** (`include_domains`) to find the pricing page — a search credit per product, and excerpts, not
  pages. Only if the fill rate stays low.
- **Store the README or the page text** — copies third-party text we do not need, at a privacy cost; the quotes are
  enough. **Keep the page text only in memory** — a model outage after the read pays Tavily again.
- **Fold a README hash into the fingerprint** — a GitHub call on every run just to compute it.
- **The gap round as a separate backlog step** — two saves per resource, and a worse run could replace a better one.
- **A new verification state `SELF_DECLARED`** — it would leak into every place that reads verification (review items,
  `accepted`, the badge). An origin is the smaller change: `ValueOrigin` already drives the label.
- **Let audience and tech level stay empty** — honest, but most products would show nothing useful; an estimate,
  labelled, helps and is never presented as a fact.
- **A `subject_id` column or a mention table for writing** — one subject only, or the same data as a relationship with
  its own retention, API and review flow.
- **Create a resource for an unknown ABOUT target** — a resource needs its own URL, and the article gives only a name.

## Consequences
- Nothing the model says is shown as verified without a quoted excerpt from retrieved text. A page never mixes AI
  fields or unreviewed AI relationships from two runs, and the review queue only asks about the newest run.
- Product pages get prices, platforms and use cases with a quote and a link, marked as what the vendor says. Fewer
  unproven fields and fewer review items, at most 400 extra model calls a day, inside the 500.
- The cost is bounded per run (`H2_SCOUT_MAX_ENRICHMENTS`: 20 by default, 200 in the Helm chart) and per day (500);
  unchanged text costs nothing and does not use up a slot of the run. At most 20 Extract credits a day (about 10
  products), inside the global cap. See [cost-analysis](../../cost-analysis.md).
- A new prompt does not re-enrich the catalog: the code re-check cleans the stored answers once; only resources that
  still miss facts and have something new to read go to the model again, accepted products first.
- A resource is read again only when its source records change, or the model changes. A page is read once in 30 days:
  a price that changes later is not seen until then. A retry after the 24-hour text expired pays Tavily
  again (one or two credits).
- Source-stated `type` is a fact, so a model that disagrees (for example LIBRARY vs MCP_SERVER) opens a review item. Any
  field that contradicts a source-provided value opens one `ENRICHMENT_VALIDATION` item for the run.
- An ABOUT target that is not in the catalog waits in review by name. The admin links it to a resource, adds the
  resource, or renames it ([0038](../backoffice/0038-back-office-and-review.md)). Discovery proposes posts too: more review
  work, one candidate per URL.
- Tavily (United States) receives the addresses of product pages. The raw page is never stored. The cleaned text stays
  in Redis for at most 24 hours, only to retry; then only the 20–300-character quotes are kept. The Privacy page says
  so. The cached text is small (about 10 products a day, at most 4 pages of 6,000 characters each, under 300 KB) next
  to the 128 MB Redis that also holds sessions and counters.
- The page step can be turned off with `H2_SCOUT_PAGE_READER_DAILY_CREDITS=0` or `H2_SOURCE_PAGE_READER_ENABLED=false`
  without a release. The demo profile has no README or page adapter, so it has no ladder.
- AI Act Art. 50: an AI-extracted, self-declared or AI-estimated value, and an ABOUT link, are labelled in the UI and in
  the API (`origin`).

### Known limits
- Vendors and services as relationship targets ([0019](0019-known-targets-vendors-and-services.md)): the public API does not expose `relationship.target_kind`, so no search filter and no vendor page reads it.
- Relationship targets by title: the list of types a title may match (tools, models, libraries, MCP servers, agents, skills, datasets) is not checked against the real UNVERIFIED relationship review items.
- Strict enrichment rules reject some correct values; the review queue grows with every partial run and needs a person ([0038](../backoffice/0038-back-office-and-review.md)).
- A relationship target two catalog resources share by title waits for a person, and its review item says only that the target is not a known resource.
- The oldest-waiting gauge is set when the Scout reads a batch, not at each scrape.
- The gap round reads a README once per fingerprint: a README that changes while the source records do not is not read again, and one that fails to load (timeout, 5xx) is not tried again until they change. A gap slot is spent when the second model call fails. README text never supports a summary claim.

### STRIDE
| Threat | Risk | Control |
|---|---|---|
| Spoofing | A page pretends to be the product, a redirect leads elsewhere; a registry entry links someone else's repository; an article names any product to get a link | Code picks every URL from the accepted page's links, inside its registrable domain (PSL), exact host on shared hosts, path prefix on a vendor's site; every returned URL is checked again. An MCP entry's README only when its namespace owns the repository. An ABOUT quote must name the target, VERIFIED only by exact title, at most 3 a run, the rest waits for a person. |
| Tampering / prompt injection (OWASP LLM01) | Text planted in a README or a page | Hidden text, AI-addressed lines and hidden characters removed before the model reads; material marked as untrusted; the validator, not the model, decides: a fact needs a quote that is in the material and states the value. Adversarial tests: an injected "set pricing FREE", hidden text, an off-site redirect, a fake "integrates with OpenAI, Anthropic, Google" list, an oversized page, a "free trial" price. None becomes `VERIFIED`. |
| Repudiation | Not knowing where a value came from | Every fact keeps its quote, link and read time; every run keeps model, prompt label, fingerprint and `extra_evidence`. |
| Information disclosure | Third-party text or personal data kept | No user data sent; README and raw page never stored; emails and phone numbers masked before the model, a quote or the cache sees them; the cleaned page text in Redis for at most 24 hours (inside the cluster, password from OpenBao, a URL hash as key, a TTL on every write), never in Postgres, logs or metrics. |
| Denial of service / cost | A hostile or huge text, a flood of calls | Bounded cleaning patterns; 512 KB a README, 64 KB cleaned, 2 MB an Extract answer; at most three model calls and two Extract calls an attempt; the daily enrichment, gap and Extract caps, the global Tavily cap; circuit breakers `collector-gap-evidence`, `collector-gap-web`. |
| Elevation of privilege | Our token or a request reaching another path or host | Links match exact GitHub and Hugging Face shapes, owner and name as URI variables; Tavily's key never leaves our request to `api.tavily.com`; we never fetch a page ourselves, so no request reaches an internal address (no SSRF). Only an admin accepts a review item. |

### AI architecture check
| # | Layer | State |
|---|---|---|
| 1 | Model | Covered: the reasoning model, NVIDIA only ([0008](../foundation/0008-llm-integration.md)); a provider outage is `LLM_UNAVAILABLE` and the first run stands. |
| 2 | Agent Framework | Covered: no agent loop; a fixed ladder of at most three calls, driven by code. |
| 3 | Orchestration | Covered: inside the Scout run; retries by resilience policy; one save per attempt; the backlog retries. |
| 4 | Tool & API | Covered: GitHub, Hugging Face and Tavily Extract behind ports, URLs only from code; model tool calling rejected (below). |
| 5 | Memory | Covered: cleaned page text in Redis for at most 24 h (retry only); the read ledger keeps URL and text hashes for 30 days. |
| 6 | Knowledge & RAG | Covered: grounding by quotes from retrieved text; no vector search here. |
| 7 | Data | Covered: `enrichment_run`, origins, `summary_claim.self_declared`, `job_marker` in V1; evidence anchored to its record ([0010](../foundation/0010-data-retention.md)). |
| 8 | Browser & Computer Use | Covered: none; Tavily reads the page, we never run a browser. |
| 9 | Guardrails | Covered: the evidence gate, stating words, claim checks, fields by type, URL scope, cleaning, caps on relationships, source facts win. |
| 10 | Observability | Covered: `helper2.scout.fields`, `helper2.scout.gap`, `helper2.scout.recheck`, budget gauges, credits; no page text in logs. |
| 11 | Evaluation | Gap: adversarial unit tests only; no fill-rate eval set exists (it needs real runs first). The pinned prompt test catches an unlabelled wording change. |
| 12 | Deployment | Covered: config only, on by default, off without a key; the page step off without a release. |
| 13 | Security & Identity | Covered: GitHub token and Tavily key from OpenBao; no new secret. |
| 14 | Human-in-the-Loop | Covered: conflicts, validation errors, unverified and self-declared relationships and unknown ABOUT targets wait in the review queue. |
| 15 | Cost & Reliability | Covered: daily enrichment, gap and Extract caps, the global Tavily cap, the 30-day ledger, the UNCHANGED skip, circuit breakers. |

### Points for a lawyer
1. Are the 20–300-character quotes of READMEs, model cards and product pages, shown with their link, covered by
   quotation or by the facts exception?
2. Do Tavily's terms and its written OK for Extract cover reading product pages and keeping the cleaned text in Redis
   for at most 24 hours?

## Diagrams
The gate: [flow](../../diagrams/flow-0014-evidence-gated-enrichment.svg) · [sequence](../../diagrams/sequence-0014-evidence-gated-enrichment.svg) ·
the gap round: [flow](../../diagrams/flow-0014-enrichment-gap-round.svg) · [sequence](../../diagrams/sequence-0014-enrichment-gap-round.svg) ·
the evidence ladder: [flow](../../diagrams/flow-0014-evidence-ladder.svg) · [sequence](../../diagrams/sequence-0014-evidence-ladder.svg) ·
writing about a resource: [flow](../../diagrams/flow-0014-writing-about-a-resource.svg) · [sequence](../../diagrams/sequence-0014-writing-about-a-resource.svg) ·
[architecture: index](../../diagrams/architecture-index.svg)

## Backlog
- Decides: [F2.4](https://github.com/silvioincalza/helper2.ai/issues/34).
- Open issues that change it:
  - [S2.4.6](https://github.com/silvioincalza/helper2.ai/issues/203): a gap round for resources enriched before it existed.
