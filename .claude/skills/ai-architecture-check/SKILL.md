---
name: ai-architecture-check
description: Use for every piece of architecture, design or review work, and when the /arch-check command runs. Holds the 15 layers of the AI architecture check (Model to Cost & Reliability) and how to report each as covered, gap or deferred.
---

# AI architecture check

For architecture, design or review work, report each layer as covered, gap (must fix) or
deferred (safe for now, say why).

| # | Layer | Look for |
|---|---|---|
| 1 | Model | model choice per task, NVIDIA-only rule, fallback when the provider is down |
| 2 | Agent Framework | agent loop, state, tool calling, workflow logic |
| 3 | Orchestration | durable steps, retries, timeouts, scheduling |
| 4 | Tool & API | MCP, external APIs, function calling, scoped access |
| 5 | Memory | session state, user context, what persists and for how long |
| 6 | Knowledge & RAG | retrieval, vector search, re-ranking, source grounding |
| 7 | Data | schema, migrations, object storage, provenance |
| 8 | Browser & Computer Use | any page or UI automation, and its limits |
| 9 | Guardrails | input validation, output checks, quote/evidence gates, policy |
| 10 | Observability | traces, model ids per answer, logs without secrets or PII |
| 11 | Evaluation | eval sets, adversarial set, regression on model or prompt change |
| 12 | Deployment | Helm, image, config, rollout and rollback |
| 13 | Security & Identity | authN/authZ, least privilege, secrets in OpenBao |
| 14 | Human-in-the-Loop | review queues, approval for irreversible actions |
| 15 | Cost & Reliability | caps, caching, rate limits, circuit breakers, degraded mode |
