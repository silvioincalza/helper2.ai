# Cost analysis

All prices are **list prices checked 2026-10-22** with quotes and URLs in
[research/sources-and-costs.md](research/sources-and-costs.md). Nothing has been purchased.
Exchange rates and taxes are not applied. "UNKNOWN" means no official figure was found.

## Assumptions (launch)

- Traffic: ≤ 50k searches/month, ≤ 5 concurrent users typical. One app instance.
- Cache hit rate for live sources ≥ 50% (popular queries repeat; 30 min fresh TTL).
- Catalog < 1 GB in PostgreSQL in year 1 (metadata only, short excerpts, no snapshots).
- Scout: runs every 15 min (`helper2.scout.every`); MCP Registry sync is hourly inside it. Each run
  enriches at most 20 resources (`helper2.scout.max-enrichments-per-run`), new ones first, then backlog.
- Enrichment prompt ≈ 1,500 input tokens per resource (estimate, not measured). Output is the open
  variable: Nemotron 3 Super reasons before it answers, so one call returns between ≈ 400 tokens
  (answer only) and 8,192 (`helper2.llm.enrich-max-tokens`, the cap).

## 1. Infrastructure (monthly)

| Item | Budget option (open-source / self-run) | Managed option | Notes |
|---|---|---|---|
| App host | Hetzner CX23 €5.99 excl. VAT (**listed as unavailable** at check time); Fly.io 1 GB shared-cpu $6.70 | Render Starter $7 (512 MB) — likely too small for JVM; $25 (2 GB) | JVM needs ~1 GB (the Helm chart limits the app to 1 GiB) |
| PostgreSQL | Same VPS (Postgres in container): $0 extra, you operate backups | Neon Launch $0.106/CU-h + $0.35/GB-mo (no minimum); Supabase Pro from $25; AWS RDS db.t4g.micro ≈ $11.68 + $0.115/GB-mo | Neon/Supabase free tiers exist but pause/limit (Supabase pauses after 1 week idle) |
| Redis | Same VPS container: $0 extra | Upstash free 500K commands/mo, then $0.20/100K; ElastiCache Serverless Redis ≈ $6.13/mo floor | Optional: app degrades to live-only |
| Domain helper2.ai | — | Porkbun $82.70 registration/renewal, **2-year minimum** for .ai (whether $82.70 is per year is unclear on the page) | Registrar choice open; Cloudflare Registrar price UNKNOWN |
| TLS | Let's Encrypt: $0 | Included with most PaaS | |
| Auth (OAuth) | GitHub OAuth App: $0 | Auth0 free ≤ 25,000 MAU; Clerk free ≤ 50,000 MRU | ADR 0023: one GitHub OAuth App per environment |

**Indicative totals:** self-run on one small VPS ≈ €6–10/mo + domain; fully managed small setup
≈ $45–70/mo (app $25 + Supabase $25 or Neon usage + Upstash free) + domain. Both exclude VAT and backup storage.

## 2. External APIs / connectors

| Source | Cost | Limit that matters |
|---|---|---|
| GitHub REST search | Free | 10 req/min unauth, 30 with token (search-specific) → cache + token essential |
| Hugging Face Hub | Free | 500 req / 5 min anonymous per IP, 1,000 with free token |
| npm registry | Free | No numeric limit published; 5M req/month called unreasonable |
| Official MCP Registry | Free (CC0 data) | No numeric limit published; sync hourly as requested |
| LLM enrichment (optional) | NVIDIA Nemotron on Nebius Token Factory only. Default `nvidia/nemotron-3-super-120b-a12b`: $0.30 / $0.90 per MTok. Cheaper option `Nemotron-3-Nano-30B-A3B`: $0.06 / $0.24 | Upper bound: the daily cap `H2_SCOUT_DAILY_ENRICHMENT_CALLS` (default 300). The Helm chart runs the Scout only on prod: once a day (`app.scoutEvery: P1D`; a restart does not add a run), ≤ 200 enrichments per run. Staging has no Scout. Prod ≤ 300 × 30 = 9,000 calls/mo. At the per-call cost of the earlier estimate (Super, 1,500 input tokens: ≈ $0.0008 at 400 output tokens, ≈ $0.0078 at the 8,192 cap), 9,000 calls/mo ≈ **$7–70/mo**. The bound holds only while the backlog stays full: once it is drained, each run enriches only new or changed resources. Thinking is off for enrichment, so most calls should be near the low end; measure output tokens per call before launch |
| Tavily (optional, `H2_TAVILY_API_KEY`) | Free tier 1,000 credits/month; 1 credit per basic search | Live news search (30 min cache) plus the Scout's discovery queries (~24 credits/month). Public redisplay needs Tavily's written OK ([sources.md](sources.md)) |
| Other web search APIs | Not used | Paid; terms to verify |

## 3. Ongoing maintenance (people time, estimates)

| Activity | Effort (estimate) |
|---|---|
| Review queue (reports, conflicts, relationship candidates) | 1–3 h/week at MVP volume |
| Connector upkeep (API changes, live smoke tests) | ~2 h/month per source; spikes on breaking changes |
| Curated seed upkeep | 1–2 h/month |
| Dependency/security updates (Boot, Spring AI, React, images) | 2–4 h/month |
| Backups/restore drills, monitoring | 1–2 h/month (more if self-hosting the DB) |

## Open-source vs paid options

- **Open-source path (recommended for MVP):** PostgreSQL, Redis, GitHub OAuth, self-hosted on one VPS or a Kubernetes cluster. Lowest cash cost, highest ops responsibility. The LLM stays NVIDIA Nemotron on Token Factory (open weights, paid API).
- **Paid managed path:** Neon/Supabase/RDS, Upstash/ElastiCache, Render/Fly. Less ops; costs scale with usage. Same LLM: NVIDIA Nemotron on Token Factory.
- No paid service is enabled by default; LLM calls are off unless profile `llm-nebius` is active.
