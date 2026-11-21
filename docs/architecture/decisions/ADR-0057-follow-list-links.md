# 0057 — A link of a directory list is followed to the page of the item

**Depends on:** [0006](ADR-0006-outbound-calls-and-resilience.md), [0007](ADR-0007-configuration-secrets-runtime-settings.md), [0010](ADR-0010-data-retention.md), [0012](ADR-0012-scout-collectors-and-events.md), [0013](ADR-0013-discovery.md), [0014](ADR-0014-evidence-gated-enrichment.md), [0027](ADR-0027-live-web-store-first-daily-quota.md), [0055](ADR-0055-directory-lists.md), [0056](ADR-0056-directory-rating.md)

**Requirements:** NFR-005, NFR-015, C-005, C-006, C-008, C-012, C-014, C-016

**Use cases:** UC-030 (BR-006, BR-011, BR-013, BR-014)

## Context

The Scout reads the list pages of the directory hosts an administrator turned on
([0055](ADR-0055-directory-lists.md)). Each link of an entry goes to the rule of its kind
([0013](ADR-0013-discovery.md) point 17). Two kinds of link are skipped there, for safety: a link to a directory host,
and a link that redirects (a URL inside the path or the query, a path such as `/out` or `/go`, a link shortener). Many
directories link an entry only to their own page of the item, and that page links the item's site through a tracking
redirect. Such a list gives nothing.

What exists to follow a link:

- **The page reader** (`LiveWebPageReader`, Tavily `POST /extract`) returns the Markdown of an address. Its documented
  answer has `results[].url` ("the URL from which the content was extracted"), `raw_content`, and `failed_results`
  ([Tavily Extract reference](https://docs.tavily.com/documentation/api-reference/endpoint/extract), read on
  2026-11-21). The reference says nothing about redirects, and no field gives the address reached after a redirect.
  Tavily does follow redirects ([0014](ADR-0014-evidence-gated-enrichment.md) point 14), so the text of an address that
  redirects can be the text of another site with no field that names it. This is why `WebPageReader` treats an answer
  whose `url` is on another host as a failure, and why the reader cannot follow a redirect: it cannot say where it
  landed.
- **Links survive in the Markdown.** The reader is asked for `format: markdown` because "the links are needed"
  ([0014](ADR-0014-evidence-gated-enrichment.md) point 15), and the list parser already reads links from it. So the
  "Visit website" link of a directory's page of an item is in reach. No read of a real page of an item is stored: no
  directory host has its switch on.
- **A request of our own with a checked address** exists for the research assistant (`CheckUrlTool`): it resolves the
  name, refuses any address that is not public (`PublicAddresses`) and connects only to the addresses it checked. So
  this decision does not add the first client that calls hosts a page names; it adds the first one the Scout runs on
  its own, with no administrator watching the run.

## Decision

### What is followed
1. **Only on a list page of a directory host whose switch is on**
   ([0055](ADR-0055-directory-lists.md) point 1), and only for an entry with no link that points at its item.
   `ListLinkFollower.canBeFollowed` takes two kinds of link: a link to a page of the list's own host (not the list,
   not the front page), and a link `ProductLinks` calls a redirect. The words of the link must name the item:
   "website" or "here" names nothing, and such a link is not followed.
2. **At most 3 steps for an entry** (`MAX_STEPS`). A step is one request made for the entry:
   - a read of the directory's page of the item, through the page reader (1 credit);
   - a status request of our own for an address that redirects (point 8);
   - a read of the page reached, through the page reader (1 credit), when its address alone does not decide.
   After 3 steps without the page of the item, the entry gives nothing.
3. **One link of the directory's page.** Of the Markdown of the page of the item, `linkToTheItem` takes the first
   link whose address carries the name of the entry, else the first whose words say it goes to the site ("Visit
   website", "Open", the name itself). At most 500 links are looked at, and at most one page of the directory is read
   for an entry: a link to another page of a directory host gives nothing.
4. **Every address passes the host check again** (`ProductLinks.page`, the Scout's `WebAddresses`): https, a public
   name, no IP address, no private or reserved name, no punycode, no user info, port 443 only; not a shadow library.
   This runs for the link of the list, for every link taken from a page and for every `Location`, before any request.

### The check that the page is the item's
5. **Code first** (`ItemPageCheck`). The page reached is the item's own when the name of the entry is
   - a whole part of its address: a label of the host, the host written in one word (`acme.ai` for "Acme AI"), or a
     segment of the path. On a host many items share (GitHub, Hugging Face, arXiv, npm) only the path counts;
   - or in its title: the words of the name, next to each other and in order, in the first heading of the Markdown
     (the reader gives no other title).
   A name shorter than 3 letters and digits proves nothing. Code says yes or cannot decide; it never says no. The
   refusals by code are the host rules of point 4 and a directory host.
6. **A model only where code cannot decide** (`SpringAiItemPageJudge`, port `ItemPageJudge`). It is the fast model
   the awesome-list classifier uses (`helper2.llm.fast-model`), through the same client, budget counter, metrics and
   circuit (`TextCall`, `BudgetedChat`, policy `llm-chat`). No provider and no model is added.
   - It gets a fixed instruction (`prompts/sources/check-item-page.txt`), the name (at most 200 characters, on one
     line) and at most 2,000 characters of the page text, cleaned by `UntrustedPageText` and fenced in `<page>`.
   - Only the one word counts: `yes` or `no`, after trimming and in any case. A sentence, a reason, JSON, two words
     or no answer is no answer. A no and no answer give nothing.
   - It writes no text a user sees and no field of a resource: its only effect is that an address is taken or not.
     So it carries no AI label ([0033](ADR-0033-marking-ai-written-text-research-trail.md)).
   - A page on GitHub and a page on a host that is never read are not read for the check: there the address alone
     decides.
7. **What following gives is an address.** `withItems` adds the address found to the links of the entry (a GitHub
   repository to its entries). From there the rule of its kind decides ([0013](ADR-0013-discovery.md) point 17):
   a repository, a model, a paper and a package are asked of their source by id; a product's site, a course and an
   article become candidates in the review queue. Nothing enters the catalog from a link alone, and no fact of the
   item comes from the directory.

### The status request of our own
8. **One small outbound adapter** (`LivePageStatusReader`, port `PageStatusReader`). `status(address)` answers
   `Answered(code, location)` or `Refused(reason)`. The code also says that a page is gone (404, 410); the follower
   uses the `location`.
   - **A status only.** HEAD; GET only when the site answers HEAD with 405 or 501, and then no body is read: the
     connection is dropped after the headers (`PinnedTransport.drop`). No HTML is parsed. The content of a page comes
     only through the page reader.
   - **No automatic redirect.** The HTTP client follows none; a `Location` is an answer, and the follower takes it as
     the next step, after the host check of point 4.
   - **The resolved address is checked, and pinned.** Every address the name resolves to must be public
     (`PublicAddresses`: not private, loopback, link-local, carrier NAT, multicast, the metadata address
     169.254.169.254, nor an IPv6 form that carries one of them). The request connects only to the addresses that
     were checked (`PublicAddresses.pinned`), so a DNS answer that changes between the check and the connect cannot
     reach an internal address. The egress rule of the chart is a second wall: port 443 only, the private ranges and
     169.254.0.0/16 left out (`networkPolicy.egress`). The chart does not change.
   - **Nothing of ours is sent.** No cookie (cookie handling is off), no credential, no referrer. The User-Agent is the
     one of every outbound call, `Helper2.ai-metasearch/<version> (+https://helper2.ai/sources)`.
   - **Short and small.** 3 s to connect, 5 s for an answer, 8 s for the whole call; at most 100 header lines of 8 KB;
     one try, no retry. TLS verification stays on.
   - **Resilience.** The call runs in the policy `page-status` ([0006](ADR-0006-outbound-calls-and-resilience.md)):
     a circuit over all sites, opened when half of the last 10 requests gave no answer. An HTTP status is an answer
     and never counts as a failure.
   - **robots.txt is honoured** for the path asked (`RobotsRules`, RFC 9309: the group that names helper2.ai, else
     the group for every robot; the longest rule decides; `*` and `$`). It is read with the same transport (at most
     512 KB), kept one day per host in memory, and counts on the caps. A site with no robots.txt (404, 410) states no
     rule. A robots.txt that cannot be read (another status, a time-out, a redirect to another site) allows nothing.
     A redirect to the same site with or without `www.` is read once.
   - **Caps, counted per try before the request.** `status-requests-per-day` (200) and
     `status-requests-per-host-per-day` (30), robots.txt included, in the daily quota counter of
     [0027](ADR-0027-live-web-store-first-daily-quota.md). When the counter cannot be read, no request is made.
   - **Never a host that is never read** (`HostsNeverRead`), and only from the follower: only for the links of lists
     whose reading an administrator turned on and the addresses they lead to.

### Budgets
9. **Steps a day.** `web-pages.follow.steps-per-day` (`H2_SCOUT_AWESOME_WEB_PAGES_FOLLOW_STEPS_PER_DAY`, 100), counted
   per step before the request. 100 is the number of pages of an item the rating reads a day
   ([0056](ADR-0056-directory-rating.md) point 9): one more reading task of the same size on the same hosts. With 1
   to 3 steps an entry, 100 steps follow 33 to 100 entries a day; the 10 list reads a day of
   [0055](ADR-0055-directory-lists.md) are followed over several days. 0 follows no link.
10. **Credits.** A read costs one credit of the Scout's daily cap (`H2_SCOUT_DAILY_CREDITS`, 1,000), counted on
    `helper2.live-web.credits{feature="scout-follow-links"}`. At most 100 a day, when every step is a read. When the
    cap is spent, the links wait.
11. **Model calls a day.** `web-pages.follow.check-calls-per-day`
    (`H2_SCOUT_AWESOME_WEB_PAGES_FOLLOW_CHECK_CALLS_PER_DAY`, 50), a `DailyBudget` of its own (`follow-check`). 50,
    because a model call needs at least 2 steps before it (a step that leads to the page, and the read of the page),
    so 100 steps cannot ask more than 50 times. 0 asks no model; then a page code cannot decide gives nothing.
12. **Status requests.** 200 a day in all: at most 100 steps, and the robots.txt of at most 100 new hosts. 30 to one
    host a day: a directory whose every "Visit" link is a redirect on its own host is asked about once an hour on
    average, and its other entries wait for the next day. `H2_SCOUT_AWESOME_WEB_PAGES_FOLLOW_STATUS_REQUESTS_PER_DAY`,
    `…_PER_HOST_PER_DAY`.
13. **The pass.** Every read counts on the time and size budget of the collector's pass. When a cap, the credits or
    the pass budget is spent, the link is not recorded as tried and waits for a later pass.

### What is kept
14. **With the last read of the list.** `list_seed_last_read.followed` (`V2026.11.9.4__list_seed_followed.sql`): for
    each link followed, the link, the address found or nothing, the number of steps and who decided (`code`,
    `model`). A link in it is not followed again. A new read of the list (7 days) starts it empty. It goes with the
    list (`ON DELETE CASCADE`). No page text, no answer of the model and nothing about a person is kept.
15. **One read of a page of an item in a pass.** The follower puts the Markdown of each directory page it read in
    `ItemPageReads`, kept for the pass that runs. `DirectoryRatingReader` takes the rating from it and does not read
    the page again; that counts as a try of the rating and costs no credit.

### How often each path is taken
16. **Expected, not measured.** No directory host is turned on, so no real page was followed. The expectation, from
    how directories commonly write their pages:
    - most entries are decided by code on the address (a product named like its domain): about 7 of 10;
    - some by code on the title, after a read of the page reached: about 2 of 10;
    - the rest go to the model (a product whose site and title carry another name: "Bard" at `gemini.google.com`),
      or give nothing: about 1 of 10.
    These figures are an estimate to be replaced by the metrics of point 17. A check by code alone is the fallback
    by a setting (`check-calls-per-day` 0).

### Metrics
17. Per directory host of the list (`host`; the hosts a link leads to are not a label: they are not bounded):
    - `helper2.scout.follow.links{host}`: links followed;
    - `helper2.scout.follow.steps{host,kind}`: steps, `kind` `item-page`, `status` or `own-page`;
    - `helper2.scout.follow.decided{host,by,answer}`: `by` `code` (answer `yes`) or `model` (`yes`, `no`, `none`);
    - `helper2.scout.follow.nothing{host,reason}`: links that gave nothing, with the reason (`steps-spent`,
      `no-link-to-the-item`, `no-redirect`, `directory-host`, `model-no`, a refusal of the host check, …);
    - `helper2.scout.follow.requests{host,outcome}`: status requests, `outcome` the status class (`2xx` to `5xx`) or
      the refusal (`host-check`, `never-read`, `private-address`, `robots`, `daily-cap`, `host-cap`, `timeout`,
      `unavailable`);
    - `helper2.scout.follow.waits{reason}`: a pass stopped following (`steps-daily-cap`, `credit-cap`, a budget);
    - `helper2.scout.follow.check.budget.remaining`: model calls left today. The model calls also count on the
      model metrics of every call, step `check-item-page`.
18. **Runbook.** After a host is turned on and a week of passes:
    `sum by (host, by, answer) (increase(helper2_scout_follow_decided_total[7d]))` against
    `sum by (host) (increase(helper2_scout_follow_links_total[7d]))` gives the share of each path;
    `increase(helper2_scout_follow_steps_total[7d]) / increase(helper2_scout_follow_links_total[7d])` the steps a
    link. A host where the model decides most links, or where most links give nothing, is looked at by hand.

### Code
- `backend/libs/schema/src/main/resources/db/migration/V2026.11.9.4__list_seed_followed.sql`.
- `backend/apps/backoffice/src/main/kotlin/ai/helper2/scout/PageStatus.kt`, `ListSeeds.kt` (`ListReading.Followed`);
  `scout/internal/JdbcListSeedStore.java`.
- `backend/apps/backoffice/src/main/java/ai/helper2/sources/github/scout/ListLinkFollower.java`,
  `ItemPageCheck.java`, `ItemPageReads.java`, `WebPageLists.java` (`followed`), `DirectoryRatingReader.java`.
- `sources/classifier/ItemPageJudge.java`, `SpringAiItemPageJudge.java`;
  `backend/apps/backoffice/src/main/resources/prompts/sources/check-item-page.txt`.
- `sources/pagestatus/LivePageStatusReader.java`, `PinnedTransport.java`, `RobotsRules.java`;
  `sources/research/PublicAddresses.java` (`pinned`).
- `sources/config/scout/CollectorsConfiguration.java`;
  `backend/libs/core/src/main/java/ai/helper2/sources/config/CollectorProperties.java` (`WebPages.Follow`);
  `backend/libs/platform/src/main/resources/helper2-shared.yaml`.
- Tests: `ListLinkFollowerTest`, `ItemPageCheckTest`, `SpringAiItemPageJudgeTest`, `LivePageStatusReaderTest`,
  `PinnedTransportTest`, `RobotsRulesTest`, `DirectoryRatingsTest`, `JdbcListSeedStoreIT`.

## Alternatives considered

### Who says that the page is the item's
| | Code first, a model where code cannot decide (chosen) | Code alone | A model for every page |
|---|---|---|---|
| What it is | Name in the address or the title; else one yes/no call | Name in the address or the title; else nothing | One yes/no call for each page reached |
| Simplicity | Two checks, one port, one prompt | One class, no prompt | One prompt, every page read |
| Coupling | To how sites name themselves, and to the fast model | To how sites name themselves | To the model for every entry |
| Testability | Unit tests on names; the parse and the fence of the prompt; no test says the model is right | Unit tests only, fully deterministic | The verdicts cannot be unit tested |
| Operational cost | 0 for an address match; 1 credit for a title; at most 50 model calls a day | The same without the calls | A read and a call for every entry |
| Failure modes | A wrong yes of the model proposes a wrong candidate to a person, or asks a source for a wrong id | A product under another name gives nothing | The same wrong yes, far more often |
| Security | The model reads untrusted text; its answer is one bit | No model reads a page | The largest prompt-injection surface |
| Reversibility | `check-calls-per-day` 0 turns the model off | — | Remove the port |
| Load for one person | A share to watch per host | The least | A model to evaluate |

- **Code alone** is the honest baseline, and it is expected to decide about 9 of 10 entries. It cannot decide a
  product whose site and title carry another name, a sub-brand under a company site, or a paper at arXiv (an id in
  the address, the title in another wording). Whether that tenth is worth a model is not measured. The model is kept
  behind its own cap, off by one setting, so that the metrics of point 17 answer the question on real pages.
- **A model for every page** pays a read and a call where the address already says it, and lets untrusted text speak
  for every entry.
- **Why this is not the model [0013](ADR-0013-discovery.md) rejected.** That ADR left out "a model that reads
  untrusted pages before anything is checked": a model that sorts results, picks categories or maps headings, with
  values that need an AI label. Here the order is the other way. Code runs first: the switch of the host, the host
  check of every address, the directory and shadow-library rules, the name in the address and in the title. The model
  sees a page only after all of them, only when they could not decide, and can add nothing but one bit. Its yes writes
  no value: the address then goes through the rule of its kind, where a source confirms by id or a person reviews.
  A wrong yes is bounded by those rules; a wrong no loses one entry.

### How a redirect is followed
| | A status request of our own (chosen) | The page reader | Parse the address inside the link | No redirect followed |
|---|---|---|---|---|
| What it is | HEAD, the `Location` as the next step | Read the redirect address through Tavily | Take the URL in `?url=` | A redirect link gives nothing |
| Works for | Every HTTP redirect | Nothing: no field names where it landed | Only links that carry the target | — |
| Security | A new outbound path to sites a page names: SSRF, DNS rebinding, robots, load on a site | No new path | The target is what the link claims, not where it leads | None |
| Operational cost | No credit; caps per day and per host | 1 credit a try | None | None |
| Load for one person | An adapter with eight controls to keep | — | A parser of tracking formats | — |

- **The page reader** cannot follow a redirect (Context). **The address inside the link** is a claim of the page: a
  hostile page writes one target in the query and redirects to another. **No redirect followed** keeps the directories
  that link through `/out` out of reach, which is most of the case this story is for.
- **JavaScript and `<meta refresh>` redirects are not followed**: they need the body, and the status request reads
  none.

### Where the result is kept
- **In the last read of the list (chosen)**: one column, the life of the read it belongs to, no new table.
- **A table per followed link**: could keep a link across reads of the list, but needs its own retention rule and a
  join in every pass.
- **Not kept**: every pass would follow every link again, at 1 to 3 steps each.

## Consequences

- A list of a directory that links only its own pages gives resources: repositories, models, papers and packages by
  their source, products, courses and articles as candidates. More review items for the administrator, within the
  daily cap of the collector.
- An entry is rated ([0056](ADR-0056-directory-rating.md)) once following gave it a resource: the entry then names
  one resource and links one page of the directory.
- More spend, at the caps: 100 live web credits a day inside the Scout's 1,000; 50 calls of the fast model a day;
  200 status requests a day, 30 to one host.
- Requests of our own reach sites of third parties. They see the address of our server and our User-Agent. The
  Privacy page and [data processing](../data-processing.md) say so.
- [0013](ADR-0013-discovery.md) point 17 and [0055](ADR-0055-directory-lists.md) still skip a redirect and a
  directory link everywhere else: on a GitHub list, on a web page that is not on a directory host, and on a directory
  host whose switch is off.

### Known limits
- The paths are not measured (point 16). The words that mark the link to the site (`SITE_WORDS`) and the rule of the
  name are written from how such pages commonly look.
- A link whose words name nothing ("Visit", "Website") is not followed: the entry has no name to check a page
  against.
- The title is the first heading of the Markdown, not the `<title>` of the page: the reader gives no HTML.
- A redirect by JavaScript or `<meta refresh>`, and a page that needs a sign-in or shows a bot wall, give nothing.
- The page reached may itself redirect when a person opens it; the address taken is the one the chain of `Location`
  headers ended on within 3 steps.
- A directory controls both the name and the link of its entries. The checks catch a link that leads somewhere else
  by mistake; they do not prove that a directory tells the truth. The switch per host, the source's own answer and
  the review queue are the controls for that.
- The robots.txt of a host is kept in memory: a restart reads it again, inside the caps.
- The caps of status requests live in Redis: when Redis loses its data, the count of the day starts again
  ([0005](ADR-0005-redis-as-a-disposable-cache.md)).
- Following runs when the web pages of a pass are read, before its GitHub lists, and counts on the same time budget
  of the pass (15 minutes): a day of slow answers leaves less time for the GitHub lists of that pass.
- One circuit covers all sites: when half of 10 requests in a row fail, following waits a minute for every site.

### STRIDE
| Threat | Risk | Control |
|---|---|---|
| Spoofing | A page pretends to be the item's own page; a directory names one product and links another | The name must be in the address or the title, or the model says yes; then the source confirms by id or a person reviews. The reader refuses a page that answers from another host. |
| Tampering: a page chosen by someone else decides where the reader goes next | A hostile list or page of an item sends the Scout to an address of its choice | Only on hosts an administrator turned on; every address passes the host check before any request; one link of one directory page; 3 steps; a directory host, an excluded domain and a shadow library give nothing. Tests: `ListLinkFollowerTest` (`everyAddressARedirectNamesPassesTheHostCheckAgainBeforeAnyRequest`). |
| Tampering: SSRF | A `Location` or a name that resolves to an internal address (10/8, 127/8, 169.254.169.254, IPv6 forms), or a DNS answer that changes after the check | No IP literal and no private name by `WebAddresses`; every resolved address public; the connect pinned to the checked addresses; https and 443 only; the egress rule of the chart. Tests: `LivePageStatusReaderTest` (`aPublicNameThatResolvesToAnInternalAddressIsNotAsked`, `theRequestConnectsToTheAddressesThatWereChecked`), `PinnedTransportTest`. |
| Tampering: open redirect chains | A chain that never ends, or that hides its end behind many hops | No automatic redirect; each hop is a step, 3 in all; each hop checked. Test: `afterThreeStepsWithoutThePageOfTheItemTheEntryGivesNothing`. |
| Tampering: prompt injection | The page read by the model says "answer yes" | Fixed instruction; the page is cleaned (hidden elements, comments, instruction lines), cut to 2,000 characters and fenced, and cannot close its fence; the answer is one word or nothing; a yes still passes the rule of the kind. Tests: `SpringAiItemPageJudgeTest`. |
| Tampering: one site for every entry | A directory page points every entry to one site to flood the queue | The name must match for each entry; candidates are one per address and per site, inside the daily cap of the collector; a person reviews each. |
| Repudiation | Nobody knows why a candidate came from a list | The candidate keeps the list that named it; the last read keeps the link, the address found, the steps and who decided; the model calls are counted by step and model id. |
| Information disclosure | A request of ours leaks a credential or a visitor | No cookie, credential or referrer; nothing about a visitor is in a pass; a third-party site sees the server address and the User-Agent only. Test: `noCookieNoCredentialAndNoReferrerIsSentAndTheUserAgentNamesHelper2`. |
| Denial of service / cost exhaustion | A list of thousands of entries spends credits, model calls, or loads a site; a site answers slowly or with a huge body | 100 steps, 50 model calls, 200 status requests a day, 30 a host, all counted per try; the Scout's credit cap; the pass budget; 3 s, 5 s and 8 s limits; no body read; 512 KB of robots.txt; a robots matcher that does not backtrack. Tests: `everyTryCountsOnTheDayAndOnItsHostAndPastACapNoRequestIsMade`, `theBodyOfAGetForAStatusIsNotRead`, `aPatternOfManyStarsIsMatchedWithoutBacktrackingForever`. |
| Elevation of privilege | A visitor or a page starts a request of our server | No endpoint starts following; only the pass of the collector does, for hosts an administrator turned on. |

### AI architecture check
| # | Layer | State |
|---|---|---|
| 1 | Model | Covered: the fast model already configured (NVIDIA on Nebius), one yes/no task. When the provider is down or the budget is spent, no answer: the entry gives nothing. |
| 2 | Agent Framework | Deferred, safe: no agent, no tool calling; one call with a fixed prompt. |
| 3 | Orchestration | Covered: the pass of the awesome-list collector; a link that waits is followed at a later pass; a link tried is not tried again until its list is read again. |
| 4 | Tool & API | Covered: the page reader, and one new outbound adapter with a status-only contract. The model has no tool. |
| 5 | Memory | Deferred, safe: nothing about users; the model keeps nothing between calls. |
| 6 | Knowledge & RAG | Deferred, safe: the check is not retrieval and the guide does not read it. |
| 7 | Data | Covered: one column in one migration; what is kept and until when is stated (point 14). |
| 8 | Browser & Computer Use | Covered: pages only through the page reader; our own request reads a status, never a page; 3 steps. |
| 9 | Guardrails | Covered: the switch, the host check per step, code before the model, the strict parse, the rule of the kind after. |
| 10 | Observability | Covered: the metrics of point 17; the model id and prompt version on every model call. |
| 11 | Evaluation | Gap, deferred: no evaluation set of real pages exists, because no host is on. The condition is the runbook of point 18; an adversarial set for the prompt is a unit test of its fence and parse only. |
| 12 | Deployment | Covered: five settings with defaults, one migration, no chart change. Rollback: 0 steps a day. |
| 13 | Security & Identity | Covered: no new secret, no new endpoint; the STRIDE table. |
| 14 | Human-in-the-Loop | Covered: an administrator turns each host on; products, courses and articles wait for a person. |
| 15 | Cost & Reliability | Covered: the caps of points 9 to 12, the circuit of `page-status` and of `llm-chat`. |

## Diagrams
[flow](../diagrams/flow-0057-follow-list-links.svg) · [sequence](../diagrams/sequence-0057-follow-list-links.svg)

## Backlog
- Decides: [S2.3.20](https://github.com/silvioincalza/helper2.ai/issues/980), in
  [F2.3](https://github.com/silvioincalza/helper2.ai/issues/33).
- The status code of `PageStatusReader` is what "a web page list its site says is gone" needs
  ([S2.3.24](https://github.com/silvioincalza/helper2.ai/issues/998)).
