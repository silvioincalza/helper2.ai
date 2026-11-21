---
name: performance-rules
description: Use when something is slow, before any optimisation, and before a change on a hot path (search, the guide, a query, an index, a cache, the JVM, the frontend bundle). Holds the measure-first rule and the latency budgets rule.
---

# Performance

- Performance: measure first (profile, JMH, load test, EXPLAIN ANALYZE), set latency budgets
  (p50/p95/p99), show numbers before and after. No optimisation without a measured problem.
- Load tests run locally or on staging, never on prod.
