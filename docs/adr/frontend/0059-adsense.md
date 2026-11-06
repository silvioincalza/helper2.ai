# 0059 — Google AdSense: site check and ads.txt

**Status.** Accepted (part 1). **Builds on:** [0050](0050-pages-for-crawlers.md), [0057](0057-monetisation.md).
**Changes:** [0057](0057-monetisation.md), which rejected AdSense; part 2 (the ad slot) is on the roadmap.

## Context
Helper2.ai shows one EthicalAds slot on search pages (0057). It now also uses Google AdSense, on resource pages
only. Before Google serves any ad, it checks the site: a `google-adsense-account` tag in the page head, and an
`ads.txt` file at the root that names Google as an authorised seller. Google reviews the live site, so both must be on
`helper2.ai` before the ad slot exists.

## Decision
1. **One setting.** `H2_ADS_ADSENSE_CLIENT` (Helm `app.monetisation.adsenseClient`), the publisher ID
   `ca-pub-` and 16 digits. Another shape stops the app at startup. Empty: AdSense is off, no tag, no `ads.txt`.
   It is not a secret: every page shows it. Prod sets it in `values-prod.yaml`; staging does not.
2. **The site check tag on every page.** The server writes
   `<meta name="google-adsense-account" content="ca-pub-...">` into the head of every page it fills (0050). The tag
   has no `data-h2-head`: it is the same on every page, so the app keeps it after a route change.
3. **`GET /ads.txt`.** One line, `google.com, pub-..., DIRECT, f08c47fec0942fa0` (Google's certification authority
   ID), cached for an hour. 404 while AdSense is off.
4. **Nothing else yet.** No script, no request to Google, no cookie: the Privacy page does not change in this part.

## Part 2, agreed (on the roadmap, not built)
- AdSense on resource pages only (`/resource/{id}`): no query in the address. Never on search, guide, text pages,
  sign-in, error states or the back office. EthicalAds stays on search pages, one network per page (its rule).
- Google's certified consent tool ("Privacy & messaging", IAB TCF) replaces our own bar, for AdSense and GA4 together.
- In the EEA, the UK and Switzerland (`gdprApplies`), GA4 loads only after consent, so after a "Reject" nothing is
  sent. Elsewhere GA4 loads without a question; the Privacy page says so.
- A nonce CSP with `'unsafe-eval'` and `'strict-dynamic'` on resource pages only (AdSense supports no allowlist);
  every other page keeps its CSP.
- The footer line about ads is removed.

## Consequences
- With the setting empty, nothing changes.
- Google can review `helper2.ai` once a prod release carries this change.
- `ads.txt` lists only Google. EthicalAds needs no line. A new seller is a code change.

## Alternatives considered
- **ads.txt as a static file in the frontend build** — no code, but the publisher would be baked into the image,
  and staging would claim Google as a seller too.
- **The tag only on the home page** — enough for Google's check, but a second rule for one tag; the same tag on every
  page costs nothing.

## Code
- `backend/src/main/java/ai/helper2/monetisation/` — `Monetisation.adsenseClient`, `adsTxt()`,
  `config/MonetisationProperties.java`.
- `backend/src/main/java/ai/helper2/pages/internal/PageHtml.java` — the tag; `pages/web/CrawlerFiles.java` — `/ads.txt`.
- `deploy/helm/helper2/values.yaml`, `values-prod.yaml`, `templates/app.yaml` — `app.monetisation.adsenseClient`.

## Diagrams
[flow](../../diagrams/flow-0059-adsense.svg) · [sequence](../../diagrams/sequence-0059-adsense.svg)
