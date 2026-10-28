## Inspiration

New AI tools, models, agents, MCP servers, papers and news show up every day, scattered across dozens of sites. The usual fix is to put an LLM in front of them, and then it makes up dates, features and popularity numbers. So I asked a different question: **what if an AI search engine could only tell you what it can prove?**

## Who it is for

**Developers who build with AI and have to pick a component today:** an MCP server for their agent, a model they can self-host, a library for RAG. The Official MCP Registry alone lists more than 1,000 servers (my Scout indexed them), and most have a one-line description.

What they do today:
- **Search GitHub, Hugging Face and npm one by one**, each with its own search box and its own idea of "popular".
- **Read awesome lists**, which are long, unranked and often stale.
- **Ask a chatbot**, which answers fast but can name a tool that doesn't exist, or a feature the tool doesn't have. Nothing tells you which part is true.

Helper2.ai gives one answer from all those sources at once, and every recommendation carries a quote from the resource's own text that code has checked word for word. You can check each pick in seconds instead of trusting it.

## Why it matters

- **A wrong pick costs more than a slow search.** An MCP server runs with the agent's permissions, often with API keys and file access. Picking an abandoned project, one with no license, or one that doesn't do what a chatbot said costs hours of integration work and can put real data at risk.
- **This choice now happens every week, not once a year.** Teams building agents add tools, models and prompts all the time, and the catalog keeps growing. Nobody can read every README, so people pick whatever is popular or whatever a chatbot names first.
- **Agents will choose for themselves.** Soon an agent will look for its own tools. An agent can't check a chatbot's answer, but it can check a quote and a source link. A search that returns only proven claims is a search an agent can trust.

## What it does

**Helper2.ai** is a metasearch engine for the AI ecosystem: tools, models, MCP servers, agents, libraries, papers and news. One search covers trusted AI sources at once, and every result shows **where it came from, when, and the evidence behind it**.

- **One page, one box.** Typing searches every source at once. **Ask the guide** answers the same text on the same page, above the results, so you never lose your search.
- **AI guide, powered by NVIDIA Nemotron on Nebius Token Factory.** Describe what you need in your own words ("an MCP server that lets an agent browse the web with a headless browser"). The guide recommends up to three resources from the catalog, explains why with a quote from each one, and can adapt a matching prompt from the catalog. **It can only recommend things it actually found.** The page shows which Nemotron model planned the search and which one chose the results.
- **From "found it" to "using it": Guide me.** On an MCP server or tool card, **Guide me** shows how to install and start it. Config snippets for 19 AI clients (Claude Code, Cursor, VS Code, Codex, Gemini CLI and others) come from the Official MCP Registry record or the project's README; secrets are never filled in. For other tools, a fast Nemotron model picks and orders passages from the project's own documentation, and code keeps a command only if it appears whole in the README.
- **Next events:** upcoming AI conferences, hackathons and meetups. An event shows up only when a source states its dates.
- **Simple to use:** one search box, plain language, every technical term explained with one click. It's meant for everyone, developer or not. The interface is in English; you can ask in any language (I tested Italian in every evaluation run).
- **Honest by design:** every search reports which sources answered, failed or timed out. Missing data shows as **Unknown**, never guessed. When sources disagree, you see both answers.

## How I built it

- **Retrieval first, Nemotron second, validator last:**
  - A fast Nemotron model turns the need, in any language, into search terms.
  - My federated search retrieves real candidates.
  - Nemotron 3 Super chooses only among them, using short candidate keys so it can't invent ids.
  - A deterministic validator keeps a pick only if its quote appears word for word in that resource's own text, and is long enough to mean something (at least 40 characters and 6 words, or the whole text if it's shorter).
  - A second Nemotron call checks each reason against its quote; a reason that claims more ("free", "supports Italian") is removed. Only the quote counts as proof.
- **NVIDIA Nemotron on Nebius Token Factory through Spring AI 2.0**, using its OpenAI-compatible API. The same evidence gate protects a background **AI Resource Scout** that enriches newly indexed resources. Every answer records which provider and model produced it.
- **Contract-first API (OpenAPI 3.1).** Backend controller interfaces and frontend types are generated from it, and real responses are validated against it in tests.
- **Spring Boot 4.1 and Spring Modulith:**
  - Kotlin for the business rules, Java for the adapters.
  - Parallel source queries on virtual threads, with results from different sources ranked together.
- **Sources:** GitHub, Hugging Face, npm, arXiv (AI categories) and the Official MCP Registry, plus **Tavily** as a live source for fresh AI news and articles. Tavily results go through the same merge, ranking and source-status reporting as every other source.
- **PostgreSQL** full-text search in the language of each query and resource (27 languages), plus vector search with NVIDIA `nemotron-3.5-embed-1b` embeddings in pgvector; **Redis** cache with a circuit breaker.
- **React 19 + Vite**; the whole search state lives in the URL.
- **Cost guard for a free public demo:** a global daily cap on model calls and a 24-hour cache of AI-reasoned answers, so a repeated question costs nothing and comes back at once.
- **Run as a real product:** a Helm chart with three replicas on Kubernetes behind a Cloudflare tunnel, with an egress network policy; GitHub Actions deploys to staging first, then to prod after approval; structured logs, OpenTelemetry traces and a Grafana dashboard with alerts.

## Challenges I ran into

- **Keeping the model honest without trusting the prompt.** Early on, a simulated model happily recommended a tool that didn't exist and quoted a description it made up. So I moved the trust boundary out of the prompt and into code: candidate keys plus verbatim-quote checks.
- **Real data exposed bugs my mocks didn't:**
  - Per-source latency measured in the wrong order.
  - A Scout that never saved its cursor.
  - A filter that bound enum values the wrong way.

  I fixed each one and checked the fix against the live APIs.
- **Deciding what *not* to show:** GitHub's "updated" date changes whenever someone stars a repository, npm has no creation date, and news sites don't reliably state event dates. I show a more accurate date, or *Unknown*.
- **Rate limits and terms of use:**
  - I read every source's terms and dropped the ones that don't allow my use or have no readable terms.
  - I throttle arXiv to one request every 3 seconds.
  - I back off when a source returns HTTP 429.

## Accomplishments that I'm proud of

- **Hallucinations blocked in practice.** In my tests, a model's invented tool, made-up quote and fake "trusted by 10,000 companies" claim never reached the page. Only claims backed by real quotes were shown, and the page says how many suggestions were removed.
- **Live, not a mockup.** Real federated search across GitHub, Hugging Face, npm and arXiv. The Scout indexed **1,000+ MCP servers** from the Official MCP Registry, and re-running it on unchanged data produces **zero** changes.
- **Resilient.** A slow source, a failed source, a cache outage, or no model at all still returns results, and the page says honestly what happened.
- **Tested end to end:** 544 backend tests, unit and integration, run against real PostgreSQL and Redis (Testcontainers), with API responses checked against the OpenAPI contract; the guide is tested end to end with a scripted model; module boundaries are verified by Spring Modulith and ArchUnit; plus 167 frontend unit and accessibility tests (28 Oct 2026, none failing).

## Known limitations

I'd rather list these myself than have you find them:

- **Code proves the quote, not the reason.** Every quote is checked word for word. The reason next to it is checked by a second Nemotron call, and that is a judgment, so a claim can still slip through.
- **A new question is slow.** About 11 seconds on the live demo (median 10.9 s, p90 17.4 s). A broad "what's new" question takes about 10 s more, because that one choose call runs with thinking on. Ask the same thing again and it comes back at once from the 24-hour cache.
- **Sometimes it says "nothing fits" when something does.** In my last full evaluation, 13 of 19 real questions got an AI pick, and the top pick was relevant on 12 of those 13. The other 6 got plain search results. That's a trade I chose: an honest empty answer beats a weak pick. The run was before the intent work, so comparisons, explanations and "newest" questions aren't in those numbers yet.
- **"Newest" only shows what has a real date.** npm doesn't publish a creation date, so npm results are left out of "newest" answers, and the answer doesn't say how many were dropped.
- **Vector search only covers my own index**, which is still growing. Live sources are searched by keyword only.
- **Developer questions work much better than everyday ones.** I couldn't find a consumer AI-tool catalog whose terms allow reuse, so that coverage is thin.
- **A one-line description gives the guide almost nothing to quote**, so some good tools get rejected just because their own text says too little.
- **Courses are a small, hand-reviewed list for now.** Coursera, edX and Udemy APIs need partner keys I don't have yet.

## What I learned

- **Treat the LLM as an untrusted proposer.** A short deterministic validator protects users better than any prompt, and it lets a fast, cheap model do useful work safely.
- **Use the right Nemotron for each step.** A small model to understand the request and a larger one to choose and write keep answers quick and credits in check.
- **"Unknown" builds trust.** Showing missing data as missing made the product more credible and the code simpler.

## Business potential

Helper2.ai is **open source (Apache-2.0)** and free to use, with no account and no ads. It's a product, not a one-off demo: it runs on helper2.ai today and I built it to keep running. Trust is the product, so nothing is for sale that could change an answer.

**How it's funded:**
- **Donations** from the people and teams who use it: developers who save hours on every component they choose. I plan to use GitHub Sponsors or Open Collective, so money in and money spent are both public.
- **Partnerships** with AI infrastructure and model providers that want an honest, open discovery layer for the ecosystem: compute and model credits (such as Nebius Token Factory), sponsoring the hosting, or a specific feature like Scout coverage of a new source. Partners get visibility on the project page, **never a better place in the results**.
- **Crowdfunding** for big steps the community asks for: Helper2 as an MCP server that agents can call, a self-hosted edition for teams, more sources and languages.

**Why a small budget is enough:**
- The infrastructure is small: PostgreSQL, Redis and one JVM app. My estimate is about €6–10 a month self-run on one server, or $45–70 a month fully managed ([cost analysis](../cost-analysis.md)).
- Model calls are the main variable cost, and they're capped. Nemotron Nano does the cheap steps (planning, setup guides), Super does only the choosing and the reason check, thinking is off, a repeated question is answered from a 24-hour cache, and a daily cap stops spending at a fixed limit.
- Open NVIDIA models on Token Factory mean I can switch to a cheaper or self-hosted model without rewriting the product.

**Why open source fits the idea:**
- **Anyone can check how an answer was made.** The validator, the prompts and the ranking are in the repository, so "it only shows what it can prove" is something you can read, not a promise.
- **Anyone can run it.** A company can host its own copy over its own list of approved tools; a contributor can add a source with one connector.
- **The evidence is the value.** Every fact the Scout or my curation adds to the catalog keeps its source, the date it was read and the exact excerpt. That history of checked claims grows with every run and every report from users.

**Risks I know about:** donations and sponsors may not cover growth, so the daily caps stay in place; every source's terms must allow my use (I already dropped sources whose terms don't); consumer tools are poorly covered because no consumer AI-tool catalog allows reuse; and the guide is only as good as the descriptions it can quote.

## What's next for helper2.ai

- **A guide that understands time and intent:** questions like "the newest open-source agent frameworks" or "alternatives to X" should use the sources' own date filters and a different search plan per intent, instead of one plan for every question. I'm finishing this now.
- **A guide that learns:** improve which results it ranks first, based on anonymous signals, such as what people open and report as wrong.
- **More events with real dates:** add more event sources that publish structured start dates.
- **Scale on Nebius:** run the Scout over the whole catalog with Nebius Serverless Jobs, and extend vector search from my own index to the live sources.
