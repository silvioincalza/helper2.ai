# 0049 — Shadow libraries

**Depends on:** [0004](../foundation/0004-persistence-and-the-schema.md),
[0007](../foundation/0007-configuration-secrets-runtime-settings.md), [0010](../foundation/0010-data-retention.md),
[0013](../scout/0013-discovery.md), [0022](0022-federated-search.md), [0027](0027-live-web-store-first-daily-quota.md),
[0036](../identity/0036-identity-and-the-admin-host.md), [0038](../backoffice/0038-back-office-and-review.md),
[0048](../backoffice/0048-review-research-assistant.md).

## Context

A live web search finds copies of books and papers on shadow libraries: sites that publish them without the rights
holder's permission (Z-Library, Library Genesis, Sci-Hub, Anna's Archive, PDF Drive). Staging held 14 copies of one
RAG book, each on a language subdomain of `z-lib.fm`.

Such a result is a risk and a bad result. A link to a copy published without consent can itself infringe copyright in
the EU, and a link put up for profit is presumed to be made with knowledge (CJEU, C-160/15, *GS Media*, 2016). The
sites carry ads and redirects, and the same book comes back once per mirror. The publisher's page is the result a person
needs.

The sites change names and mirrors often. A new mirror must be blocked the same day, without a release.

## Decision

1. **A web result on a shadow library is never shown.** A host matches when one of its labels, before the last, is a
   name on the list: `libgen` matches `libgen.rs`, `libgen.is` and `ka.libgen.is`, not `my-libgen-notes.dev` and not
   `libgen` alone (`ShadowLibraryNames.anyNames`). The check runs where a Tavily result becomes a hit
   (`TavilySource.toHit`, so the live web search, the Scout's news and its events collectors) and where discovery sorts
   a result (`DiscoveryCollector.keep`): such a page is never a hit, never a list page and never a candidate. The other
   sources (GitHub, Hugging Face, npm, arXiv, Hacker News, the courses collector's providers) return pages of their own
   or of known hosts, so they do not check.
2. **The list is data, in table `shadow_library`.** One row per name: `name` (one host label, 3 to 63 characters,
   lower case, checked in the database too), `added_by`, `added_at`, `reason`. Migration
   `V2026.11.6.2__shadow_library.sql` creates it with the first nine names (`z-lib`, `z-library`, `zlibrary`, `1lib`,
   `singlelogin`, `libgen`, `sci-hub`, `annas-archive`, `pdfdrive`), `added_by` = `migration`. The search module owns
   the table. Both apps read it; only the back office writes it ([0004](../foundation/0004-persistence-and-the-schema.md)
   §9, default privileges).
3. **The admin keeps the list on the Settings page.** `GET /api/v1/admin/shadow-libraries`, `POST` to add,
   `POST /{name}/remove` to remove, on the admin host only ([0036](../identity/0036-identity-and-the-admin-host.md)).
   Use case `ManageShadowLibraries`:
   - a name is trimmed and kept in lower case;
   - refused with a 400: not one host label (a dot, a space, a scheme, `_`), shorter than 3 characters, a label many
     sites use (`www`, `com`, `org`, `blog`, `news`, `docs`, `api`, `cdn`, …), a reason that is not one plain line of
     at most 500 characters, or a full list (200 names);
   - 409 when the name is listed already; 404 when a name to remove is not listed;
   - each add and remove is one transaction with its row in `admin_audit` (`ADD_SHADOW_LIBRARY`,
     `REMOVE_SHADOW_LIBRARY`), also when it changes nothing.
4. **Each app holds the list in memory.** `ShadowLibraries` keeps the names as an immutable set and checks a host with
   no database call. It reads the table at startup and again every 30 seconds (`ShadowLibrariesConfiguration`). The
   back office also reads it right after a change commits, so its own collectors follow at once; the public app follows
   within 30 seconds. A read that fails keeps the last list and logs a warning; at startup that list is empty until the
   next read works.

## Alternatives considered

- **A fixed list in code.** Simplest, tested, no table. A new mirror needs a code change and a release, and the mirrors
  change faster than our releases. Rejected.
- **A list in configuration (`H2_SEARCH_SHADOW_LIBRARIES`).** No table and no screen. A change still needs a redeploy
  of both apps, and a list in an environment variable is not audited. A runtime setting
  ([0007](../foundation/0007-configuration-secrets-runtime-settings.md)) is a number with a maximum, not a list.
  Rejected.
- **Rows in `discovery_host`.** That list is the Scout's, holds whole hosts (`techcrunch.com`) and the public app may
  not read it. A shadow library has many hosts under one name, and the live search needs it. Rejected.
- **Read the table on every search.** Always current, but one more query on the search path for a list that changes a
  few times a year. The 30-second refresh costs one small query per app every 30 seconds. Rejected.
- **Show the result lower in the ranking.** The link is still shown. Rejected.

## Consequences

- A new mirror is blocked from the Settings page in seconds, with an audit row saying who and why.
- A name blocks every site with that label before the last. A legitimate site with the same label (`libgen.example`)
  is blocked too; the 3-character minimum and the common labels keep the risk small.
- The list only filters new results. Rows stored before a name is added stay until their retention ends
  ([0010](../foundation/0010-data-retention.md)), or an admin removes them.
- The migration creates the table and redefines the `admin_audit.action` check with the two new actions.
- Privacy: no personal data beyond the admin's subject in `added_by`, the same as the other admin lists. The Privacy page
  does not change. Terms: no new rule for users.

### AI architecture check

| Layer | Covered by |
|---|---|
| Model | None: no model reads or writes the list. Safe to defer |
| Agent framework | None. The review research assistant ([0048](../backoffice/0048-review-research-assistant.md)) uses the same `ShadowLibraries` |
| Orchestration | 30-second refresh per app; reload after commit in the back office (4) |
| Tool & API | Admin API, three operations (3) |
| Memory | None. Safe to defer |
| Knowledge & RAG | Keeps shadow-library pages out of the results the guide answers from (1) |
| Data | `shadow_library`, seeded by its migration; audit rows (2, 3) |
| Browser & computer use | None. Safe to defer |
| Guardrails | Host check before a result is kept; name checks in code and in the database (1, 2, 3) |
| Observability | Audit trail; a warning when a read fails (3, 4) |
| Evaluation | Unit and integration tests; no offline metric needed for a block list. Safe to defer |
| Deployment | No new setting; the migration runs in the migrate step |
| Security & identity | Admin only, CSRF, admin host behind the VPN; the public app only reads (2, 3) |
| Human-in-the-loop | Every change is an admin's, with a reason, audited (3) |
| Cost & reliability | One small query per app every 30 seconds; the last list stays when a read fails (4) |

## Diagrams

- Flowchart: [flow-0049-shadow-libraries.svg](../../diagrams/flow-0049-shadow-libraries.svg)
- Sequence: [sequence-0049-shadow-libraries.svg](../../diagrams/sequence-0049-shadow-libraries.svg)
- Software architecture: [architecture-search.svg](../../diagrams/architecture-search.svg)
- Data model: [data-model.svg](../../diagrams/data-model.svg)

## Backlog

- Features: [F3.6](https://github.com/silvioincalza/helper2.ai/issues/52) Live web: store first, daily quota per
  feature. Decides STORY_ID (this ADR).
- Open issues that change this decision: none.
