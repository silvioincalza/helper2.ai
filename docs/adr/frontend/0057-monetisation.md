# 0057 — Monetisation: gifts, Google Analytics after consent, one EthicalAds slot

**Status.** Accepted. **Builds on:** [0024](../edge/0024-http-edge-protection.md), [0025](0025-frontend-one-box.md),
[0040](0040-frontend-design.md), [0042](0042-redesign-design-system-v4.md). **Replaces:** decision 9 of
[0040](0040-frontend-design.md) ("Footer says only No ads.").

## Context
Helper2.ai is free and has running costs every day: AI models, Tavily searches, servers. It needs money from its
users without making search slower or less honest. Three ways, chosen together: gifts, ads and analytics to see how
the site is used.

The constraints:
- Google AdSense and Google's ad rules forbid ads on screens used "for alerts, navigation or other behavioral
  purposes" and ads shown for a preset time before the content (pre-roll). An ad on a waiting screen breaks both.
- AdSense needs a certified consent tool (IAB TCF) for the EEA, the UK and Switzerland, and a nonce CSP with
  `'unsafe-eval'` and `'strict-dynamic'`. It always sends the full page address, and our addresses hold the search
  words (`/search?q=`) and the guide question (`ask`).
- EthicalAds uses no cookies, builds no profile and needs no consent tool. It allows one ad per page, above the fold,
  above, beside or below the content and never inside it. It pays per 1,000 views.
- Google Analytics 4 sets cookies: it needs consent in the EU (ePrivacy Art. 5(3), TDDDG §25 for our servers in
  Germany).
- Every page today runs under `script-src 'self'`, and the footer says "No ads."

## Decision
1. **One module, off by default.** `monetisation` reads four `H2_` settings and publishes them as one value,
   `Monetisation`. An empty setting is off: no link, no script, no CSP origin. A value with the wrong shape (a non-https
   link, an ID that is not `G-...`) stops the app at startup.
   - `H2_SUPPORT_GITHUB_SPONSORS_URL`, `H2_SUPPORT_STRIPE_PAYMENT_URL`
   - `H2_ANALYTICS_GA4_MEASUREMENT_ID`
   - `H2_ADS_ETHICALADS_PUBLISHER`
2. **The page reads them from `GET /api/v1/me`.** A new `monetisation` object, each value null while off. Every page
   already calls `/me` once, so there is no extra request, and Home still does not read `/policy` (ADR 0042, 13). The
   running server decides, so staging and prod can differ (a separate GA4 stream for staging) and no release is needed
   to switch a part on. On the admin port `/me` answers every value as null (`AdminHost`).
3. **The CSP follows the parts that are on.** `ContentSecurityPolicy.forPublicSite` starts from the strict policy and
   adds only:
   - analytics: `https://www.googletagmanager.com` to `script-src`; the Google Analytics hosts to `connect-src` and
     `img-src`, and `https://*.google.com` to `connect-src`, as Google's CSP guide lists. No `'unsafe-inline'`, no `'unsafe-eval'`: our own code writes the gtag setup.
   - ads: `media.ethicalads.io` and `server.ethicalads.io` to `script-src`, `connect-src` and `img-src`, and
     `'unsafe-inline'` to `style-src` only (the EthicalAds client writes its own `<style>`). Scripts stay strict.
   - The admin port always sends the strict policy (`CspHeaderWriter` picks it per request), so even a bug cannot
     load these scripts there.
   - `GET /api/v1/me` answers every monetisation value as null on the admin port, so the admin host never shows the
     bar, the ad or the gift links.
4. **Gifts: GitHub Sponsors and a Stripe Payment Link.** A Support page (`/support`) says what running the site costs
   and links to both. GitHub takes no fee; Stripe lets anyone give by card without an account. Payment happens at the
   provider. Nothing is locked for anyone, there is no badge, and nobody is asked twice. Stripe sends the giver back
   to `/support/thanks`. The footer shows "Support helper2" while one of the two is on.
5. **Google Analytics only after "Accept".** A bar at the bottom asks once; it never covers the search box, and search
   works before an answer. "Reject" and "Accept" are the same size and style. The answer stays in the browser
   (`h2.consent`: yes or no and the date) until the purposes change. "Cookie settings" in the footer asks again; a
   "Reject" or a withdrawn yes sets Google's off switch for the stream (`ga-disable-<ID>`: denied storage alone still
   lets cookieless pings through) and deletes the `_ga` cookies. Other open tabs follow the answer through the
   `storage` event, so a withdrawal in one tab stops the tag in all of them.
   What reaches Google:
   - the page path and a fixed page name per route ("Search", "Resource"), set with `gtag('set')` before every page
     view, so events Google sends on its own carry them too. Never the query string, never `document.title` (it holds
     the search words);
   - the referrer as origin and path only: the previous clean address, or the browser's referrer without its query
     string. Google reads `document.referrer` by default, and inside the site that is a search address;
   - no automatic page view, Google signals off, ad personalisation off, ad storage denied;
   - host-only cookies (`cookie_domain: 'none'`): on the site's own host, never on a parent domain such as
     `incalza.me` for staging. A withdrawal removes `_ga` cookies on the host and on every parent domain;
   - nothing on `/admin`.
6. **One ad slot, EthicalAds, on search pages.** The slot sits above the results, in a box of fixed height, marked
   "Sponsored" with one line on why it is there ("Ads keep helper2 free..."). EthicalAds allows one ad per page, so
   the same slot does both jobs:
   - **while a search runs**, it shows a progress bar that fills over the longest source deadline (6 s,
     [0008](../search/0008-federated-search.md)). The bar is only a sign of progress: the results replace it the
     moment they come, and nothing waits for the ad;
   - **with the results**, it stays where it is. A new search asks EthicalAds for a new ad: the box is emptied and
     loaded again with the client's `load()`. Its `reload()` skips manual placements, and `load()` skips a box marked
     "loaded". The box stays mounted between searches, as the client needs.
   - There is no ad on a page with a guide answer (`ask`), in the guide picks, on the home page, on text pages, on
     sign-in, on error states or on the back office. An ad is never written by our AI and never looks like an answer
     ([0038](../guide/0038-ai-content-marking.md)).
7. **Footer: "No tracking ads."** Replaced by [0059](0059-adsense.md): with Google AdSense the footer makes no claim
   about ads.
8. **Privacy and Terms say it.** The Privacy page lists Google Analytics (consent, cookies, 2 months at Google),
   EthicalAds (IP used for the country only, the page address with the search words, no cookies) and the gift
   providers. The Terms page has "Ads and gifts": an ad never changes results or their order, a gift buys nothing
   and is not tax-deductible.

## Consequences
- With all four settings empty, the site behaves as before, with the same CSP.
- A part is switched on per environment with one Helm value (`app.monetisation.*`) and a restart.
- With ads on, inline styles are allowed on public pages. Scripts are not.
- EthicalAds receives the page address without the query string, and up to three topic words its client picks from
  the text of the page, so they come from the results of the search. The Privacy page says so; there is no ad on
  pages with a guide question.
- EthicalAds may refuse a small site (about 50,000 page views a month is their usual bar). The slot shows nothing
  until the publisher ID is set.
- GA4 runs in the visitor's browser and talks to Google directly: the CSP must list its hosts, and Google may change
  them. A blocked request shows in the browser console, not in our logs.
- Manual steps in Google Analytics, needed before the prod ID is set: data retention 2 months; in enhanced
  measurement, "Page changes based on browser history events", site search and form interactions off (they would
  send the full address with `q` and `ask`); Google signals off. Check once in DebugView that no hit carries `q=`.

## Alternatives considered
- **Google AdSense** — no traffic minimum, but a certified consent tool, a nonce CSP with `'unsafe-eval'`, every Google
  script with full access to our page, and the full page address with the search words sent to Google.
- **An ad on a waiting screen** — against Google's ad rules and close to pre-roll; it would also pay more the slower
  search is. The slot above the results shows during loading instead, and never holds the results back.
- **Self-hosted analytics (Umami, Plausible)** — about 3 KB, same-origin, probably no consent needed. Not chosen:
  Google Analytics was asked for. Still the way back if consent rates are too low to be useful.
- **A consent platform (Cookiebot, Usercentrics)** — needed for AdSense, not for one analytics tool. Our own bar is a
  few lines of code and loads nothing from a third party.
- **Build-time settings in the frontend bundle** — no API call, but one image could not serve staging and prod with
  different IDs.
- **In `GET /api/v1/policy`** — the place for configured values, but only the text pages read it, on purpose (ADR 0042,
  13). Every page needs these, so Home would make one more request.

## Code
- `backend/src/main/java/ai/helper2/monetisation/` — `Monetisation`, `config/MonetisationProperties.java`,
  `config/MonetisationConfiguration.java`.
- `backend/src/main/java/ai/helper2/identity/config/ContentSecurityPolicy.java` — the public CSP;
  `CspHeaderWriter.java` sends it, or the strict one on the admin port; `platform/AdminHost.java` (answered by identity) tells other modules.
- `backend/src/main/java/ai/helper2/identity/web/MeController.java`, `api/openapi.yaml` — `Me.monetisation`.
- `frontend/src/context/MonetisationContext.tsx` (reads `/me`), `lib/consent.ts`, `lib/analytics.ts`,
  `components/AnalyticsConsent.tsx`, `components/SponsorSlot.tsx`, `pages/SupportPage.tsx`,
  `pages/SupportThanksPage.tsx`, `components/Layout.tsx` (footer), `components/results/Results.tsx` (slot).
- `deploy/helm/helper2/values.yaml`, `templates/app.yaml` — `app.monetisation.*`.

## Diagrams
[flow](../../diagrams/flow-0057-monetisation.svg) · [sequence](../../diagrams/sequence-0057-monetisation.svg)
