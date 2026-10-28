## Inspiration

New AI tools, models, agents, MCP servers, papers and news appear every day, scattered across dozens of sites. The usual fix is to put an LLM in front of them, and it makes up dates, features and popularity numbers. We asked a different question: **what if an AI search engine could only tell you what it can prove?**

## Who it is for

**Developers who build with AI and must pick a component today:** an MCP server for their agent, a model they can self-host, a library for RAG. The Official MCP Registry alone lists more than 1,000 servers (our Scout indexed them), and most have a one-line description.

What they do today:
- **Search GitHub, Hugging Face and npm one by one**, each with its own search box and its own idea of "popular".
- **Read awesome lists**, which are long, unranked and often stale.
- **Ask a chatbot**, which answers fast but can name a tool that does not exist or a feature the tool does not have. Nothing tells you which part is true.

Helper2.ai gives one answer from all those sources at once, and every recommendation carries a quote from the resource's own text that code has checked word for word. You can verify each pick in seconds instead of trusting it.

## Why it matters

- **A wrong pick costs more than a slow search.** An MCP server runs with the agent's permissions, often with API keys and file access. Choosing an abandoned project, one with no license, or one that does not do what a chatbot said, costs hours of integration work and can put real data at risk.
- **The choice now happens every week, not once a year.** Teams that build agents add tools, models and prompts all the time, and the catalog keeps growing. Nobody can read every README, so people pick whatever is popular or whatever a chatbot names first.
- **Agents will choose for themselves.** Soon an agent will look for its own tools. An agent cannot check a chatbot's answer, but it can check a quote and a source link. A search that returns only proven claims is the kind of search an agent can trust.

## What it does

**Helper2.ai** is a metasearch engine for the AI ecosystem: tools, models, MCP servers, agents, libraries, papers and news. One search covers trusted AI sources at once, and every result shows **where it came from, when, and the evidence behind it**.

- **One page, one box.** Typing searches every source at once: tools, models, MCP servers, agents, libraries, papers and news. **Ask the guide** answers the same text on the same page, above the results, so you never lose your search.
- **AI guide, powered by NVIDIA Nemotron on Nebius Token Factory.** Describe your need in your own words ("an MCP server that lets an agent browse the web with a headless browser"). The guide recommends up to three resources from the catalog, explains why with a quote from each one, and can adapt a matching prompt from the catalog. **It can only recommend things it actually found.** The page shows which Nemotron model planned the search and which one chose the results.
- **From "found it" to "using it": Guide me.** On an MCP server or tool card, **Guide me** shows how to install and start it. Config snippets for 19 AI clients (Claude Code, Cursor, VS Code, Codex, Gemini CLI and others) come from the Official MCP Registry record or the project's README; secrets are never filled in. For other tools, a fast Nemotron model picks and orders passages from the project's own documentation, and code keeps a command only if it appears whole in the README.
- **Next events:** upcoming AI conferences, hackathons and meetups. An event appears only when a source states its dates.
- **Simple to use:** one search box, plain language, every technical term explained with one click. It works for everyone, whether you are a developer or not. The interface is in English; questions can be written in any language (we tested Italian in every evaluation run).
- **Honest by design:** every search reports which sources answered, failed or timed out. Missing data shows as **Unknown**, never guessed. When sources disagree, both answers are shown.

## How we built it

- **Retrieval first, Nemotron second, validator last:**
  - A fast Nemotron model turns the need, in any language, into search terms.
  - Our federated search retrieves real candidates.
  - Nemotron 3 Super chooses only among them, using short candidate keys so it can't invent ids.
  - A deterministic validator keeps a pick only if its quote appears word for word in that resource's own text, and is long enough to mean something (at least 40 characters and 6 words, or the whole text if it is shorter).
  - A second Nemotron call checks each reason against its quote; a reason that claims more ("free", "supports Italian") is removed. Only the quote counts as proof.
- **NVIDIA Nemotron on Nebius Token Factory through Spring AI 2.0**, using its OpenAI-compatible API. The same evidence gate protects a background **AI Resource Scout** that enriches newly indexed resources. Every answer records which provider and model produced it.
- **Contract-first API (OpenAPI 3.1).** Backend controller interfaces and frontend types are generated from it, and real responses are validated against it in tests.
- **Spring Boot 4.1 and Spring Modulith:**
  - Kotlin for the business rules, Java for the adapters.
  - Parallel source queries on virtual threads, with results from different sources ranked together.
- **Sources:** GitHub, Hugging Face, npm, arXiv (AI categories) and the Official MCP Registry, plus **Tavily** as a live source for fresh AI news and articles. Tavily results go through the same merge, ranking and source-status reporting as every other source.
- **PostgreSQL** full-text search in the language of each query and resource (27 languages), plus vector search with NVIDIA `nemotron-3.5-embed-1b` embeddings in pgvector; **Redis** cache with a circuit breaker.
- **React 19 + Vite**; the whole search state lives in the URL.
- **Run as a real product:** a Helm chart with three replicas on Kubernetes behind a Cloudflare tunnel, with egress network policy; GitHub Actions deploys to staging first, then to prod after approval; structured logs, OpenTelemetry traces and a Grafana dashboard with alerts.
- **Cost guard for a free public demo:** a global daily cap on model calls and a 24-hour cache of AI-reasoned answers, so a repeated question costs nothing and is answered at once.

## Challenges we ran into

- **Keeping the model honest without trusting the prompt.** Early on, a simulated model happily recommended a tool that didn't exist and quoted a description it made up. We moved the trust boundary out of the prompt and into code: candidate keys plus verbatim-quote checks.
- **Real data exposed bugs our mocks didn't:**
  - Per-source latency measured in the wrong order.
  - A Scout that never saved its cursor.
  - A filter that bound enum values the wrong way.

  We fixed each one and verified the fix on live APIs.
- **Deciding what *not* to show:** GitHub's "updated" date changes whenever someone stars a repository, npm has no creation date, and news sites don't reliably state event dates. We show a more accurate date or *Unknown*.
- **Rate limits and terms of use:**
  - We read every source's terms and dropped the ones that don't permit our use or have no readable terms.
  - We self-throttle arXiv to one request every 3 seconds.
  - We back off when a source returns HTTP 429.

## Accomplishments that we're proud of

- **Hallucinations blocked in practice.** In our tests, a model's invented tool, made-up quote and fake "trusted by 10,000 companies" claim never reached the page. Only the claims backed by real quotes were shown, and the page says how many suggestions were removed.
- **Live, not a mockup.** Real federated search across GitHub, Hugging Face, npm and arXiv. The Scout indexed **1,000+ MCP servers** from the Official MCP Registry, and re-running it on unchanged data produces **zero** changes.
- **Resilient.** A slow source, a failed source, a cache outage, or no model at all still returns results, and the page says honestly what happened.
- **Tested end to end:** 544 backend tests, unit and integration, run against real PostgreSQL and Redis (Testcontainers), with API responses checked against the OpenAPI contract; the guide is tested end to end with a scripted model; module boundaries are verified by Spring Modulith and ArchUnit; plus 167 frontend unit and accessibility tests (28 Oct 2026, none failing).

## Known limitations

- Only the quote is proven by code. The reason is checked by a second model call, which is a judgment and can miss a claim.
- Vector search covers only what Helper2 has indexed, and the index is still being built; live sources are keyword-only.
- A new question takes about 11 seconds on the live demo (median 10.9 s; p90 17.4 s). A repeated question is answered at once from a 24-hour cache.
- In our latest evaluation, 13 of 19 real questions got an AI pick and the top pick was relevant on 12 of those 13. The other 6 got plain search results: the guide prefers "nothing fits" to a weak pick.
- Everyday-tool coverage is thin: no consumer AI-tool catalog allows reuse, so developer questions get much better answers.
- Short one-line descriptions give the guide little to quote, so some good candidates are rejected.

## What we learned

- **Treat the LLM as an untrusted proposer.** A short deterministic validator protects users better than any prompt, and it lets a fast, cheap model do useful work safely.
- **Use the right Nemotron for each step.** A small model for understanding the request and a larger one for choosing and writing keep answers quick and keep credits in check.
- **"Unknown" builds trust.** Showing missing data as missing made the product more credible and the code simpler.

## Business potential

Helper2.ai is **open source (Apache-2.0)** and free to use, with no account and no ads. It is a product, not a one-off demo: it runs on helper2.ai today and is built to keep running. Trust is the product, so nothing is for sale that could change an answer.

**How it is funded:**
- **Donations** from the people and teams who use it: developers who save hours on every component they choose. We plan to use GitHub Sponsors or Open Collective, so that money in and money spent are both public.
- **Partnerships** with AI infrastructure and model providers that want an honest, open discovery layer for the ecosystem: compute and model credits (such as Nebius Token Factory), sponsorship of the hosting, or of a specific feature like Scout coverage of a new source. Partners get visibility on the project page, **never a better place in the results**.
- **Crowdfunding** for big steps that the community asks for: Helper2 as an MCP server that agents can call, a self-hosted edition for teams, more sources and languages.

**Why a small budget is enough:**
- The infrastructure is small: PostgreSQL, Redis and one JVM app. Our estimate is about €6–10 a month self-run on one server, or $45–70 a month fully managed ([cost analysis](../cost-analysis.md)).
- Model calls are the main variable cost, and they are capped. Nemotron Nano does the cheap steps (planning, setup guides), Super does only the choosing and the reason check, thinking is off, a repeated question is answered from a 24-hour cache, and a daily cap stops spending at a fixed limit.
- Open NVIDIA models on Token Factory mean we can switch to a cheaper or self-hosted model without rewriting the product.

**Why open source fits the idea:**
- **Anyone can check how an answer was made.** The validator, the prompts and the ranking are in the repository, so "it only shows what it can prove" is something you can read, not a promise.
- **Anyone can run it.** A company can host its own copy over its own list of approved tools; a contributor can add a source in one connector.
- **The evidence is the value.** Every fact the Scout or our curation adds to the catalog keeps its source, the date it was read and the exact excerpt. That history of checked claims grows with every run and every report from users.

**Risks we know about:** donations and sponsors may not cover growth, so the daily caps stay in place; every source's terms must allow our use (we already dropped sources whose terms do not); consumer tools are poorly covered because no consumer AI-tool catalog allows reuse; and the guide is only as good as the descriptions it can quote.

## What's next for helper2.ai

- **A guide that understands time and intent:** questions like "the newest open-source agent frameworks" or "alternatives to X" should use the sources' own date filters and a different search plan per intent, instead of one plan for every question.
- **A guide that learns:** improve which results it ranks first, based on anonymous signals, such as what people open and report as wrong.
- **More events with real dates:** add more event sources that publish structured start dates.
- **Scale on Nebius:** run the Scout over the whole catalog with Nebius Serverless Jobs, and extend vector search from our own index to the live sources.
