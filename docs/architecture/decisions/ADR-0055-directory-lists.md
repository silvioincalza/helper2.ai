# 0055 — Lists of directory hosts

**Depends on:** [0007](ADR-0007-configuration-secrets-runtime-settings.md), [0010](ADR-0010-data-retention.md), [0012](ADR-0012-scout-collectors-and-events.md), [0013](ADR-0013-discovery.md), [0023](ADR-0023-catalog-full-text-index.md), [0027](ADR-0027-live-web-store-first-daily-quota.md), [0036](ADR-0036-identity-and-the-admin-host.md), [0038](ADR-0038-back-office-and-review.md)

**Requirements:** NFR-012, NFR-015, C-008, C-012, C-014

**Use cases:** UC-030 (reading), UC-019 (the switch), UC-020 (the lists an administrator adds), UC-007 and UC-024 (where it shows)

## Context

A directory host lists products ([0013](ADR-0013-discovery.md) point 21). No page of it is kept: none is a product's
own site and none is a post. A page of it whose title reads as a list ("12 best AI coding tools") waits in the review
list as an article candidate, one decision per page.

Such a list names many resources in one place. The Scout already reads a web page an administrator added as a list
([0013](ADR-0013-discovery.md) point 15): its links go to the rule of their kind, and what a source confirms is
indexed. An administrator adds each page by hand, at most 50.

Two things are missing. A list page discovery finds on a directory is never read, even on a host an administrator
would let the Scout read. And a link of a list to a blog post is skipped, although discovery itself proposes such a
post when a search finds it.

## Decision

### The switch
1. **One switch per directory host, off.** `discovery_host.reads_lists` (`V2026.11.9.1__directory_lists.sql`), false
   for every host and for a new one. The table refuses it on a writing host. An administrator turns the reading on
   host by host after checking the host.
2. **Only an administrator, audited.** `ManageDiscoveryHosts.readLists`, `POST
   /api/v1/admin/discovery-hosts/{host}/list-reading` with `{read, reason}`: ADMIN only, on the back office, CSRF on
   the write. One `admin_audit` row in the same transaction: `READ_DIRECTORY_LISTS`, the host, the outcome (`ON`,
   `OFF`, `NOT_A_DIRECTORY`, `NOT_FOUND`), the state before and after, the reason. On `/admin/settings` each
   directory row says whether its lists are read and has the button; a review says what changes before Confirm.
3. **Read with the lists.** `HostLists.directoryListsRead` is built with the two lists, once at the start of a run
   ([0013](ADR-0013-discovery.md) point 22). A change applies at the next run.

### A list page discovery finds
4. **A third shape.** `DiscoveryPages.classify` gives a list page of a directory host `LIST_TO_READ` when the switch
   of its host is on, and `LIST_TO_REVIEW` when it is off. A category with `index-lists: false` drops both.
   The category searches exclude the hosts of `helper2.scout.discovery.exclude-domains` and of each category
   ([0013](ADR-0013-discovery.md) point 4), and the known directories are among them. A host whose switch is on
   is taken out of that list for the run (`DiscoveryCollector.requestBody`, read from the hosts at the start of
   the run), so its list pages can be found. Its other pages may then come back from a search; `DiscoveryPages`
   drops them as before.
5. **Added by the rules of an added web page.** `DiscoveryCollector` hands a `LIST_TO_READ` page to
   `AddDiscoveredList` (port `DiscoveredLists`). The page becomes a `list_seed` row of kind `WEB_PAGE` with
   `origin = DISCOVERY` and the id of the collector that found it as `added_by`. The checks are the ones an
   administrator's page passes: the address rules (`ListAddresses.check`) and the hosts never read
   (`HostsNeverRead`), which win over the switch.
6. **Its own cap.** At most `web-pages.max-discovered` lists have the origin `DISCOVERY`
   (`H2_SCOUT_AWESOME_WEB_PAGES_MAX_DISCOVERED`, 100). The 50 lists an administrator adds are counted apart
   (`ManageListSeeds` counts only the origin `ADMINISTRATOR`).
7. **What is refused waits for review.** A page the checks refuse, and a page found when the cap is reached, is an
   `ARTICLE` candidate in the review list with the shape `LIST_TO_REVIEW`, as with the switch off. A page that is a
   list to read already gives nothing. A cap of 0 sends every page to review.

### Reading
8. **The reader of added web pages.** `WebPageLists` reads a discovered list like any added web page: through the
   live web page reader, at most once in `H2_SCOUT_AWESOME_WEB_PAGES_EVERY` (7 days), one credit a read, the last
   read kept in `list_seed_last_read` and used between two reads. No page of a directory other than an added list
   page is read: not its item pages, and no link that redirects is followed.
9. **A budget of its own inside the Scout's.** A read costs one credit of the Scout's daily cap
   (`H2_SCOUT_DAILY_CREDITS`, 40 by default), the cap the category searches, LinkedIn, the events scout, the courses
   and the research assistant's web search also count on ([0013](ADR-0013-discovery.md) point 10). So that the
   lists never take the whole of it:
   - at most `web-pages.discovered-reads-per-day` reads of discovered lists a UTC day
     (`H2_SCOUT_AWESOME_WEB_PAGES_DISCOVERED_READS_PER_DAY`, 10): at most 10 credits of the 40. The reads of a day are
     counted in PostgreSQL, from `list_seed.read_tried_at`, like the daily link cap;
   - in a pass the pages an administrator added are read first, the page read longest ago first; they do not count
     on the 10;
   - 100 lists read once a week need about 15 reads a day. At 10 a day a full set is read again every 10 days, not
     every 7.
10. **What waits.** Past the 10 reads of a day, a discovered list waits for the next day (`page-daily-cap`). When
    the Scout's cap is spent, every web page waits (`page-credit-cap`), an administrator's too. A list that waits
    keeps what its last read gave; a list never read yet gives nothing until its first read. The page reader of
    product pages has its own budget (`H2_SCOUT_PAGE_READER_DAILY_CREDITS`) and is not touched.
11. **Switch off.** A discovered list is read only while the switch of its host is on, checked at every pass. With
    the switch off, or the host removed, it is not read (`page-host-off`) and costs nothing. Its last read is still
    used: what the list names is written again at each pass, so it is not aged out and the count on the public page
    stays. Turning the switch on resumes the reading. A new list page of the host waits for review.

### What a list gives
12. **The rule of each kind.** The links of a discovered list follow [0013](ADR-0013-discovery.md) point 17 with no
    change: a model, a paper and a package are asked of their source and indexed when it confirms them; a course
    page and a product site are candidates in the review list. The same caps hold: 500 candidates a pass, 50 new
    review items a day, 100 models, 200 papers and 100 packages a pass.
13. **A link to an article is a candidate.** `ListEntryLinks.route` gives a link that is no product page and reads as
    an article by `DiscoveryPages.isWritingPage` (a post on a writing host, below `/blog/` or with a post's slug)
    the route `Article`: a `RESOURCE_CANDIDATE` of type `ARTICLE`, titled by the link's words, inside the same caps.
    A blog's index, an author's page, a page on a directory host and a page on an excluded domain are skipped as
    they are for products. This holds for every list, on GitHub or on the web.
14. **A list page of a directory host is never a resource.** The collector writes no hit for it
    (`GitHubAwesomeCollector.listHits`), whoever added the page: the host decides, read from the host lists at each
    pass, and a list with the origin `DISCOVERY` is one for good, also after its host is removed from the directory
    hosts. So it has no page, it is in no search result, in no hub and no sitemap, and it is not counted among the
    resources. Its words are not in the list index either: a resource is not found by the words a directory list
    uses around it.
15. **List → resource, two relations.**
    - A curated list (an awesome list on GitHub, a web page that is not on a directory host) is a resource; the list
      index ([0023](ADR-0023-catalog-full-text-index.md)) keeps what it names in `awesome_list_entry`, by the id of
      the list. Nothing changes there.
    - A list page of a directory host is kept by its address in `directory_list_entry` (`list_url`, `resource_id`,
      `list_title`, `heading_path`, `updated_at`; owned by the catalog, read-only for the public app):
      one row for each resource of the list that is in the catalog, whatever its kind.
      `DirectoryListIndex.replace` writes the rows of a list at each pass (`ListText.directoryListTitle` marks such
      a list for the Scout's `ListIndex`). No period deletes the rows: they stay until the next read of the list
      writes them again, and a row goes with its resource (`ON DELETE CASCADE`), as in the list index.
    - **A list that is gone.** A curated list on GitHub whose repository GitHub no longer knows stops naming its
      resources ([0023](ADR-0023-catalog-full-text-index.md) point 17, UC-030 BR-010). A list page of a directory
      host does not: it is a web page, and the page reader does not tell a page that is gone from a page it could
      not read, so its rows stay until its next read.
    - **Every kind.** `GitHubAwesomeCollector.searchEntries` gives the index the repositories, the models, the
      papers and the packages of a list first, then the pages that wait for a person: a product, a course and an
      article, by the address their candidate has (`ListEntryLinks.reviewedPage`). The index keeps the ones that
      are in the catalog, so a list read again names an accepted product. Both relations follow this rule.
    - **On accept.** A candidate keeps the list that named it in its `foundBy` entry: the address, the heading
      path and, for a list page of a directory host, its title. When an administrator accepts it,
      `IndexAcceptedCandidate` hands each list to `NameResourceByList` (port `ListNamings`), which writes one row
      in the relation of that list, in the transaction of the decision. A candidate whose page is in the catalog
      already names that resource. The row of a curated list carries the title of the resource as its words until
      the next read of the list writes the words of the entry. A curated list that is not in the list index (never
      read, or gone at its source) names nothing on accept.
16. **The public page counts, the back office names.**
    - `GET /api/v1/resources/{id}`: `foundIn.directoryLists` is the number of directory lists that name the
      resource, counted in `directory_list_entry`. `foundIn.lists` and `foundIn.more` are the curated lists, as
      before. The page says "Named in 3 directory lists" under the curated lists it names.
    - `GET /api/v1/admin/review-items/{id}/evidence`: `directoryLists` names up to 50 of them for the item's
      resource: title, the address of the page, the headings above the entry. The review page shows each as a link
      (https only, `rel="noopener noreferrer nofollow"`).
17. **Lists to read.** `/admin/settings` shows a discovered list with the collector that found it; an administrator
    removes it like any other. `ListSeedEntry.origin` carries it in the API.
18. **Metrics.** `helper2.scout.awesome.skips{reason}` gets `page-daily-cap` and `page-host-off`
    ([0013](ADR-0013-discovery.md) point 19). A read counts on `helper2.live-web.credits{feature="scout-list-pages"}`.

### Code
- `backend/libs/schema/src/main/resources/db/migration/V2026.11.9.1__directory_lists.sql`.
- `backend/apps/backoffice/src/main/kotlin/ai/helper2/scout/DiscoveryHosts.kt` (`HostLists.readsListsOf`,
  `DiscoveryHostStore.readLists`), `ListSeeds.kt` (`ListOrigin`, `DiscoveredLists`),
  `scout/usecase/ManageDiscoveryHosts.kt` (`readLists`), `AddDiscoveredList.kt`, `ManageListSeeds.kt`;
  `scout/internal/JdbcDiscoveryHostStore.java`, `JdbcListSeedStore.java`.
- `backend/apps/backoffice/src/main/java/ai/helper2/sources/liveweb/scout/DiscoveryPages.java` (`LIST_TO_READ`),
  `DiscoveryCollector.java` (`readAsList`); `sources/github/scout/WebPageLists.java` (`Reads`, `switchedOff`),
  `ListEntryLinks.java` (`Article`), `GitHubAwesomeCollector.java`;
  `backend/libs/core/src/main/java/ai/helper2/sources/config/CollectorProperties.java` (`WebPages`).
- `backend/libs/core/src/main/java/ai/helper2/catalog/DirectoryListIndex.java`, `AwesomeListEntries.java`,
  `ResourceDetail.java` (`FoundIn`), `catalog/internal/JdbcDirectoryListIndex.java`, `JdbcResourceDetailReads.java`,
  `ListNamings.java`, `catalog/internal/JdbcListNamings.java`, `catalog/usecase/NameResourceByList.java`;
  `backend/apps/backoffice/src/main/java/ai/helper2/scout/internal/ScoutConfiguration.java`
  (`listIndex`).
- `backend/apps/backoffice/src/main/java/ai/helper2/admin/web/DiscoveryHostsController.java`,
  `ListSeedsController.java`, `ReviewController.java`, `admin/usecase/GetReviewEvidence.java`;
  `backend/apps/public/src/main/java/ai/helper2/catalog/web/ResourceDetailController.java`; `api/admin.yaml`,
  `api/public.yaml`.
- `frontend/src/admin/DiscoveryHostsPanel.tsx`, `ListSeedsPanel.tsx`, `ReviewEvidence.tsx`,
  `frontend/src/features/resource/FoundInLists.tsx`.
- Tests: `ManageDiscoveryHostsTest`, `AddDiscoveredListTest`, `ManageListSeedsTest`, `DiscoveryPagesTest`,
  `DiscoveryCollectorTest`, `WebPageListsTest`, `ListEntryLinksTest`, `JdbcDiscoveryHostStoreIT`,
  `JdbcListSeedStoreIT`, `DiscoveryHostsApiIT`, `JdbcDirectoryListIndexIT`,
  `ResourceFoundInApiIT`, `JdbcListNamingsIT`, `NameResourceByListTest`, `IndexAcceptedCandidateTest`,
  `ListEntryLinksTest`, `DiscoveryHostsPanel.test.tsx`, `ListSeedsPanel.test.tsx`, `ReviewEvidence.test.tsx`,
  `ResourcePage.test.tsx`.

## Alternatives considered

| | A switch per host, the reader of added pages (chosen) | Search the directories and index their listings | Review only | A crawler of a directory's own pages |
|---|---|---|---|---|
| What it is | Discovery adds a list page as a list to read on a host an administrator turned on | Tavily searches with `include_domains`, each listing becomes an entry | Every list page is one review item; an administrator adds a page by hand | Follow a directory's item pages and their outbound links |
| Simplicity | One column, one shape, one use case; the reader, the link rules and the index exist | A new collector and a mapping per directory | Nothing to build | A new crawler, redirects, a queue per host |
| Coupling | The discovery collector knows one port of the scout | A parser per directory's page layout | None | Tied to each directory's markup |
| Testability | Unit tests on the shape, the use case and the reader | Fixtures per directory | — | Fixtures per directory, redirect cases |
| Operational cost | At most 10 credits a day, capped in configuration | 2 credits a search, more searches | The administrator's time: one decision a page, 50 pages by hand | Many reads a list |
| Failure modes | A page that fails waits and is tried again; a full cap sends pages to review | A layout change empties a source | The review list fills | Redirect loops, blocked reads |
| Security | No new call target: the page reader, then the Hub, arXiv and npm by id | The same reader | None | Following redirects opens SSRF cases the link rules refuse today |
| Reversibility | Switch off: reading stops at the next run | Remove the collector and its records | — | Remove the crawler and its records |
| Load for one person | One switch per host after a check | A parser to keep alive per directory | High: every page by hand | High |

- **Search the directories and index their listings** gives their catalog, not ours: no source confirms an entry,
  and no person checks the host first.
- **Review only** keeps a person on every page but gives nothing from the links of a page until someone adds it by
  hand.
- **A crawler of a directory's own pages** reads far more than the lists and needs redirects followed, which the
  link rules refuse.
- **One cap for every added list.** Simpler, but discovery could then fill the 50 places of an administrator.
- **A daily cap counted in Redis.** The reads are already in `list_seed`; counting them there needs no second
  store and survives a cache flush.
- **A directory list as a resource kept out of every public read** (a mark on the list, and a condition in search,
  the resource page, the hubs, the sitemap, the guide and the counts) — no new table and the list index is reused,
  but each public read must remember the condition, and one that forgets shows the page: a trap for every later
  read. Its own table keyed by the address needs one small table and one writer, and nothing public can show what
  is not a resource.
- **Drop a discovered list when the switch goes off.** Clean, but the rows of what it named would stay with no
  list behind them, since only a read of the list writes them again, and turning the switch on again would have to
  find and read every list again.

## Consequences

- A search no longer excludes a directory host whose switch is on. Its pages take places among the results of a
  query (10 a query); the ones that are not list pages are dropped after the search, so a query can return fewer
  usable pages.
- A list page on a directory an administrator turned on feeds the catalog without one decision per page. What a
  source confirms is indexed; products, courses, articles and models under the like floor still wait for a person.
- More credits: one read a week per discovered list, at most 10 a day of the Scout's 40. On a day the categories
  and LinkedIn spend most of the cap ([0013](ADR-0013-discovery.md), Consequences), the list reads and the last
  searches of the day share what is left: whichever collector runs first spends first, and the other keeps its pass
  open or waits for the next day.
- More review items, inside the 50 new items a day of the awesome-list collector: the links of the new lists, and
  the article links of every list.
- A list whose links point to the directory's own pages, or through a redirect, gives little: those links are
  skipped by the link rules. Its GitHub repositories, models, papers and packages are still read.
- A list page of a directory host is not a resource, so search does not find it, and it gives no words and no
  categories to the resources it names. A web page on a directory host that an administrator adds by hand follows
  the same rule.
- Nothing new is kept about a person. From a discovered page the Scout keeps what it keeps from an added page: the
  address, the title, the headings, the links with their words and up to 500 characters of each entry, until the
  next read; and the address and the title with each resource it names. An entry's text can hold a name the page prints. `list_seed.added_by` holds a collector id for a
  discovered list, not an administrator. The Privacy page says that such pages are read and what is kept.
- Retention ([0010](ADR-0010-data-retention.md)): a discovered list stays in `list_seed` until an administrator
  removes it; its last read goes with it. Its audit rows follow the audit trail. What a list named
  (`awesome_list_entry`, `directory_list_entry`) has no period: it stays until the next read of the list writes it
  again, or until the resource it names goes. What a curated list on GitHub named also goes when its repository is
  gone ([0023](ADR-0023-catalog-full-text-index.md) point 17); `directory_list_entry` has no such rule.

### Known limits
- A product, a course and an article come after the pages a source confirms in the index of a list. In a list
  past the caps of the index ([0023](ADR-0023-catalog-full-text-index.md) point 10) the last of them have no row.
- A resource an administrator accepted under another address than the link of the list is named by the list
  until its next read, which no longer finds it.
- A discovered list an administrator removes can be found and added again by a later search.
- The reads of a day are counted from the last try of each list: a list tried twice in one UTC day counts once.
- The count on the public page includes a directory list whose switch is off, and a list whose page no longer
  names the resource until its next read. It never falls by time alone.
- A list that is no longer read keeps naming its resources: a list an administrator removed from the lists to read,
  a directory list whose switch is off or whose host is never read. Only a read of the list or the end of the
  resource removes a row.
- A web page that no longer exists (it answers 404 or 410) keeps naming its resources too, a list page of a
  directory host and a curated web page alike, and the count on the public page keeps it. The page reader gives no
  status of the page: a page that is gone and a page that timed out are the same failed read. Telling them apart
  needs a request of our own to the page, which is not made.
- A list page of a directory host that waits for review (switch off, or refused) is an `ARTICLE` candidate. When an
  administrator accepts it, it becomes an `ARTICLE` resource like any accepted candidate: a person's decision on
  one page, not a list that is read.
- The page a crawler gets (`PageHtml`) names the curated lists and does not give the number of directory lists.

### STRIDE
| Threat | Risk | Control |
|---|---|---|
| Spoofing | A page pretends to be a list of a trusted directory | The host is matched by name and subdomain against `discovery_host`; the reader refuses a page that answers from another host (`WebPageReader`). |
| Tampering | A list page carries hostile links, or text written for a model | The link rules of [0013](ADR-0013-discovery.md) point 17: https, no IP, no redirect, no shortener; no model reads the page. |
| Repudiation | Nobody knows who let a host be read | One audit row per switch change, with the administrator, the states and the reason; the list row names its collector. |
| Information disclosure | The public page shows which directories name a resource | Only a number is public; the names are in the back office, behind the VPN and the ADMIN role. |
| Denial of service / cost | A directory with thousands of list pages spends the credits or fills the review list | 100 discovered lists, 10 reads a day, the Scout's daily cap, 2 MB a read, 2,000 links a list, 500 candidates a pass, 50 new review items a day. |
| Elevation of privilege | A non-admin turns a host on | The endpoint is ADMIN only on the back office, CSRF on the write ([0036](ADR-0036-identity-and-the-admin-host.md)). |

### AI architecture check
| # | Layer | State |
|---|---|---|
| 1 | Model | Deferred, safe: no model reads a list page or sorts its links. |
| 2 | Agent Framework | Deferred, safe: no agent. |
| 3 | Orchestration | Covered: the awesome-list collector's pass, its budgets, a list that waits is read at a later pass. |
| 4 | Tool & API | Covered: the live web page reader for the page; the Hub, arXiv and npm by id for its links. |
| 5 | Memory | Deferred, safe: nothing about users. |
| 6 | Knowledge & RAG | Covered: a directory list adds resources, not words: it is not in the list index and not in search. |
| 7 | Data | Covered: two columns and one table in one migration; the origin of a list and what a directory list names are stored. |
| 8 | Browser & Computer Use | Covered: one page a read through the page reader, no link followed, no redirect. |
| 9 | Guardrails | Covered: the switch, the address rules, the hosts never read, the link rules. |
| 10 | Observability | Covered: two skip reasons, the credit metric per feature, the audit row. |
| 11 | Evaluation | Gap, deferred: the share of useful links per directory is not measured; the review decisions of the first weeks give it. |
| 12 | Deployment | Covered: two settings with defaults, no chart change. Rollback: switch off per host, or 0 discovered lists. |
| 13 | Security & Identity | Covered: ADMIN only, VPN, CSRF, no new secret. |
| 14 | Human-in-the-Loop | Covered: an administrator turns each host on; products, courses and articles wait for a person. |
| 15 | Cost & Reliability | Covered: 10 reads a day inside the Scout's cap; a failed read waits and is tried again. |

## Diagrams
[flow](../diagrams/flow-0055-directory-lists.svg) · [sequence](../diagrams/sequence-0055-directory-lists.svg)

## Backlog
- Decides: [S2.3.15](https://github.com/silvioincalza/helper2.ai/issues/970),
  [S2.3.16](https://github.com/silvioincalza/helper2.ai/issues/971),
  [S2.3.17](https://github.com/silvioincalza/helper2.ai/issues/972), in
  [F2.3](https://github.com/silvioincalza/helper2.ai/issues/33).
