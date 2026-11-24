# Screens and states: Check Source Coverage

Screen notes for UC-002: what the page shows, step by step and in each state. The behaviour is in the use case.

## Understand coverage and sources

1. **Sources** page lists every configured source: mode (LIVE, INDEXED, CURATED, DEMO), what it covers, homepage, terms, attribution, last outcome and check time. A called source also shows its response time ("No calls yet" on a live source before its first call). A source whose circuit is open says "Paused after repeated failures, since HH:MM:SS UTC. It tries again on its own."; half open, "Trying again". The metered web source shows today's web credits for all visitors ("312 of 1,000 (all visitors)", "312 (no daily cap)" or "Not available right now"). The summary counts the last outcome only: a paused source is not counted as limited. One line under the list says response time and pauses come from the server that answered the page. Back on the tab after a minute or more, the page asks again and keeps the list on screen meanwhile; if that fails, it keeps the old list and says "Could not refresh; showing the list from HH:MM UTC".
2. Footer on every page: "Searchavo.ai searches the sources listed here — not the whole web."

| State | Behavior |
|---|---|
| Loading | Skeleton rows of the table on the first load. Back on the tab after a minute or more, the page asks again and keeps the list on screen meanwhile. |
| Success | Every configured source with its mode, what it covers, terms, attribution, last outcome and check time. |
| Empty | A live source before its first call says "No calls yet". |
| Partial | A source whose circuit is open says "Paused after repeated failures, since HH:MM:SS UTC. It tries again on its own."; web credits the server cannot read say "Not available right now". |
| Error | The refresh fails: the page keeps the old list and says "Could not refresh; showing the list from HH:MM UTC". |
