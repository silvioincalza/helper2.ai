# 0059 — Google AdSense on resource pages, with Google's consent tool

**Status.** Accepted. **Builds on:** [0024](../edge/0024-http-edge-protection.md), [0050](0050-pages-for-crawlers.md),
[0057](0057-monetisation.md). **Changes:** [0057](0057-monetisation.md), which rejected AdSense, and its decision 7
(the footer line).

## Context
Helper2.ai shows one EthicalAds slot on search pages (0057). It now also shows Google AdSense, on resource pages
only. Before Google serves any ad, it checks the site: a `google-adsense-account` tag in the page head, and an
`ads.txt` file that names Google as an authorised seller. Google reviews the live site, so both come first.

AdSense brings three constraints:
- It sends the full page address to Google. Search and guide addresses hold the search words and the question.
- In the EEA, the UK and Switzerland it needs a Google-certified consent tool (IAB TCF). Our own bar is not one.
- It supports only a strict nonce CSP, with `'unsafe-eval'` and `'strict-dynamic'`, not an allowlist. And a browser
  keeps the CSP of the first page for the whole visit of a single-page app, so a CSP per page cannot work.

## Decision
1. **Two settings.** `H2_ADS_ADSENSE_CLIENT` (`ca-pub-` and 16 digits) and `H2_ADS_ADSENSE_SLOT` (the ad unit, 8 to
   12 digits); Helm `app.monetisation.adsenseClient` and `adsenseSlot`. Another shape stops the app at startup. Not
   secrets: every page shows them.
   - **The publisher alone** answers Google's site check: the tag and `ads.txt`, nothing else. Prod sets it.
   - **Publisher and ad unit** turn on everything else in one step: the consent tool, the nonce CSP and the slot.
     So the CSP changes only on the day the ads start, never before Google approves the site.
2. **The site check tag on every page.** The server writes
   `<meta name="google-adsense-account" content="ca-pub-...">` into the head of every page it fills (0050), without
   `data-h2-head`, so the app keeps it after a route change.
3. **`GET /ads.txt`.** One line, `google.com, pub-..., DIRECT, f08c47fec0942fa0` (Google's certification authority
   ID), cached for an hour; 404 without the publisher.
4. **The slot on resource pages only.** One box, marked "Sponsored", of fixed height, between the resource's sections
   and never next to the AI summary. A resource address has no search words. No AdSense on search pages (EthicalAds
   is there, one network per page), guide answers, Home, text pages, sign-in, error states or the back office. Each
   resource mounts its own box and asks AdSense for one ad.
5. **Google's consent tool for AdSense and Google Analytics.** While AdSense is on, the page loads Google's
   Privacy & messaging tag (`fundingchoicesmessages.google.com/i/pub-...?ers=1`) on every public page, and our own bar
   (0057) is off. Google asks where the EU, EEA, UK or Swiss rules apply. "Privacy choices" in the footer asks again
   (`googlefc.showRevocationMessage()`).
   - The page reads the answer from the TCF API (`__tcfapi`), for every change.
   - `gdprApplies` false: Google Analytics loads without a question.
   - Where the rules apply: it loads only with consent to purposes 1 (store on a device) and 8 (measure content), and
     for Google as a vendor (755). A "no", or a later withdrawal, turns it off (`ga-disable-<ID>`) and deletes its
     cookies. We do not use Google's automatic TCF mode: with it GA would still send cookieless pings after a "no".
   - The tool blocked (an ad blocker): we cannot tell where the visitor is, so GA does not load.
   - Whether an ad is personalised is AdSense's call from the same answer.
6. **A nonce CSP on the whole public site while AdSense is on.** One nonce per request (`CspNonce`): the server writes
   it on every script and module preload of the page it fills, and the header names it:
   `script-src 'nonce-…' 'unsafe-inline' 'unsafe-eval' 'strict-dynamic' https: http:`, `object-src 'none'`,
   `base-uri 'none'`, frames, images and requests to any https origin, `frame-ancestors 'none'`. Scripts our bundle
   adds (Google's tags, GA4, EthicalAds) run through `'strict-dynamic'`. The admin port keeps the strict policy of
   0024.
7. **The footer makes no claim about ads.** The Privacy page says which ads, where and when.

## Consequences
- With the ad unit empty, nothing changes but the site check.
- While AdSense is on, the public site loses part of its XSS protection: an injected script tag still needs the
  nonce, but `'unsafe-eval'` lets injected code run through eval-style calls, and every Google script runs with full
  access to the page. The admin host does not.
- Google receives the resource page address, the IP address and browser data with every ad. The Privacy page says so.
- Outside Europe, Google Analytics runs without asking, and AdSense may personalise ads under Google's policies.
- If the consent tool is blocked, Europe and the rest are the same: no analytics.
- Which TCF purposes GA4 needs is our reading (1 and 8), not a Google statement: on the lawyer list.

## Alternatives considered
- **A nonce CSP on resource pages only** — does not work in a single-page app: the CSP of the first page stays.
- **A full page load for every resource link** — the CSP would fit per page, but every click reloads the app.
- **AdSense in a sandboxed iframe on another origin** — the strongest isolation, but AdSense's rules on ads in
  iframes put the account at risk.
- **AdSense on search pages** — the search words would go to Google with every ad.
- **Our own bar plus Google's tool** — two questions for one visitor.
- **Google's automatic TCF mode for GA4** — no code, but cookieless pings after a "no".

## Code
- `backend/src/main/java/ai/helper2/monetisation/` — `Monetisation.adsenseClient`, `adsenseSlot`, `adsenseOn()`,
  `adsTxt()`, `config/MonetisationProperties.java`.
- `backend/src/main/java/ai/helper2/platform/CspNonce.java`; `identity/config/ContentSecurityPolicy.java` (`ADSENSE`),
  `CspHeaderWriter.java`; `identity/web/MeController.java` (`Me.monetisation`).
- `backend/src/main/java/ai/helper2/pages/internal/PageHtml.java` (the tag, the nonce), `pages/web/PageRoutes.java`,
  `pages/web/CrawlerFiles.java` (`/ads.txt`).
- `frontend/src/lib/googleConsent.ts`, `components/AnalyticsConsent.tsx`, `components/AdSenseSlot.tsx`,
  `pages/ResourcePage.tsx`, `components/Layout.tsx` (footer).
- `deploy/helm/helper2/values.yaml`, `values-prod.yaml`, `templates/app.yaml`.

## Diagrams
[flow](../../diagrams/flow-0059-adsense.svg) · [sequence](../../diagrams/sequence-0059-adsense.svg)
