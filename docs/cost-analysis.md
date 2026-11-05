# Cost analysis

All prices are **list prices checked 2026-10-22** with quotes and URLs in
[research/sources-and-costs.md](research/sources-and-costs.md).
Exchange rates and taxes are not applied. "UNKNOWN" means no official figure was found.

## What runs

- One Kubernetes cluster, servers in the EU ([ADR 0027](adr/operations/0027-runtime-topology.md)). Prod runs 3 app
  replicas, staging 1. Each replica asks for 250m CPU and 768 MiB, and may use up to 1 GiB.
- PostgreSQL and Redis run in the cluster from the same Helm chart: no managed database, no managed cache. Redis is
  capped at 128 MB.
- Live sources are cached (30 min fresh). Tavily answers from its stored result sets instead: 24 h for time-bound
  questions, 48 h otherwise.
- The Scout runs once a day on prod (`app.scoutCron`, 03:07 UTC), with at most 200 resources enriched per run
  (`app.scoutMaxEnrichments`). Staging has no Scout.
- Enrichment prompt ≈ 1,500 input tokens per resource (estimate, not measured). Output is the open
  variable: Nemotron 3 Super can reason before it answers, so one call returns between ≈ 400 tokens
  (answer only) and 8,192 (`helper2.scout.enrich-max-tokens`, the cap).

## 1. Infrastructure (monthly)

| Item | Cost | Notes |
|---|---|---|
| Cluster (app, PostgreSQL, Redis) | The platform's cost, outside this repo | The chart runs on any Kubernetes cluster; the deploy stays generic |
| Image registry, secrets, ingress, observability | The platform's cost, outside this repo | Harbor, OpenBao, ingress-nginx, the observability stack |
| Off-site backup copy (R2, when turned on) | R2 storage for the dumps | Not on yet ([roadmap](roadmap.md#2-operations)) |
| Domain helper2.ai | Porkbun $82.70 registration/renewal, **2-year minimum** for .ai (whether $82.70 is per year is unclear on the page) | |
| TLS | Let's Encrypt: $0 | |
| Auth (OAuth) | GitHub: $0 | One GitHub app per environment ([ADR 0035](adr/edge/0035-admin-host.md)) |

## 2. External APIs / connectors

| Source | Cost | Limit that matters |
|---|---|---|
| GitHub REST search | Free | 10 req/min unauth, 30 with token (search-specific) → cache + token essential |
| Hugging Face Hub | Free | 500 req / 5 min anonymous per IP, 1,000 with free token |
| npm registry | Free | No numeric limit published; 5M req/month called unreasonable |
| Official MCP Registry | Free (CC0 data) | No numeric limit published; asks for hourly polling, read once a day (the Scout runs daily) |
| LLM enrichment (optional) | NVIDIA Nemotron on Nebius Token Factory only. Default `nvidia/nemotron-3-super-120b-a12b`: $0.30 / $0.90 per MTok. Cheaper option `Nemotron-3-Nano-30B-A3B`: $0.06 / $0.24 | Upper bound: the daily cap `H2_SCOUT_DAILY_ENRICHMENT_CALLS` (default 500), gap-round calls included (`H2_SCOUT_GAP_DAILY_CALLS`, 400, [ADR 0043](adr/scout/0043-enrichment-gap-round.md)). The Helm chart runs the Scout only on prod: once a day (`app.scoutCron`, 03:07 UTC; a restart does not add a run), ≤ 200 resources enriched per run (up to twice as many calls with gap rounds). Staging has no Scout. Prod ≤ 500 × 30 = 15,000 calls/mo. At the per-call cost of the earlier estimate (Super, 1,500 input tokens: ≈ $0.0008 at 400 output tokens, ≈ $0.0078 at the 8,192 cap), 15,000 calls/mo ≈ **$12–117/mo**. The bound holds only while the backlog stays full: once it is drained, each run enriches only new or changed resources. Thinking is off for enrichment, so most calls should be near the low end; measure output tokens per call before launch |
| Tavily (optional, `H2_TAVILY_API_KEY`) | Plan with 14,000 credits/month (free tier: 1,000); 1 credit per basic search, 2 per advanced | Store first ([ADR 0037](adr/search/0037-tavily-store-and-daily-quota.md)): a question costs one credit, then its stored result set answers everyone for 24 h (time-bound) or 48 h. Each visitor gets 3 web searches a day for search and 3 for the guide, so one visitor spends at most 6 credits a day; `H2_TAVILY_GLOBAL_DAILY_CREDITS` caps the total when set, prod sets it to 250 a day and staging to 150, and an alert fires above 200 credits in 24 h. Fixed use: the Scout ~506 credits/month (news ~42, courses ~43, discovery ~335: 39 searches a week at `search_depth advanced`, 78 credits a week, [ADR 0051](adr/scout/0051-tavily-discovery.md); LinkedIn ~86: 20 searches a week at `search_depth basic`, 20 credits a week, [ADR 0053](adr/scout/0053-linkedin-via-tavily.md)) and the weekly live check ~13. Discovery and LinkedIn spend 98 credits a week together, capped at 40 a day, and count on the same global daily counter as the live calls, so a day's searches (about 12 credits, about 32 on LinkedIn's day, at most 40) come off the live cap too. In a 31-day month prod (≤ 7,750), staging (≤ 4,650), the Scout (≤ ~580 with 5 passes) and the live check (~13) come to ≈ 12,990, about 1,010 under the 14,000 Tavily gave its written OK for public display, storage and reuse ([sources.md](sources.md)) |
| Other web search APIs | Not used | Paid; terms to verify |

### Guide questions: cost per question (estimate, not yet measured)

A fresh guide question makes 3 model calls: plan (Nano), choose (Super), reason check (Super); a retry adds one.
A repeated question is answered from the 24-hour cache and costs nothing. Thinking is off for all three steps.

| Step | Model | Input (estimate) | Output cap | Cost at the estimate | Cost at the output cap |
|---|---|---|---|---|---|
| Plan | Nano $0.06 / $0.24 | ≈ 2.5k tokens (prompt 9.6 KB) | 2,048 | ≈ $0.0002 | ≈ $0.0006 |
| Choose | Super $0.30 / $0.90 | ≈ 3–5k tokens (prompt 4.5 KB + candidates) | 8,192 | ≈ $0.0015–0.0025 | ≈ $0.009 |
| Reason check | Super $0.30 / $0.90 | ≈ 1.5k tokens (prompt 2 KB + picks) | 4,096 | ≈ $0.0006 | ≈ $0.004 |
| **Per question, no retries** | | | | **≈ $0.002–0.0035** | **≈ $0.014** |

Input tokens are prompt size / 4 plus an assumed candidate list; outputs at the estimate are 200–800 tokens. One measured choose call with thinking off returned
97 completion tokens (1,772 with thinking on; [hackathon/feedback.md](hackathon/feedback.md)), so the estimate is on the high side.
**Daily ceiling:** the global cap `H2_GUIDE_DAILY_MODEL_CALLS` counts calls, not questions (600 calls/day). Each step
may retry once and every attempt takes a call, so the call mix is not fixed at one plan, one choose and one reason check.
The conservative ceiling prices all 600 calls as the most expensive one, a Super choose at its 8,192-token output cap
(≈ $0.009 per call with ≈ 5k input tokens): **≈ $5.5/day, ≈ $165/month**. Output tokens are bounded by the caps;
input tokens are still an estimate, so this ceiling is too. At the per-question estimate, 200 questions/day cost
≈ $0.5/day, ≈ $15/month.

**Measuring it:** every call counts its tokens in `helper2_llm_tokens_total{step,prompt,type}` (Prometheus, on the
management port, scraped into Grafana). The evaluation runs of 29 Oct 2026, 00:07–00:28 UTC
([evaluation.md](evaluation.md), [evaluation-adversarial.md](evaluation-adversarial.md)) are the window to read: 28 fresh
questions (19 + 8, plus one first attempt whose answer was cached), so the per-question figure is the increase over
that window divided by 28. Not read yet: this session had no metrics access.

## 3. Ongoing maintenance (people time, estimates)

| Activity | Effort (estimate) |
|---|---|
| Review queue (reports, conflicts, relationship candidates) | 1–3 h/week at MVP volume |
| Connector upkeep (API changes, live smoke tests) | ~2 h/month per source; spikes on breaking changes |
| Curated seed upkeep | 1–2 h/month |
| Dependency/security updates (Boot, Spring AI, React, images) | 2–4 h/month |
| Backups/restore drills, monitoring | 1–2 h/month (more if self-hosting the DB) |

## Open-source vs paid options

- **What runs:** PostgreSQL, Redis and GitHub sign-in, self-run on a Kubernetes cluster. Lowest cash cost, highest ops
  responsibility. The LLM is NVIDIA Nemotron on Token Factory (open weights, paid API).
- **Paid managed services** (a managed database or cache, a platform as a service) are not used.
- No paid service is enabled by default; LLM calls are off unless profile `llm-nebius` is active.
