# 0030 — Monetisation: gifts, analytics after consent, EthicalAds and AdSense

**Status.** Accepted. Merges old 0057, 0059 (texts merged in PR 10 of the reset). Until then each part below is the old record as it was, with its numbers and links moved to the new ones.

<a id="part-0057"></a>

## Part: was ADR 0057 — Monetisation: gifts, Google Analytics after consent, one EthicalAds slot

**Status.** Accepted. **Builds on:** [0025](../edge/0025-http-edge-protection.md), [0027](0027-frontend-one-box.md),
[0028](0028-design-system.md). **Replaces:** decision 9 of
[0028](0028-design-system.md) ("Footer says only No ads.").

### Context
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

### Decision
1. **One module, off by default.** `monetisation` reads four `H2_` settings and publishes them as one value,
   `Monetisation`. An empty setting is off: no link, no script, no CSP origin. A value with the wrong shape (a non-https
   link, an ID that is not `G-...`) stops the app at startup.
   - `H2_SUPPORT_GITHUB_SPONSORS_URL`, `H2_SUPPORT_STRIPE_PAYMENT_URL`
   - `H2_ANALYTICS_GA4_MEASUREMENT_ID`
   - `H2_ADS_ETHICALADS_PUBLISHER`
2. **The page reads them from `GET /api/v1/me`.** A new `monetisation` object, each value null while off. Every page
   already calls `/me` once, so there is no extra request, and Home still does not read `/policy` (ADR 0028 (part was 0042), 13). The
   running server decides, so staging and prod can differ (a separate GA4 stream for staging) and no release is needed
   to switch a part on. The back office has its own `/me`, which answers every value as null ([0032](../operations/0032-runtime-topology.md)).
3. **The CSP follows the parts that are on.** `ContentSecurityPolicy.forPublicSite` starts from the strict policy and
   adds only:
   - analytics: `https://www.googletagmanager.com` to `script-src`; the Google Analytics hosts to `connect-src` and
     `img-src`, and `https://*.google.com` to `connect-src`, as Google's CSP guide lists. No `'unsafe-inline'`, no `'unsafe-eval'`: our own code writes the gtag setup.
   - ads: `media.ethicalads.io` and `server.ethicalads.io` to `script-src`, `connect-src` and `img-src`, and
     `'unsafe-inline'` to `style-src` only (the EthicalAds client writes its own `<style>`). Scripts stay strict.
   - The back office is another app, with its own fixed strict policy (`BackOfficeEdge`), so even a bug cannot load
     these scripts there.
   - The back office's `GET /api/v1/me` answers every monetisation value as null, and the admin web app has no bar,
     no ad and no gift links.
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
     [0009](../search/0009-federated-search.md)). The bar is only a sign of progress: the results replace it the
     moment they come, and nothing waits for the ad;
   - **with the results**, it stays where it is. A new search asks EthicalAds for a new ad: the box is emptied and
     loaded again with the client's `load()`. Its `reload()` skips manual placements, and `load()` skips a box marked
     "loaded". The box stays mounted between searches, as the client needs.
   - There is no ad on a page with a guide answer (`ask`), in the guide picks, on the home page, on text pages, on
     sign-in, on error states or on the back office. An ad is never written by our AI and never looks like an answer
     ([0018](../guide/0018-ai-content-marking-and-research-trail.md)).
7. **Footer: "No tracking ads."** Replaced by [the part that was ADR 0059](#part-0059): with Google AdSense the footer makes no claim
   about ads.
8. **Privacy and Terms say it.** The Privacy page lists Google Analytics (consent, cookies, 2 months at Google),
   EthicalAds (IP used for the country only, the page address with the search words, no cookies) and the gift
   providers. The Terms page has "Ads and gifts": an ad never changes results or their order, a gift buys nothing
   and is not tax-deductible.

### Consequences
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

### Alternatives considered
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
- **In `GET /api/v1/policy`** — the place for configured values, but only the text pages read it, on purpose (ADR 0028,
  13). Every page needs these, so Home would make one more request.

### Code
- `backend/libs/monetisation/src/main/java/ai/helper2/monetisation/` — `Monetisation`, `config/MonetisationProperties.java`,
  `config/MonetisationConfiguration.java`.
- `backend/apps/public/src/main/java/ai/helper2/identity/config/ContentSecurityPolicy.java` — the public CSP;
  `CspHeaderWriter.java` sends it; the back office sends its own (`apps/backoffice/.../identity/config/BackOfficeEdge.java`).
- `backend/apps/public/src/main/java/ai/helper2/identity/web/MeController.java`, `api/common.yaml` — `Me.monetisation`.
- `frontend/src/context/MonetisationContext.tsx` (reads `/me`), `lib/consent.ts`, `lib/analytics.ts`,
  `components/AnalyticsConsent.tsx`, `components/SponsorSlot.tsx`, `pages/SupportPage.tsx`,
  `pages/SupportThanksPage.tsx`, `components/Layout.tsx` (footer), `components/results/Results.tsx` (slot).
- `deploy/helm/helper2/values.yaml`, `templates/app.yaml` — `app.monetisation.*`.

### Diagrams
[flow](../../diagrams/flow-0030-monetisation.svg) · [sequence](../../diagrams/sequence-0030-monetisation.svg)

<a id="part-0059"></a>

## Part: was ADR 0059 — Google AdSense on resource pages, with Google's consent tool

**Status.** Accepted. **Builds on:** [0025](../edge/0025-http-edge-protection.md), [0029](0029-site-pages-and-policy.md),
[the part that was ADR 0057](#part-0057). **Changes:** [the part that was ADR 0057](#part-0057), which rejected AdSense, and its decision 7
(the footer line).

### Context
Helper2.ai shows one EthicalAds slot on search pages (the part that was ADR 0057). It now also shows Google AdSense, on resource pages
only. Before Google serves any ad, it checks the site: a `google-adsense-account` tag in the page head, and an
`ads.txt` file that names Google as an authorised seller. Google reviews the live site, so both come first.

AdSense brings three constraints:
- It sends the full page address to Google. Search and guide addresses hold the search words and the question.
- In the EEA, the UK and Switzerland it needs a Google-certified consent tool (IAB TCF). Our own bar is not one.
- It supports only a strict nonce CSP, with `'unsafe-eval'` and `'strict-dynamic'`, not an allowlist. And a browser
  keeps the CSP of the first page for the whole visit of a single-page app, so a CSP per page cannot work.

### Decision
1. **Two settings.** `H2_ADS_ADSENSE_CLIENT` (`ca-pub-` and 16 digits) and `H2_ADS_ADSENSE_SLOT` (the ad unit, 8 to
   12 digits); Helm `app.monetisation.adsenseClient` and `adsenseSlot`. Another shape stops the app at startup. Not
   secrets: every page shows them.
   - **The publisher alone** answers Google's site check: the tag and `ads.txt`, nothing else. Prod sets it.
   - **Publisher and ad unit** turn on everything else in one step: the consent tool, the nonce CSP and the slot.
     So the CSP changes only on the day the ads start, never before Google approves the site.
2. **The site check tag on every page.** The server writes
   `<meta name="google-adsense-account" content="ca-pub-...">` into the head of every page it fills (0029), without
   `data-h2-head`, so the app keeps it after a route change.
3. **`GET /ads.txt`.** One line, `google.com, pub-..., DIRECT, f08c47fec0942fa0` (Google's certification authority
   ID), cached for an hour; 404 without the publisher.
4. **The slot on resource pages only.** One box, marked "Sponsored", of fixed height, between the resource's sections
   and never next to the AI summary. A resource address has no search words. No AdSense on search pages (EthicalAds
   is there, one network per page), guide answers, Home, text pages, sign-in, error states or the back office. Each
   resource mounts its own box and asks AdSense for one ad, after it has put Google's consent tool in the page (point
   5): as in Google's own snippet, the tool's presence frame makes the ad request wait for the consent signal, also
   for a visit that lands on a resource page. The AdSense script stays loaded in the app, so leaving a
   resource page pauses its requests (`pauseAdRequests`), and **Auto ads and overlay formats (anchor, vignette, side
   rail) stay off in the AdSense account**: Google would otherwise place ads, and read the address, on search pages.
5. **Google's consent tool for AdSense and Google Analytics.** While AdSense is on, the page loads Google's
   Privacy & messaging tag (`fundingchoicesmessages.google.com/i/pub-...?ers=1`) on public pages, and our own bar
   (the part that was ADR 0057) is off. The tag sees the page address, so it never loads on an address with `q` or `ask`: a visit that
   starts there gets the tag, and analytics, on its first other page. Google asks where the EU, EEA, UK or Swiss rules apply. "Privacy choices" in the footer asks again
   (`googlefc.showRevocationMessage()`). On an address with `q` or `ask` it first moves to the Privacy page inside the
   app, then asks there: no new page load, so no new document gets the search address as its referrer.
   - The page reads the answer from the TCF API (`__tcfapi`), for every change, and again when the tab is seen again
     (another tab may have changed it).
   - `gdprApplies` false: Google Analytics loads without a question.
   - Where the rules apply: it loads only with consent to purposes 1 (store on a device) and 8 (measure content), and
     for Google as a vendor (755), and when Google's consent mode says analytics storage is granted
     (`googlefc.getGoogleConsentModeValues()`, a number: 1 granted, 3 not applicable). Until the consent mode data is
     ready (no value, or 0), GA waits and the page reads the answer again on `CONSENT_MODE_DATA_READY`. 2 (denied) and
     4 (the purpose is not configured in Privacy & messaging) count as no. A "no", or a later withdrawal, turns it off
     (`ga-disable-<ID>`) and deletes its cookies. We do not use Google's automatic TCF mode: with it GA would still send cookieless pings after a "no".
   - The tool blocked (an ad blocker): we cannot tell where the visitor is, so GA does not load.
   - Whether an ad is personalised is AdSense's call from the same answer.
6. **A nonce CSP on the whole public site while AdSense is on.** One nonce per request (`CspNonce`): the server writes
   it on the scripts and module preloads of the shell and on the JSON-LD it writes, never into text from sources, and
   the header names it. Pages are sent with `no-store`, so no cache gives one visitor's nonce to another, and
   `/index.html` (the shell without nonces) redirects to `/`. The policy:
   `script-src 'nonce-…' 'unsafe-inline' 'unsafe-eval' 'strict-dynamic' https: http:`, `object-src 'none'`,
   `base-uri 'none'`, frames, images and requests to any https origin, `frame-ancestors 'none'`. Scripts our bundle
   adds (Google's tags, GA4, EthicalAds) run through `'strict-dynamic'`. The back office is another app and keeps its
   own fixed strict policy (0025, [0032](../operations/0032-runtime-topology.md)).
7. **The footer makes no claim about ads.** The Privacy page says which ads, where and when.

### Threat model (STRIDE)
Trust boundary: our page and the third-party scripts it loads while AdSense is on (Google's consent tool, AdSense,
Google Analytics, EthicalAds). They run in our origin with full access to the page.

| | Threat | Mitigation | Left |
|---|---|---|---|
| S | An ad imitates the page (a fake "Download" or sign-in) | One box, marked "Sponsored", fixed place; AdSense blocking controls | Accepted: Google's ad review |
| T | A Google script, or a malicious ad, changes the page or the results | No SRI is possible for Google's scripts; ads only in their box; nothing on the admin host | Accepted while AdSense is on |
| T | An injected script runs under the weaker CSP | Nonce per request, only on the shell and our JSON-LD, never on source text; `no-store`; `/index.html` redirects | `'unsafe-eval'`: eval-style sinks are open |
| R | Invalid clicks get the AdSense account closed | No own clicks; nothing asks for clicks | Accepted |
| I | Search words or the guide question reach Google | No AdSense and no consent tool on `q` or `ask` addresses; requests paused off resource pages; Auto ads and overlay formats off; GA gets the path only | The consent tool, once loaded, stays for the visit |
| I | Analytics without consent in Europe | GA loads only on the TCF answer (purposes 1 and 8, vendor 755); a "no" sets `ga-disable` and deletes cookies; tool blocked = no GA | Our reading of the purposes: lawyer |
| D | Ad scripts slow the page (INP, CLS) | Fixed-height box; scripts load after the app | Measure on staging |
| E | A third-party script uses the CSRF token for same-origin writes | Writes need sign-in or are rate-limited; admin only on the admin host, with the strict CSP and no third-party script | Accepted on the public host |

### Consequences
- With the ad unit empty, nothing changes but the site check.
- While AdSense is on, the public site loses part of its XSS protection: an injected script tag still needs the
  nonce, but `'unsafe-eval'` lets injected code run through eval-style calls, and every Google script runs with full
  access to the page. The admin host does not.
- Google receives the resource page address, the IP address and browser data with every ad. The Privacy page says so.
- Outside Europe, Google Analytics runs without asking, and AdSense may personalise ads under Google's policies.
- If the consent tool is blocked, Europe and the rest are the same: no analytics.
- Which TCF purposes GA4 needs is our reading (1 and 8), not a Google statement: on the lawyer list.
- `no-store` turns off the browser's back/forward cache for the shell while AdSense is on.
- The admin web app is its own build without the site check tag or any Google script
  ([0032](../operations/0032-runtime-topology.md)).

### Alternatives considered
- **A nonce CSP on resource pages only** — does not work in a single-page app: the CSP of the first page stays.
- **A full page load for every resource link** — the CSP would fit per page, but every click reloads the app.
- **AdSense in a sandboxed iframe on another origin** — the strongest isolation, but AdSense's rules on ads in
  iframes put the account at risk.
- **AdSense on search pages** — the search words would go to Google with every ad.
- **Our own bar plus Google's tool** — two questions for one visitor.
- **Google's automatic TCF mode for GA4** — no code, but cookieless pings after a "no".

### Code
- `backend/libs/monetisation/src/main/java/ai/helper2/monetisation/` — `Monetisation.adsenseClient`, `adsenseSlot`, `adsenseOn()`,
  `adsTxt()`, `config/MonetisationProperties.java`.
- `backend/libs/platform/src/main/java/ai/helper2/platform/CspNonce.java`; `identity/config/ContentSecurityPolicy.java` (`ADSENSE`),
  `CspHeaderWriter.java`; `identity/web/MeController.java` (`Me.monetisation`).
- `backend/apps/public/src/main/java/ai/helper2/pages/internal/PageHtml.java` (the tag, the nonce), `pages/web/PageRoutes.java`,
  `pages/web/CrawlerFiles.java` (`/ads.txt`).
- `frontend/src/lib/googleConsent.ts`, `components/AnalyticsConsent.tsx`, `components/AdSenseSlot.tsx`,
  `pages/ResourcePage.tsx`, `components/Layout.tsx` (footer).
- `deploy/helm/helper2/values.yaml`, `values-prod.yaml`, `templates/app.yaml`.

### Diagrams
[flow](../../diagrams/flow-0030-adsense.svg) · [sequence](../../diagrams/sequence-0030-adsense.svg)
