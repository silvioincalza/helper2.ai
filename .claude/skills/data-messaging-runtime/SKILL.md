---
name: data-messaging-runtime
description: Use before adding or changing a database migration, a table, a store, a cache, a queue, an event, a stream (SSE, WebSocket) or a multi-step flow, and for every new data flow. Holds the rules on orchestration and choreography, storage and retention, and the names and immutability of Flyway migrations.
---

# Data, messaging and runtime

- Orchestration for multi-step flows with compensation; choreography for loose side effects.
  SSE for server streaming; WebSocket only for real two-way interaction.
- Adopt new storage, queues or streaming only for a concrete, named need.
- Every data flow: decide storage (relational, vector, graph), caching and retention period.
- Schema (ADR 0004 §5): a released migration never changes, `V1__schema.sql` included. A schema change is a new
  migration `V<version>.<n>__<what>.sql`: the version of the release that ships it, above the latest release tag, and
  `n` from 1 within it (`V2026.11.6.1__known_target_tool.sql`). A migration that did not ship in a release is renamed
  to the version after it. The seeds (`R__…`) may change. CI fails an edited or removed released migration and a new
  one with any other name (`.github/scripts/released-migrations.sh`).
- No backward compatibility with removed code or migrations unless asked.
- A change to a data flow also follows the skill `privacy-terms-ai-regulation`.
