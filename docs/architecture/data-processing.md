# Data processing: recipients and transfers

Who receives data from searchavo.ai, where they are, and what a transfer outside Switzerland and the EU rests on
([C-016](../requirements.md)). The [Privacy page](../../frontend/src/i18n/locales/en.legal.json) ("Who receives data") is what
visitors read; this page is its evidence, and the two change in the same pull request. The sources themselves are in
[sources.md](sources.md), the retention periods in [ADR 0010](decisions/ADR-0010-data-retention.md).

## The Data Privacy Framework list

Each US company that receives data, and GitHub, looked up by name on the official list
([dataprivacyframework.gov/list](https://www.dataprivacyframework.gov/list), active participants) on 2026-11-14
([S9.5.1](https://github.com/silvioincalza/searchavo.ai/issues/339)). "All three" means the EU-U.S. Data Privacy Framework,
the Swiss-U.S. Data Privacy Framework and the UK Extension, each `Active`.

| Recipient | What it receives | Entry on the list | Privacy page | Match |
|---|---|---|---|---|
| GitHub | Search words (live search); the sign-in; a gift through GitHub Sponsors | "GitHub", San Francisco: all three. EU-U.S. since 26 January 2017, Swiss-U.S. since 23 July 2019, UK since 16 October 2023; next certification due 3 August 2027. No other covered entity | certified, Swiss-US and EU-US and UK Extension | yes |
| Cloudflare | Every request and its IP address (network edge); the encrypted backup copy | "Cloudflare, Inc.", San Francisco: all three | certified, all three | yes |
| Google (deps.dev) | Public repository names, and the public package names the research assistant looks up, from our server | "Google LLC", Mountain View: all three | certified, all three | yes |
| Google (Analytics, AdSense, the consent tool) | What the browser sends, after consent where the rules ask for it | same entry | certified, Swiss-US and EU-US | yes |
| Google (BigQuery, `pypi`) | A fixed query; nothing about a visitor | same entry | named; no personal data goes | yes |
| Stripe | A gift by card, on Stripe's own page. The contract is with Stripe Payments Europe, Limited (Ireland) | "Stripe, LLC", South San Francisco: all three, certified 11 May 2026, next due 11 May 2027 | certified, Swiss-US and EU-US | yes |
| Hugging Face | Search words | not on the list | not certified; Art. 17(1)(b) FADP, Art. 49(1)(b) GDPR | yes |
| npm | Search words | "npm" is not on the list, and GitHub's entry names no other covered entity | not certified; same basis | yes |
| arXiv (Cornell University) | Search words | not on the list | not certified; same basis | yes |
| Tavily | Search text or the guide's web query; public page addresses | not on the list | not certified; same basis | yes |
| Algolia (Hacker News search) | Search words | not on the list | not certified; same basis | yes |
| Nebius (AI models) | Guide questions, searches in another language, public text about resources. The contract is with Nebius B.V. (Netherlands), a processor | "Nebius Inc.", Newburyport: EU-U.S. only, certified 19 November 2025, next due 19 November 2026. No Swiss-U.S. entry | the standard contractual clauses of its data processing agreement, not the list | yes |

Every line of the Privacy page that names the list matches it.

**Not on this table, and why.** Y Combinator receives nothing: Hacker News search is Algolia's. The MCP Registry, the
news feeds of AI companies, `huggingface/ai-deadlines` and `tech-conferences/conference-data` (confs.tech) are read by our server with a plain `GET` that carries nothing
about a visitor. IndexNow (api.indexnow.org) receives, once a day from the back office, the public addresses of the resource pages that changed, and shares them with the search engines that take part (Microsoft Bing and others); nothing about a visitor goes, and the Privacy page names it under "Who receives data". The research assistant's `check_url` sends a plain `HEAD` from our server to a public address a review item
names, with nothing about a visitor. jsDelivr (Volentio JSD Limited, United Kingdom) receives the public name of an npm package when the
setup guide reads its README; the Privacy page does not name it yet ([S9.1.3](https://github.com/silvioincalza/searchavo.ai/issues/433)).

Sites a link of a directory list leads to receive, from the back office, a status request of our own server when
the link redirects ([ADR 0057](decisions/ADR-0057-follow-list-links.md)): a `HEAD` (a `GET` with no body read when
the site refuses `HEAD`) and one `GET` of its `robots.txt` a day. Such a site can be anywhere. It sees the address of
our server and our User-Agent, no cookie, no credential and nothing about a visitor; the Privacy page says so under
"Who receives data". The site of a web page list receives the same request, for the address of the list page, when
the live web processor could not read the page: the answer says whether the page is gone
([ADR 0023](decisions/ADR-0023-catalog-full-text-index.md) point 17). The page text the fast model checks against a name is public text about a resource, the kind
Nebius already receives (the table above): at most 2,000 characters of a page and the name a list gives.

**When to look again.** Before a release that adds a recipient, and when a "next due" date above has passed: an entry
that is not renewed moves to the inactive list, and the Privacy line changes in the same change.

## Providers' terms for commercial use

The terms of each processor and money provider are read for a site that shows ads and takes gifts. The reading and
its legal points are tracked privately (S9.6.1, S9.6.3).
