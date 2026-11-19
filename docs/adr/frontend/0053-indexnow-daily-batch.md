# 0053 — IndexNow: a daily batch of changed pages

**Depends on:** [0006](../foundation/0006-outbound-calls-and-resilience.md), [0007](../foundation/0007-configuration-secrets-runtime-settings.md), [0041](0041-site-pages-for-crawlers-policy-endpoint.md), [0043](../operations/0043-runtime-topology-helm-chart-three-deployables.md).

## Context

A search engine finds a new or changed resource page through the sitemap ([0041](0041-site-pages-for-crawlers-policy-endpoint.md)),
when it next reads the sitemap. That can take days. [IndexNow](https://www.indexnow.org/documentation) is an open
protocol: a site sends the addresses that changed, and the search engines that take part (Microsoft Bing, Yandex,
Seznam, Naver and others) read them soon after. Google does not take part; for Google the sitemap stays the path.

The catalog changes mostly once a day, when the Scout runs in the back office.

## Decision

1. **Once a day, from the back office.** The back office runs every scheduled job ([0043](../operations/0043-runtime-topology-helm-chart-three-deployables.md)),
   so the batch runs there: `IndexNowSchedule`, once a day at `H2_INDEXNOW_CRON` (05:47 UTC, after the Scout's
   nightly run), under its cluster lock.
2. **What it sends.** `SendChangedPages` reads the index-worthy resources, newest change first (`ListIndexableResources`,
   the sitemap's query and types, `IndexRule`), at most 10,000 (the protocol's limit per request). It keeps the ones
   changed in the last 25 hours and sends their readable addresses (`resourcePath`), absolute on the public host
   (`H2_PUBLIC_BASE_URL`), in one `POST` to `https://api.indexnow.org/indexnow` with the host, the key and the key's
   address. Nothing changed: nothing is sent. The window is one day plus an hour, so a late run misses nothing; a page
   sent twice costs nothing.
3. **No state.** There is no "last sent" record: the window is the state. A day the job does not run, or a batch
   that fails, is not sent again; those pages still reach the engines through the sitemap.
4. **The key.** IndexNow proves the site owns the host with a key file at the site's root. The key is public by
   design, so it is a setting, not a secret: `H2_INDEXNOW_KEY` (Helm `app.indexNowKey`), the same value in both apps.
   The public app serves it at `/{key}.txt` (`IndexNowKeyFile`) and answers 404 for any other name. With no key, or
   with `H2_NOINDEX` on (staging; the chart passes it to both apps), the batch is off and there is no key file.
5. **The call.** `IndexNowNotice` uses the observed HTTP client ([0006](../foundation/0006-outbound-calls-and-resilience.md)) with a
   20 s timeout. A refused or failed batch is logged and counted (`helper2.indexnow.batches{outcome}`); no retry, see 3.
   Sent addresses are counted in `helper2.indexnow.urls`.
6. **The shared rule.** The slug (`Slug`, `resourcePath`) and the index-worthy types (`IndexRule`) live in the
   catalog (`libs/core`), so both apps write the same addresses. `IndexPolicy` in the public app reads them.

## Alternatives considered

- **Send from the public app, after each sitemap build.** No new code path in the back office, but the public app
  runs no scheduled job ([0043](../operations/0043-runtime-topology-helm-chart-three-deployables.md)), and it can run several replicas.
- **Send at once from the Scout, per changed resource.** Fresher, but many small calls, and a Scout run that writes
  thousands of rows would need its own batching. Once a day matches how the catalog changes.
- **A table with the last sent time.** Retries a missed day, but a migration and a state to keep right, for pages the
  sitemap covers anyway.
- **The key in OpenBao.** The key is published in a file by design; keeping it secret adds nothing.

## Consequences

- Bing and the other engines that take part hear about a changed page within a day instead of at their next sitemap
  read. Bing's index also feeds answer engines that search with it.
- One `POST` a day at most, to one more host from the back office (port 443, allowed by its network policy).
- Two settings: `H2_INDEXNOW_KEY` and `H2_INDEXNOW_CRON`.
- A new recipient: IndexNow receives public page addresses only, nothing about a visitor. The Privacy page names it
  ("Who receives data"), and `docs/processors.md` lists it.
- Off until `H2_INDEXNOW_KEY` is set.

### Security (STRIDE)

| Threat | Answer |
|---|---|
| Spoofing (someone sends our addresses with our key) | The key only lets a sender ask engines to read our own public pages; it cannot change what they find there. |
| Tampering | We send only addresses we build from the catalog; nothing comes back that we use. |
| Repudiation | Each batch is logged with its count. |
| Information disclosure | Only public addresses of indexed pages go out. |
| Denial of service | One request a day, at most 10,000 addresses; a slow answer stops at 20 s. |
| Elevation of privilege | The key file answers only its own name; no write path. |

### AI architecture check

No AI layer of its own. Tool & API: one outbound HTTP call, scoped to one host. Observability: two counters and a log
line. Deployment: the key in both apps, the time in the back office. Cost & Reliability: one call a day, no retry, the sitemap as the fallback.
The other layers are not touched.

## Diagrams

[flow](../../diagrams/flow-0053-indexnow.svg) · [sequence](../../diagrams/sequence-0053-indexnow.svg)

## Backlog

- Features: [F7.3](https://github.com/silvioincalza/helper2.ai/issues/89) Pages for crawlers, SEO, policy endpoint;
  story [S7.3.7](https://github.com/silvioincalza/helper2.ai/issues/817).
- Open issues that change this decision: none.
