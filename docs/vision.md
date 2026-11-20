# Vision: helper2.ai

## Mission

New AI models, tools, agents, MCP servers and papers appear every day. General search engines mix them with marketing
pages and old content, so it is hard to know what is real and current.

helper2.ai helps people find the right AI resource for their need, and shows the proof behind every answer. It is a
metasearch for the AI world only (tools, models, MCP servers, agents, prompts, libraries, papers and news), on one
page with one box. Typing searches every source. Asking the guide answers on the same page, only from the results it
retrieved, with a quote that code has checked word for word.

## Target users

- **Everyday user** (marketing manager, student, knowledge worker; often not technical, often not an English
  speaker): needs a trustworthy, current tool for a task such as writing, translating or summarizing, without jargon.
- **Developer and AI builder** (backend engineer, agent developer): needs an MCP server, a model, a library, an agent
  skill or a prompt that works with a given framework, with its license, its health and real "works with" facts.
- **Signed-in user**: a visitor who signs in with GitHub to keep saved resources across devices. An account is
  never needed to search or ask.
- **Administrator** (the maintainer): keeps the catalog honest through the back office: the review queue, the Scout
  runs, source health, runtime limits.

## Goals

- One query returns aggregated results from every enabled source that responded within its timeout.
- Every configured source appears in the source status list with a truthful outcome.
- No invented dates, popularity or facts: an unknown value is labeled as unknown.
- Every published AI-extracted fact links to its evidence; an unsupported fact is unverified or goes to review.
- Search works with the cache down, the language model down, or one or more sources down.
- Asking the guide never loses the query or the filters.
- Keyboard-only and screen-reader users can search, ask, evaluate, report and save (UJ-1 to UJ-6).
- The model cost stays bounded: per-client and global daily caps on questions and model calls, and AI answers cached.

## Scope

### In scope

- Federated search over a disclosed set of AI sources and an own catalog (full-text and vector search), with
  filters, categories and sorting, in any query language.
- The AI guide: picks at most a few catalog resources for a need, each with a checked quote and a reason.
- Resource pages with provenance, evidence quotes, relationships and setup guides from the tool's own documentation.
- The AI Resource Scout: collects new resources from the sources and enriches them, each claim backed by evidence.
- Human review in the back office: candidates, reported errors, relationships, field values, research help.
- Reports of wrong or outdated information from any visitor.
- Optional GitHub sign-in to save resources; saving works in the browser without an account.
- Hub pages per resource type for search engines, the news list and upcoming AI events.
- The Mission, Privacy and Terms pages, and voluntary support of the site.
- Every AI-written text labeled in the UI and marked machine-readable in the API and the HTML.

### Out of scope

- Searching the whole web: helper2.ai searches only its disclosed sources.
- Judging that a quoted claim is true: a quote proves that the source said it.
- Fine-tuning or a substantial change of a model.
- Selling data, or personalized ads and analytics without consent in Europe.
- A UI in other languages than English (queries work in any language).

## Constraints

- LLMs are NVIDIA models only (Nemotron on Nebius Token Factory); the embedding model runs in the cluster.
- The model never decides what is true: retrieval first, the model second, a deterministic validator last.
- GDPR, Swiss nFADP, ePrivacy, EU AI Act (Art. 50 marking of AI text), DSA, and the terms of every source and API.
- Servers in the EU. The back office is reachable only over the VPN, with GitHub as identity provider.
- Kubernetes with Helm charts for staging and production, secrets in OpenBao, no dependency on a specific cluster.
- The source code is public under the Apache-2.0 license.
- Built and run by one person, so every choice weighs operational cost and cognitive load.

## Success measures

- The product success criteria SC-1 to SC-7 in [the user journeys](user-journeys.md) hold, each checked by the tests
  named there.
- The evaluation runs ([evaluation](evaluation.md)): search relevance, and for the guide the answer rate, the
  questions that must stay empty and do, P@3 and nDCG@3 of its picks, the picks the validator kept and rejected, and
  latency p50 / p90. A search change must not let nDCG@10 fall more than 0.005 below the stored baseline.
- Latency stays within the budgets of the load test for search and the guide's search-only path.
- Each user journey states how we know it worked (for example gifts and views of the Support page, p95 time to
  results before and after an ad slot).
