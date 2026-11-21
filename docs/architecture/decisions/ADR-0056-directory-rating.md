# 0056 — A rating from the votes directories state

**Depends on:** [0007](ADR-0007-configuration-secrets-runtime-settings.md), [0010](ADR-0010-data-retention.md), [0013](ADR-0013-discovery.md), [0025](ADR-0025-ranking.md), [0027](ADR-0027-live-web-store-first-daily-quota.md), [0038](ADR-0038-back-office-and-review.md), [0041](ADR-0041-site-pages-for-crawlers-policy-endpoint.md), [0046](ADR-0046-evaluation-guide-and-search.md), [0055](ADR-0055-directory-lists.md)

**Requirements:** NFR-005, NFR-012, NFR-015, C-008, C-012, C-014

**Use cases:** UC-030 (reading), UC-007 (the public page), UC-024 (the back office), UC-009 (the report), UC-001 (the ranking)

## Context

A product, a course or an article has no stars and no downloads. The catalog can say how many lists name it
([0055](ADR-0055-directory-lists.md)) and nothing else about how people judge it.

A directory host often prints an aggregated rating next to an item: an average and the number of votes behind it.
The Scout already reads the list pages of the directory hosts an administrator turned on, through the live web page
reader. That reader gives the text of a page as Markdown. It gives no HTML and no structured data, so a
`schema.org` `AggregateRating` block is out of reach; what a page prints as text is in reach.

No read of a real list page of a directory host is stored yet: no host has its switch on. So the phrasings the code
recognises are written from how such pages commonly print a rating, and are not measured on real pages.

## Decision

### What is read
1. **Three figures, or nothing.** `StatedRatings.in` reads a rating from text only when the text states all three:
   the average, its scale and the number of votes ("4.6/5 (1,234 reviews)", "4.6 out of 5 based on 800 reviews",
   "9.1/10 from 45 votes"). A figure with no stated scale is dropped. A count of upvotes or likes is not a rating and
   is left out: no rule maps a count of approvals to an average. Code only, no model.
2. **Conservative patterns.** The average is a number followed by `/` or "out of" and 5, 10 or 100. On 5 and 10 it
   must have a decimal: a whole number out of 5 is a step or a page ("3/5") far more often than an average. The
   count is a number (with thousands separators or a `k`) followed by "reviews", "ratings" or "votes". The two stand
   at most 40 characters apart. Markdown emphasis is taken out first. A text that states two different ratings gives
   nothing: the code does not choose.
3. **Range checks.** `StatedFigures.checked` keeps figures only inside their ranges: the scale is 5, 10 or 100, the
   average is above 0 and at most its scale, the count is from 1 to 100,000,000. Anything else is dropped. The
   table refuses the same values (`CHECK`), and the read side checks again.
4. **No review text, no name.** Only the three numbers are taken. The text of a review and the name of a reviewer
   are never read as such and never kept.

### Where the figures come from
5. **The list page first.** For each entry of a list page of a directory host that names exactly one resource,
   `DirectoryRatingReader` reads the figures in the text of the entry (at most 500 characters,
   [0013](ADR-0013-discovery.md) point 17). This costs nothing: the text is the last read of the list.
6. **Then the directory's page of the item.** When the entry states no figures and links to exactly one page on the
   host of the list that is neither the list nor the front page (`ListEntryLinks.directoryItemPage`), that page is
   read through the live web page reader (`WebPageReader`, the reader of list pages). Only the three figures are
   taken for the rating and nothing else of the page is kept. A page the following of links read in the same pass
   ([0057](ADR-0057-follow-list-links.md) point 15) is not read again: its figures are taken from that read. A page that states two different ratings
   (the item and its alternatives) gives nothing.
7. **Only where an administrator turned the reading on.** A page of an item is read only when the switch of its host
   is on ([0055](ADR-0055-directory-lists.md) point 1), the host is not one never read, the page reader is on and
   the resource the entry names is in the catalog. The address passes the link rules: https, a public host name, no
   redirect ([0013](ADR-0013-discovery.md) point 17).
8. **A visit never starts a read.** The figures are read in the pass of the awesome-list collector and nowhere else.
9. **A budget of its own.** At most `web-pages.item-page-reads-per-day` pages of an item a UTC day
   (`H2_SCOUT_AWESOME_WEB_PAGES_ITEM_PAGE_READS_PER_DAY`, 100), apart from the cap of list reads. Every try counts,
   whatever it gives: the try is written before the read (`DirectoryRatingIndex.tried`) and the tries of the day are
   counted in PostgreSQL. A read costs one credit of the Scout's daily cap; when that cap is spent, the pages wait.
10. **No more often than its list.** A page of an item is tried again after `H2_SCOUT_AWESOME_WEB_PAGES_EVERY`
    (7 days), read or failed. A failed read keeps the figures of the last read.

### What is kept
11. **One table.** `directory_rating` (`V2026.11.9.3__directory_rating.sql`, owned by the catalog, read-only for the
    public app): the address of the list, the resource, the host, the address of the page that states the figures
    (the list page or the page of the item), the average, the scale, the number of votes, the time of the read and
    the time of the last try. One row per list and resource. A row with no figures records a page of an item that
    was tried and stated none.
12. **Kept until the page is read again.** `DirectoryRatingIndex.replace` runs at each pass, with what the list
    names: a row goes when the list no longer names the resource; the figures of the list page go when the page no
    longer states them; the figures of a page of an item stay until that page is read again. A row goes with its
    resource (`ON DELETE CASCADE`). No period deletes a row. The records of a list that is gone are handled by
    [S2.3.22](https://github.com/silvioincalza/helper2.ai/issues/992).
13. **Nothing about a person.** An average and a count are not about a person. No account, no visitor data and no
    reviewer is in the table.

### One rating
14. **A Bayesian average with a stated prior** (`DirectoryRating.of`):
    `rating = (C × m + Σ votes × average) / (C + Σ votes)`, with `m = 3.0` (the middle of the scale) and `C = 20`
    votes. Each average is first put on the scale from 1 to 5 (`average × 5 / scale`, 1 the lowest). So 5.0 from 2
    votes gives 3.2 and 4.6 from 800 votes gives 4.6. The prior is a stated choice, not a measured one: the middle of
    the scale claims nothing about a resource, and 20 votes is where a stated average starts to count as much as the
    prior.
15. **Several directories, one rating.** The sum runs over the directories, each with its own votes. Two lists of
    one host count once, with the figures read last.
16. **Shown with one decimal** (`shownScore`), with the number of directories.
17. **Not AI-written.** The rating is a figure a source stated and a formula computed. It carries no AI label and no
    `data-ai-*` mark, and `aiContent` never names it ([0033](ADR-0033-marking-ai-written-text-research-trail.md)).

### Where it shows
18. **The public page.** `GET /api/v1/resources/{id}`: `rating {score, directories}`, or null. The page shows
    "4.7 out of 5 from 2 directories" in a section "Rating from directories", and "Not rated yet" when no directory
    states figures (NFR-005: no invented figure). The names and addresses of the directories are never given.
19. **The page for crawlers.** `PageHtml` gives the same values as a fact: "Rating: 4.7 out of 5, from 2
    directories" or "Rating: Not rated yet" ([0041](ADR-0041-site-pages-for-crawlers-policy-endpoint.md)). No
    `AggregateRating` is written into the page's own structured data: the votes are not votes of helper2.ai's
    visitors.
20. **The back office names them.** `GET /api/v1/admin/review-items/{id}/evidence`: `directoryRatings`, each with
    the host, the page, the average, the scale, the votes and the time of the read. The review page links each page
    (https only, `rel="noopener noreferrer nofollow"`).
21. **A wrong rating is reported like any wrong detail.** The section has a link "Report a wrong rating": it opens
    the report form of the page with the detail "rating" filled in. The report follows UC-009 with no change to its
    API.

### Ranking
22. **In the formula, with weight 0.** `Ranking.score` adds `weight × (rating − 3) / 2`: from `−weight` (rated 1) to
    `+weight` (rated 5). A result that is not rated yet counts as 3, so it adds 0 and is not ranked lower for it.
    The weight is `helper2.search.ranking.rating-weight` (`H2_SEARCH_RANKING_RATING_WEIGHT`), 0 by default and at
    most 0.008, the weight of the popularity ([0025](ADR-0025-ranking.md) point 6). With the weight 0 the ratings
    are not read at all (`ResultRatings`).
23. **Why 0, and why a setting.** The evaluation set (`eval/`) has no rated resource: no directory is read yet. A
    weight cannot be measured, so none is set. The other weights stay in the code ([0025](ADR-0025-ranking.md) point
    5); this one is a setting so that the measurement can be run against production data without a release. The
    condition to raise it: a recording with rated resources in the first ten results, a variant in `RankingVariants`
    with the weight, and its ΔnDCG@10 with the interval of [0046](ADR-0046-evaluation-guide-and-search.md), before
    and after. With the weight 0 the replay of the recorded set (131 queries, 50 judged) gives nDCG@10 0.378, the
    value of the baseline: the order does not change. The Settings page shows the weight read-only in the group
    "Ranking".

### Metrics and the measurement of the patterns
24. `helper2.scout.ratings.pages{host,page}` counts the pages read (`page` is `list` or `item`) and
    `helper2.scout.ratings.found{host,page}` the ratings they stated. `helper2.scout.ratings.skips{reason}` counts
    what waited (`item-daily-cap`, `item-credit-cap`, `item-unavailable`). A read counts on
    `helper2.live-web.credits{feature="scout-item-pages"}`.
25. **Runbook.** After an administrator turns a host on and a pass has run, compare
    `sum by (host, page) (increase(helper2_scout_ratings_found_total[7d]))` with
    `sum by (host, page) (increase(helper2_scout_ratings_pages_total[7d]))`. A host with pages read and nothing
    found prints its rating in a phrasing the patterns do not know, or prints none: open one of its pages and
    compare. The patterns are extended only from such a measurement, each with a test that quotes the phrasing.

### Code
- `backend/libs/schema/src/main/resources/db/migration/V2026.11.9.3__directory_rating.sql`.
- `backend/libs/core/src/main/java/ai/helper2/catalog/StatedFigures.java`, `DirectoryRating.java`,
  `DirectoryRatingIndex.java`, `ResourceRatings.java`, `ResourceDetail.java`;
  `catalog/internal/JdbcDirectoryRatingIndex.java`, `JdbcResourceRatings.java`, `CatalogResultRatings.java`;
  `catalog/usecase/GetResourceDetail.java`.
- `backend/apps/backoffice/src/main/java/ai/helper2/sources/github/scout/StatedRatings.java`,
  `DirectoryRatingReader.java`, `ListEntryLinks.java` (`directoryItemPage`), `GitHubAwesomeCollector.java`
  (`ratedEntries`), `WebPageLists.java`; `scout/internal/ScoutConfiguration.java` (`listIndex`);
  `backend/libs/core/src/main/java/ai/helper2/sources/config/CollectorProperties.java` (`WebPages`).
- `backend/libs/core/src/main/kotlin/ai/helper2/search/Ranking.kt` (`ResultRatings`, `ratingSignal`),
  `search/config/SearchProperties.java` (`RankingSignals`), `SearchConfiguration.java`,
  `search/internal/RankingSettingsConfiguration.java`.
- `backend/apps/public/src/main/java/ai/helper2/catalog/web/ResourceDetailController.java`,
  `site/pages/Pages.kt` (`facts`), `site/pages/usecase/RenderPage.java`;
  `backend/apps/backoffice/src/main/java/ai/helper2/admin/usecase/GetReviewEvidence.java`,
  `admin/web/ReviewController.java`; `api/public.yaml`, `api/admin.yaml`.
- `frontend/src/features/resource/DirectoryRating.tsx`, `ResourcePage.tsx`, `ReportForm.tsx`,
  `frontend/src/admin/ReviewEvidence.tsx`, `frontend/src/i18n/locales/en.json`, `en.admin.json`, `en.legal.json`.
- Tests: `StatedRatingsTest`, `StatedFiguresTest`, `DirectoryRatingTest`, `DirectoryRatingsTest`,
  `JdbcDirectoryRatingIndexIT`, `ResourceFoundInApiIT`, `RankingTest`, `RankingSettingsConfigurationTest`,
  `PagesTest`, `ResourcePage.test.tsx`, `ReviewEvidence.test.tsx`.

## Alternatives considered

### The source of the figures
| | The text the page reader gives (chosen) | An own fetcher of the HTML and its JSON-LD | Another reading service that returns HTML | The API of each directory |
|---|---|---|---|---|
| What it is | Patterns over the Markdown of a page already read | Fetch the page, read `AggregateRating` | A second provider | One adapter per directory |
| Simplicity | One parser, the reader exists | A fetcher, robots, a parser of HTML | A new client and secret | An adapter and a token each |
| Coupling | To how pages print a rating | To each page's markup, less to wording | To a second provider | To each API |
| Testability | Unit tests on phrasings | Fixtures of HTML | Fixtures of the provider | Fixtures per API |
| Operational cost | 0 for a list page; 1 credit a page of an item, at most 100 a day | No credit; an egress rule, bot walls | A price per page | Tokens to keep |
| Failure modes | A phrasing it does not know gives nothing | Blocked reads, layout changes | The provider is down | An API changes |
| Security | No new call target | A new outbound path: SSRF, DNS rebinding, redirects | The risk moves to the provider | A new target per directory |
| Reversibility | 0 pages a day turns the reads off | Remove the fetcher | Remove the provider | Remove the adapter |
| Load for one person | Patterns to extend after a measurement | A fetcher to keep alive | A second bill and contract | High |

- **An own fetcher** is the only way to the structured data, where the scale and the kind of count are stated. It is
  a new outbound path from the cluster, and many directories answer a plain fetch with a bot wall.
- **Another service** adds a processor and a price before anything is measured.
- **The API of each directory** covers few hosts.

### The formula
| | Bayesian average, prior 3.0 with 20 votes (chosen) | Plain mean weighed by votes | Lower bound of a confidence interval |
|---|---|---|---|
| 5.0 from 2 votes | 3.2 | 5.0 | Not computable from what a page states |
| 4.6 from 800 votes | 4.6 | 4.6 | Not computable from what a page states |
| Needs | The average and the count | The average and the count | The spread of the votes, which no page states |
| To explain | "Few votes stay near 3" | Simple | Hard |

- **The plain mean** lets 5.0 from 2 votes beat 4.6 from 800.
- **A lower confidence bound** (Wilson, or a normal bound) needs the spread of the votes or the share of each star;
  a page states neither, so the variance would be an invented figure.
- **A prior from the catalog's own mean** would be measured, not chosen, and is the better prior once there are
  ratings. Today there are none.

### Upvotes
- **Map a count of upvotes to a rating** (for example by its rank on the host): the result would be a figure no
  source stated (NFR-005). Left out.

### The item pages
- **Only the list page**, no page of an item: no credit and no change to what is read, but a directory that prints
  the rating only on the page of the item gives nothing.
- **One call for five pages** (the reader takes up to 5 addresses a call for one credit): a fifth of the credits,
  but one failed page fails the call and the host check runs per page. Left for a measured need.

### The weight in the ranking
- **A weight in the code like the others**: one source for the evaluation, but the weight could then be measured
  only after a release that already ranks by it.
- **No rating in the ranking until measured**: nothing to ship now, and the measurement would need a change of the
  formula first.

## Consequences

- A resource a directory rates shows one rating from 1 to 5 and the number of directories. Every other resource
  page shows "Not rated yet".
- The score is not the average a directory prints: 4.6 from 5 votes shows 3.3. The page says that few votes count
  little.
- One more thing is read: the directory's page of an item, for the three figures only. UC-030 BR-006 says so.
- More credits: at most 100 a day, inside the Scout's daily cap of page and search credits. On a day the cap is
  spent, the pages of items wait.
- More rows: one per list and resource that has figures or a tried page of an item.
- The Privacy page says what is read and kept, and how the rating is made.
- The ranking does not change: the weight is 0.
- The weight of the rating is the one ranking weight outside the code. The evaluation must replay it by name.

### Known limits
- The patterns are not measured on real pages. A directory that prints its rating in another phrasing, in an image
  or only in structured data gives nothing.
- An entry is rated only when it names exactly one resource. A list whose entries link only to the directory's own
  pages names no resource until its link is followed to the item's own page
  ([0057](ADR-0057-follow-list-links.md)).
- The figures are read from the first 500 characters of an entry.
- A page of an item that also shows the ratings of other items gives nothing.
- A rating a directory removed stays until its page is read again; the rating of a list that is gone stays until
  [S2.3.22](https://github.com/silvioincalza/helper2.ai/issues/992).
- A page of an item named by two lists of one host is read once for each list.
- The prior is chosen, not measured.
- A directory can state figures that are not true. The rating repeats them; the report of wrong information is the
  remedy.

### STRIDE
| Threat | Risk | Control |
|---|---|---|
| Spoofing | A page pretends to be a directory's page of an item | Only a page on the host of a list an administrator's switch covers; the reader refuses a page that answers from another host. |
| Tampering | A page prints absurd or hostile numbers, or text written for a model | The page is untrusted input: three numbers by fixed patterns, range checks in the code and in the table, bounded digits, no model reads the page, nothing else is kept. A directory can still overstate; the Bayesian prior and the cap of the weight bound the effect, and a visitor can report it. |
| Repudiation | Nobody knows where a rating came from | Each row keeps the host, the page and the time of the read; the back office names them. |
| Information disclosure | The public page shows which directories rate a resource | Only the score and a number are public; the names are behind the VPN and the ADMIN role. |
| Denial of service / cost | A directory with thousands of entries spends the credits | 100 tries a day, counted per try; the Scout's daily cap; 2 MB a read; one try a page a week; the budget of the pass. |
| Elevation of privilege | A visit or a visitor starts a read | No endpoint starts a read; only the Scout's pass does. |

### AI architecture check
| # | Layer | State |
|---|---|---|
| 1 | Model | Deferred, safe: no model reads a page or makes the rating. |
| 2 | Agent Framework | Deferred, safe: no agent. |
| 3 | Orchestration | Covered: the pass of the awesome-list collector, its budgets, a page that waits is tried at a later pass. |
| 4 | Tool & API | Covered: the live web page reader; no new call target. |
| 5 | Memory | Deferred, safe: nothing about users. |
| 6 | Knowledge & RAG | Deferred, safe: the rating is not in the search index and the guide does not read it. |
| 7 | Data | Covered: one table in one migration; what is kept and until when is stated. |
| 8 | Browser & Computer Use | Covered: one page a read through the page reader, no link followed, no redirect. |
| 9 | Guardrails | Covered: the switch, the hosts never read, the link rules, all three figures or nothing, range checks. |
| 10 | Observability | Covered: pages read and ratings found per host, skip reasons, the credit metric per feature. |
| 11 | Evaluation | Gap, deferred: the patterns and the ranking weight are not measured; the metrics of point 24 and the evaluation of point 23 are the conditions. |
| 12 | Deployment | Covered: two settings with defaults, no chart change. Rollback: 0 pages a day, weight 0. |
| 13 | Security & Identity | Covered: no new endpoint that writes, no new secret, ADMIN only for the names. |
| 14 | Human-in-the-Loop | Covered: an administrator turns each host on; a visitor reports a wrong rating; a person decides the report. |
| 15 | Cost & Reliability | Covered: 100 credits a day at most; a failed read keeps the last figures. |

## Diagrams
[flow](../diagrams/flow-0056-directory-rating.svg) · [sequence](../diagrams/sequence-0056-directory-rating.svg)

## Backlog
- Decides: [S2.3.18](https://github.com/silvioincalza/helper2.ai/issues/973), in
  [F2.3](https://github.com/silvioincalza/helper2.ai/issues/33).
