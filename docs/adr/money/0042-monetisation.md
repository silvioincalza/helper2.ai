# 0042 — Monetisation

**Depends on:** [0033](../guide/0033-marking-ai-written-text-research-trail.md), [0037](../identity/0037-http-edge.md), [0039](../frontend/0039-frontend-one-box-state-in-the-url.md), [0040](../frontend/0040-design-system.md), [0041](../frontend/0041-site-pages-for-crawlers-policy-endpoint.md), [0043](../operations/0043-runtime-topology-helm-chart-three-deployables.md).

## Context

Helper2.ai is free and has running costs every day: AI models, Tavily searches, servers. It needs money from its
users without making search slower or less honest. Three ways, chosen together: gifts, ads, and analytics to see how
the site is used.

The constraints:
- Google's ad rules forbid ads on screens used "for alerts, navigation or other behavioral purposes" and ads shown for
  a preset time before the content (pre-roll). An ad on a waiting screen breaks both.
- Google AdSense checks the site before it serves an ad: a `google-adsense-account` tag in the page head, and an
  `ads.txt` file that names Google as an authorised seller. It sends the full page address to Google, and search and
  guide addresses hold the search words (`/search?q=`) and the question (`ask`). In the EEA, the UK and Switzerland it
  needs a Google-certified consent tool (IAB TCF). It supports only a strict nonce CSP with `'unsafe-eval'` and
  `'strict-dynamic'`, not an allowlist, and a browser keeps the CSP of the first page for the whole visit of a
  single-page app, so a CSP per page cannot work.
- Google Analytics 4 sets cookies: it needs consent in the EU (ePrivacy Art. 5(3), TDDDG §25 for our servers in
  Germany).
- Without any of this, every page runs under `script-src 'self'`.

## Decision

1. **One part of the site, off by default.** `site.monetisation` ([0041](../frontend/0041-site-pages-for-crawlers-policy-endpoint.md) §15) reads seven
   `H2_` settings and publishes them as one value, `Monetisation`. An empty setting is off: no link, no script, no CSP
   origin. A value with the wrong shape (a non-https link, an ID that is not `G-...`, a publisher that is not
   `ca-pub-` and 16 digits) stops the app at startup. None is a secret: every page shows them.
   - `H2_SUPPORT_GITHUB_SPONSORS_URL`, `H2_SUPPORT_STRIPE_PAYMENT_URL`
   - `H2_ANALYTICS_GA4_MEASUREMENT_ID`
   - `H2_ADS_ADSENSE_CLIENT`, and one ad unit (8 to 12 digits) per place an ad can show: `H2_ADS_ADSENSE_SLOT`
     for resource pages, `H2_ADS_ADSENSE_SLOT_SEARCH` for the box below the search results,
     `H2_ADS_ADSENSE_SLOT_GUIDE` for the box below a guide answer. **The publisher alone**
     answers Google's site check (8) and nothing else. **The publisher and at least one ad unit** turn on the rest
     in one step: Google's consent tool, the nonce CSP and the slot of each ad unit that is set. So the CSP changes
     only on the day the ads start, never before Google approves the site, and a place is switched off alone by
     emptying its ad unit.

   Helm sets them per environment (`app.monetisation.*`); a part is switched on with one value and a restart.
2. **The page reads them from `GET /api/v1/site`.** A `monetisation` object, each value null while off; the AdSense
   publisher and ad units only while the publisher and at least one ad unit are set. The answer is the same for every visitor, so it is not part of
   `/me`, which is about the user. The running server decides, so staging and prod can differ (a separate GA4 stream
   for staging) and no release is needed. `site.monetisation.web.SiteController` builds the answer once at start:
   the endpoint makes no decision, so there is no use case. The answer has no personal data and sets no cookie.
   - **Cached:** `Cache-Control: public, max-age=300`, like `/policy` and the home examples. The values change only
     with a restart, so a browser can show a part up to 5 minutes after it was switched off, or miss a new one for
     5 minutes. No ETag: the body is a few hundred bytes.
   - **Rate-limited** by the `site` limiter, `H2_RATE_LIMIT_RECENT_PER_MINUTE` requests per client address and
     minute, like the other public reads. Public in the security chain (`permitAll`).
   - **One more request per page load.** The page reads it once, next to `/me`, and never waits for it: until the
     answer is there, and when the read fails, every part is off (no link, no question, no script). So the order
     stays: settings first, then the consent question or Google's consent tool, then any script (5, 9, 10).
   - **The back office does not serve it** (404) and its web app never asks
     ([0043](../operations/0043-runtime-topology-helm-chart-three-deployables.md)).
3. **The CSP follows the parts that are on.** `site.csp.ContentSecurityPolicy.forPublicSite` starts from the strict
   policy ([0037](../identity/0037-http-edge.md) §6) and adds only:
   - analytics: `https://www.googletagmanager.com` to `script-src`; the Google Analytics hosts to `connect-src` and
     `img-src`, and `https://*.google.com` to `connect-src`, as Google's CSP guide lists. No `'unsafe-inline'`, no
     `'unsafe-eval'`: our own code writes the gtag setup.
   - AdSense on: the nonce policy of decision 11 instead.

   `CspHeaderWriter` sends it on every response of the public app's filter chain; identity's security configuration
   takes it as a `HeaderWriter` bean (`contentSecurityPolicy`). The plain 404 the public app writes for back office
   paths, before the filter chain, always carries the strict policy (`strictContentSecurityPolicy`). Three owners
   send a CSP, each its own: this one; the back office's fixed strict policy (`BackOfficeEdge`), so even a bug cannot
   load these scripts there; and the web image's `deploy/web/headers.conf` for the files it serves (`default-src
   'none'`, `sandbox`).
4. **Gifts: GitHub Sponsors and a Stripe Payment Link.** A Support page (`/support`) says what running the site costs
   and links to both. GitHub takes no fee; Stripe lets anyone give by card without an account. Payment happens at the
   provider. Nothing is locked for anyone, there is no badge, and nobody is asked twice. Stripe sends the giver back
   to `/support/thanks`. The footer shows "Support helper2" while one of the two is on.
5. **Google Analytics only after "Accept", while AdSense is off.** A bar at the bottom asks once; it never covers the
   search box, and search works before an answer. "Reject" and "Accept" are the same size and style. The answer stays
   in the browser (`h2.consent`: yes or no and the date) until the purposes change. "Cookie settings" in the footer
   asks again; a "Reject" or a withdrawn yes sets Google's off switch for the stream (`ga-disable-<ID>`: denied storage
   alone still lets cookieless pings through) and deletes the `_ga` cookies. Other open tabs follow the answer through
   the `storage` event, so a withdrawal in one tab stops the tag in all of them. While AdSense is on, Google's consent
   tool asks instead (10). What reaches Google:
   - the page path and a fixed page name per route ("Search", "Resource"), set with `gtag('set')` before every page
     view, so events Google sends on its own carry them too. Never the query string, never `document.title` (it holds
     the search words);
   - the referrer as origin and path only: the previous clean address, or the browser's referrer without its query
     string. Google reads `document.referrer` by default, and inside the site that is a search address;
   - no automatic page view, Google signals off, ad personalisation off, ad storage denied;
   - host-only cookies (`cookie_domain: 'none'`): on the site's own host, never on a parent domain such as
     `incalza.me` for staging. A withdrawal removes `_ga` cookies on the host and on every parent domain;
   - nothing on `/admin`.
6. **One ad below the search results, or one below a guide answer.** A search address holds the search words
   (`q`) and, for a guide question, the question (`ask`), and an ad network's script in the page reads the address.
   With an ad on these pages, Google receives the search words or the question, also from a visitor who answered
   "no" to Google's question (the ad is then not personalised). The Privacy page says so. A page shows one ad at
   most: a guide question shows the guide box and no search box. Home, text pages, sign-in, error states and the
   back office carry none. An ad is never written by our AI, never inside the text marked as AI-written and never
   looks like an answer ([0033](../guide/0033-marking-ai-written-text-research-trail.md)).
   While the first results of a plain search are still loading after 700 ms, and a gift link is on (4), the space
   above the loading results shows a note of our own, marked "From helper2.ai": one line on what the site costs and
   a link to `/support` (`SupportWhileSearching`). It holds nothing back: the results replace it the moment they
   come, a faster search never shows it, and a guide question never gets it. It is not an ad and sends nothing to
   anyone, so no network's rules on waiting screens apply.
7. **The footer makes no claim about ads.** The Privacy page says which ads, where and when.
8. **Google's site check: the tag and `/ads.txt`.** With the publisher set, the server writes
   `<meta name="google-adsense-account" content="ca-pub-...">` into the head of every page it fills
   ([0041](../frontend/0041-site-pages-for-crawlers-policy-endpoint.md)), without `data-h2-head`, so the app keeps it after a route change.
   `GET /ads.txt` answers one line, `google.com, pub-..., DIRECT, f08c47fec0942fa0` (Google's certification authority
   ID), cached for an hour; 404 without the publisher.
9. **Three AdSense slots: on a resource page, below the search results and below a guide answer.** Each is one box, marked
   "Advertisements" (one of the two labels Google allows), of fixed height, with its own ad unit (1).
   - **Resource page:** between the resource's sections, never next to the AI summary. Not when the address has
     `q` or `ask`.
   - **Search results** (`/search` with `q` and without `ask`): after the list and its "Show more" button, outside
     the element that marks the listed descriptions as AI-written. Only while results are on screen: not while the
     first results load, not when a search finds nothing, not when it fails (Google allows no ad on a screen
     without content). The box is keyed on the search words, so a new search asks for a new ad, and a filter, a
     sort or one more page does not.
   - **Guide answer** (`/search` with `ask`): after the guide's picks and its answer, before the result list,
     outside every element marked as AI-written. Only for a finished answer with at least one pick: not while the
     guide works, not when it asks back, picks nothing or fails. The box is keyed on the question.

   No AdSense on Home, text pages, sign-in, error states or the back office. A box asks
   AdSense for one ad, after it has put Google's consent tool in the page (10): as in Google's own snippet, the
   tool's presence frame makes the ad request wait for the consent signal, also for a visit that lands on a page
   with an ad. The AdSense script stays loaded in the app, so leaving a page with an ad pauses its requests
   (`pauseAdRequests`), and **Auto ads and overlay formats (anchor, vignette, side rail) stay off in the AdSense
   account**: Google would otherwise place more ads than one per page, and on pages we keep free of them.
10. **Google's consent tool for AdSense and Google Analytics, on the pages that show an ad.** While AdSense is on,
    the page loads Google's Privacy & messaging tag (`fundingchoicesmessages.google.com/i/pub-...?ers=1`) when a
    page of a placement opens (`lib/googleConsent.adPlacement`: a resource page, a search for words or a guide
    question, each when its ad unit is set), and our own bar (5) is off. On Home, text pages and every other page
    nothing is requested from the tool, so a first visit to Home shows no dialog. The tag sees the page address:
    with a search that is the search words, with a guide question the question (6). Google asks where the EU, EEA,
    UK or Swiss rules apply.
    "Privacy choices" in the footer works on every page: it loads the tag if the visit has not loaded it yet (the
    visitor asked for it), then asks again (`googlefc.showRevocationMessage()`). On an address with `q` or `ask` it
    first moves to the Privacy page inside the app, then loads and asks there: no new page load, so no new document
    gets the search address as its referrer.
    - **No decision known, no analytics.** Where the visitor is and what they answered come only from the tool. Until
      it has loaded in this page load, the page knows neither, so Google Analytics does not start, for anyone. We do
      not read the tool's cookies (`FCCDCF`, `FCNEC`) ourselves to carry an earlier answer to other pages: their
      format is Google's own and not documented, and they say nothing for a visitor Google did not ask. So a visitor
      who said yes last week is counted again from the first page with an ad of the new visit.
    - **After the tool has loaded**, it stays in the app. The page keeps listening, so the later pages of the visit
      are counted or not by the same answer, with the address rules of decision 5 (the path only, never `q` or
      `ask`).
    - The page reads the answer from the TCF API (`__tcfapi`), for every change, and again when the tab is seen again
      (another tab may have changed it).
    - `gdprApplies` false: Google Analytics loads without a question.
    - Where the rules apply: it loads only with consent to purposes 1 (store on a device) and 8 (measure content), and
      for Google as a vendor (755), and when Google's consent mode says analytics storage is granted
      (`googlefc.getGoogleConsentModeValues()`, a number: 1 granted, 3 not applicable). Until the consent mode data is
      ready (no value, or 0), GA waits and the page reads the answer again on `CONSENT_MODE_DATA_READY`. 2 (denied)
      and 4 (the purpose is not configured in Privacy & messaging) count as no. A "no", or a later withdrawal, turns it
      off (`ga-disable-<ID>`) and deletes its cookies. We do not use Google's automatic TCF mode: with it GA would
      still send cookieless pings after a "no".
    - The tool blocked (an ad blocker): we cannot tell where the visitor is, so GA does not load.
    - Whether an ad is personalised is AdSense's call from the same answer.
11. **A nonce CSP on the whole public site while AdSense is on.** One nonce per request (`site.csp.CspNonce`): the
    server writes it on the scripts and module preloads of the shell and on the JSON-LD it writes, never into text
    from sources, and the header names it. Pages are sent with `no-store`, so no cache gives one visitor's nonce to
    another, and `/index.html` (the shell without nonces) redirects to `/`. The policy:
    `script-src 'nonce-…' 'unsafe-inline' 'unsafe-eval' 'strict-dynamic' https: http:`, `object-src 'none'`,
    `base-uri 'none'`, frames, images and requests to any https origin, `frame-ancestors 'none'`. Scripts our bundle
    adds (Google's tags, GA4) run through `'strict-dynamic'`. The back office keeps its own fixed strict
    policy.
12. **Privacy and Terms say it.** The Privacy page lists Google Analytics (consent, cookies, 2 months at Google),
    AdSense and Google's consent tool (what Google
    receives, when it personalises, its cookies) and the gift providers. The Terms page has "Ads and gifts": an ad
    never changes results or their order, a gift buys nothing and is not tax-deductible.

### Code

- `backend/apps/public/src/main/java/ai/helper2/site/monetisation/` — `Monetisation` (`adsenseOn()`, `adsTxt()`),
  `config/MonetisationProperties.java`, `config/MonetisationConfiguration.java`, `web/SiteController.java`
  (`GET /site`); `api/public.yaml` — `GET /site`, `Site`, `Monetisation`.
- `backend/apps/public/src/main/java/ai/helper2/site/csp/` — `ContentSecurityPolicy.java` (`STRICT`, `ADSENSE`,
  `forPublicSite`), `CspHeaderWriter.java`, `CspNonce.java`, `PublicSiteCsp.java` (the two header writers).
- `backend/apps/public/src/main/java/ai/helper2/identity/security/PublicSecurityConfiguration.java` (adds the header
  writers, `/api/v1/site` public).
- `backend/libs/platform/src/main/java/ai/helper2/platform/RateLimitInterceptor.java` — the `site` limiter.
- `backend/apps/backoffice/src/main/java/ai/helper2/identity/security/BackOfficeEdge.java` — the back office's own CSP;
  `deploy/web/headers.conf` — the web image's.
- `backend/apps/public/src/main/java/ai/helper2/site/pages/internal/PageHtml.java` (the tag, the nonce),
  `site/pages/web/PageRoutes.java`, `site/pages/web/CrawlerFiles.java` (`/ads.txt`).
- `frontend/src/context/MonetisationContext.tsx` (reads `/site` once), `AppProviders.tsx`, `lib/consent.ts`, `lib/analytics.ts`,
  `lib/googleConsent.ts`, `features/monetisation/AnalyticsConsent.tsx`, `features/monetisation/SponsorSlot.tsx`,
  `features/monetisation/AdSenseSlot.tsx`, `features/monetisation/SupportPage.tsx`, `features/monetisation/SupportThanksPage.tsx`, `features/resource/ResourcePage.tsx`,
  `shell/Layout.tsx` (footer), `features/search/Results.tsx` (slot).
- `deploy/helm/helper2/values.yaml`, `values-prod.yaml`, `templates/app.yaml` — `app.monetisation.*`.
- Tests: `site/csp/ContentSecurityPolicyTest.java`, `site/csp/CspHeaderWriterTest.java`,
  `site/monetisation/config/MonetisationConfigurationTest.java`, `site/monetisation/web/SiteControllerTest.java`,
  `site/pages/web/AdsTxtTest.java`, `api/HttpEdgeIT.java`, `frontend/src/features/monetisation/Monetisation.test.tsx`,
  `frontend/src/features/monetisation/AdSenseSlot.test.tsx`, `frontend/src/lib/googleConsent.test.ts`.

## Alternatives considered

- **An ad on a waiting screen** — against Google's ad rules and close to pre-roll; it would also pay more the slower
  search is.
- **No AdSense on search pages** — the search words never reach Google, but a search is what most visits are, so
  the site would earn only from resource pages.
- **No Google ad on a search page after a "no" to Google's question** — the search words would reach Google only
  with consent. Not chosen: the non-personalised ad stays. A lawyer has to confirm the legal basis.
- **No ad next to a guide answer** — no risk from Google's rule on generated content, and the question stays
  with us. Not chosen: the guide is where a visit spends most of its time. The guide ad unit can be emptied
  without a release if Google objects.
- **The guide ad inside the answer, or between the picks** — more views, but it would sit in text marked as
  AI-written and read as a pick.
- **An ad between the results** — more views, but it sits inside the list marked as AI-described and reads as a
  result.
- **One ad unit for every place** — one setting less, but no report per place and no way to switch one place off
  without a release.
- **A nonce CSP on resource pages only** — does not work in a single-page app: the CSP of the first page stays.
- **A full page load for every resource link** — the CSP would fit per page, but every click reloads the app.
- **AdSense in a sandboxed iframe on another origin** — the strongest isolation, but AdSense's rules on ads in
  iframes put the account at risk.
- **Self-hosted analytics (Umami, Plausible)** — about 3 KB, same-origin, probably no consent needed. Not chosen:
  Google Analytics was asked for. Still the way back if consent rates are too low to be useful.
- **A consent platform (Cookiebot, Usercentrics) for analytics alone** — our own bar is a few lines of code and loads
  nothing from a third party; Google's tool takes over only when AdSense needs it.
- **Our own bar plus Google's tool** — two questions for one visitor.
- **Google's tool on every public page** — analytics for every visit, but Google's dialog covers Home
  on a first visit in Europe, before the first search.
- **Reading the tool's cookie on the other pages, to keep an earlier yes** — more visits counted, but it depends on
  an undocumented format, and it cannot tell a visitor outside Europe from one who was never asked.
- **Google Analytics without the tool's answer on pages where it is not loaded** — a visitor in Europe would be
  counted without consent.
- **The ad slot keyed on the whole search request** — a new ad for every filter, sort and typing pause.
- **An ad request while the search runs** — the ad shows sooner, but half-typed words and failed searches ask for
  ads nobody sees next to results.
- **Google's automatic TCF mode for GA4** — no code, but cookieless pings after a "no".
- **Build-time settings in the frontend bundle** — no API call, but one image could not serve staging and prod with
  different IDs.
- **In `GET /api/v1/me`** — no extra request, because every page reads `/me`. But `/me` is per user and
  never cached, and the settings are the same for everyone; the user's session carried the site's configuration.
- **In `GET /api/v1/policy`** — also configured values and cached the same way, but only the text pages read it, on
  purpose (ADR 0040, decision 16), and it holds limits and retention periods. Every page needs these settings, so
  Home would load the whole policy for six values.
- **In the HTML of the page (a script tag written by Spring)** — no request at all, but the page is `no-cache` or
  `no-store`, the web image's `index.html` would not have it, and inline data needs the nonce.
- **A `monetisation` library** — only the public app uses it
  ([0041](../frontend/0041-site-pages-for-crawlers-policy-endpoint.md) §15).

## Consequences

- With all eight settings empty, the site loads no third-party script and sends the strict CSP.
- With ads on, inline styles are allowed on public pages. Scripts are not, until AdSense is on.
- While AdSense is on, the public site loses part of its XSS protection: an injected script tag still needs the
  nonce, but `'unsafe-eval'` lets injected code run through eval-style calls, and every Google script runs with full
  access to the page. The admin host does not.
- Google receives the page address, the IP address and browser data with every ad. With a search the address
  holds the search words and with a guide answer the question, with or without consent. Outside Europe, Google
  Analytics runs without asking, and AdSense may personalise ads under Google's policies. If the consent tool is
  blocked, Europe and the rest are the same: no analytics.
- While AdSense is on, Google Analytics misses every visit that never opens a page with an ad, in Europe and
  outside it, and the pages of a visit before the first one. With the search ad unit set, a visit is counted from
  its first search; with only the resource ad unit, from its first resource page. The numbers for Home are too low
  and are not comparable with the time before. That is the price of no Google dialog on Home.
- With the search or the guide ad unit set, Google's dialog opens on the first search or guide question of a visit
  in Europe.
- Google's publisher rules warn against ads on generated content that nobody reviewed. A guide answer is generated.
  The box sits outside the answer and only where the guide picked a listed resource, but Google can still object:
  then the guide ad unit is emptied.
- Which TCF purposes GA4 needs is our reading (1 and 8), not a Google statement.
- `no-store` turns off the browser's back/forward cache for the shell while AdSense is on.
- GA4 and the ad clients run in the visitor's browser and talk to their hosts directly: the CSP must list the hosts,
  and they may change them. A blocked request shows in the browser console, not in our logs.
- Manual steps in Google Analytics, needed before the prod ID is set: data retention 2 months; in enhanced
  measurement, "Page changes based on browser history events", site search and form interactions off (they would
  send the full address with `q` and `ask`); Google signals off. Check once in DebugView that no hit carries `q=`.
- The admin web app is its own build without the site check tag or any Google script
  ([0043](../operations/0043-runtime-topology-helm-chart-three-deployables.md)).

### Threat model (STRIDE)

Trust boundary: our page and the third-party scripts it loads (Google's consent tool, AdSense, Google Analytics).
They run in our origin with full access to the page.

| | Threat | Mitigation | Left |
|---|---|---|---|
| S | An ad imitates the page (a fake "Download" or sign-in) | One box, marked "Advertisements", fixed place; AdSense blocking controls | Accepted: Google's ad review |
| T | A Google script, or a malicious ad, changes the page or the results | No SRI is possible for Google's scripts; ads only in their box; nothing on the admin host | Accepted while AdSense is on |
| T | An injected script runs under the weaker CSP | Nonce per request, only on the shell and our JSON-LD, never on source text; `no-store`; `/index.html` redirects | `'unsafe-eval'`: eval-style sinks are open |
| R | Invalid clicks get the AdSense account closed | No own clicks; nothing asks for clicks | Accepted |
| I | Search words and guide questions reach Google | Accepted for the ads below the results and below a guide answer, and said on the Privacy page; GA gets the path only; the Privacy page warns against typing personal data; requests paused off the pages with an ad; Auto ads and overlay formats off | They can be personal data, a question more than search words, and they reach Google also after a "no": a lawyer confirms the legal basis |
| S | An ad is taken for a guide pick | The box is marked "Advertisements", outside every AI-marked element, after the picks and the answer | Accepted |
| I | Analytics without consent in Europe | Our bar, or the TCF answer (purposes 1 and 8, vendor 755); a "no" sets `ga-disable` and deletes cookies; tool blocked or not loaded yet = no GA | Our reading of the purposes |
| D | Ad scripts slow the page (INP, CLS) | Fixed-height boxes; the search box comes after the list; scripts load after the app | Measure on prod |
| E | A third-party script uses the CSRF token for same-origin writes | Writes need sign-in or are rate-limited; admin only on the admin host, with the strict CSP and no third-party script | Accepted on the public host |

### Known limits

- The ad slots' height is fixed by hand; check it against real ads on prod, on a resource page and on a search page.
  Google serves ads only on the approved domain, so staging shows empty boxes.
- Google Analytics hosts are listed by hand in the CSP; if Google changes them, hits are blocked until the list is updated.

### Open points

- The manual steps come before a setting is filled on prod.

## Diagrams

Gifts and analytics: [flow](../../diagrams/flow-0042-monetisation.svg) · [sequence](../../diagrams/sequence-0042-monetisation.svg) ·
AdSense and Google's consent tool: [flow](../../diagrams/flow-0042-adsense.svg) · [sequence](../../diagrams/sequence-0042-adsense.svg)

## Backlog

- Features: [F8.1](https://github.com/silvioincalza/helper2.ai/issues/95) Gifts: GitHub Sponsors and Stripe Payment Link, [F8.2](https://github.com/silvioincalza/helper2.ai/issues/96) Google Analytics after consent, [F8.3](https://github.com/silvioincalza/helper2.ai/issues/97) AdSense on resource pages.
- Open issues that change this decision:
  - [#332](https://github.com/silvioincalza/helper2.ai/issues/332) S8.4.1 The ad networks' rules checked before any prod setting is filled
  - [#333](https://github.com/silvioincalza/helper2.ai/issues/333) S8.5.1 A 10 CHF subscription for more calls
