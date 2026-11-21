# Open Questions

Questions about the specification that the specification does not answer yet. A question is `Resolved` only when
the answer is in the specification itself; **Resolved in** names where. Resolved and withdrawn questions stay as
history.

## OQ-001: Is 5 requests per second the right peak rate for the search response time?

**Element:** NFR-001  
**Raised by:** /requirements, 2026-11-20  
**Blocking:** No  
**Status:** Open  
**Assumed:** the peak rate and thresholds of the load test: 5 requests per second, p50 1 s, p95 3 s, p99 4 s, under 1% failures.  

The vision names the load test budgets but no traffic level. The numbers come from the Gatling search simulation and
its default peak rate. Readings: (a) keep them; (b) set a peak rate from the measured production traffic.

## OQ-002: Is 5 requests per second the right peak rate for the guide's search-only path?

**Element:** NFR-002  
**Raised by:** /requirements, 2026-11-20  
**Blocking:** No  
**Status:** Open  
**Assumed:** the thresholds of the guide search-only load test: 5 requests per second, p50 3 s, p95 8 s, under 1% failures.  

The numbers come from the Gatling guide simulation. The full guide answer with model calls has no latency budget.
Readings: (a) keep the search-only budget only; (b) add a budget for a full answer.

## OQ-003: Is WCAG 2.2 level AA the accessibility target?

**Element:** NFR-009  
**Raised by:** /requirements, 2026-11-20  
**Blocking:** No  
**Status:** Open  
**Assumed:** WCAG 2.2 level AA, as the UI design guidance of the repository states.  

The vision only says that keyboard and screen-reader users can complete UC-001, UC-005, UC-007, UC-009 and UC-012. The level comes from the
ui-design agent description and the design system ADR.

## OQ-004: Which articles of the Digital Services Act apply?

**Element:** C-009  
**Raised by:** /requirements, 2026-11-20  
**Blocking:** No  
**Status:** Open  
**Assumed:** the DSA applies as a whole, with no article named.  

The repository names the DSA but no article. A qualified lawyer should name the duties that apply to helper2.ai.

## OQ-005: Are the priorities of the functional requirements right?

**Element:** FR-001  
**Raised by:** /requirements, 2026-11-20  
**Blocking:** No  
**Status:** Open  
**Assumed:** High for search, the guide, resource details, reports, review, legal and privacy duties; Medium for the
other user and back office features; Low for examples, events, support and search engine notices.  

The vision gives no priorities. FR-001 stands for every row of the Functional Requirements table.

## OQ-006: Which articles are the source of the named recipients constraint?

**Element:** C-016  
**Raised by:** the maintainer, 2026-11-21  
**Blocking:** No  
**Status:** Open  
**Assumed:** the articles in the Source column of C-016, as read by the author of the row.  

C-016 names articles of the GDPR and of the Swiss nFADP as its source. The numbers were read from the record of
recipients and transfers, not checked against the official texts. A qualified lawyer should confirm each article
or name the right one.

## OQ-007: Which parts of the design system page still describe the shipped results screen?

**Element:** UC-001  
**Raised by:** the maintainer, 2026-11-21  
**Blocking:** No  
**Status:** Open  
**Assumed:** where the design system page and ADR 0040 differ, ADR 0040 holds.  

The sections "Results page" and "Radar" of [the design system page](../frontend/design/README.md) are older than
[ADR 0040](architecture/decisions/ADR-0040-design-system.md). The page needs one pass that removes what the shipped
screens of UC-001 no longer do. Readings: (a) remove both sections and keep "Page rules"; (b) rewrite them from the
shipped screens.

## OQ-008: Which form does the record of what a coding assistant decides alone take?

**Element:** C-014  
**Raised by:** the maintainer, 2026-11-21  
**Blocking:** No  
**Status:** Open  
**Assumed:** the rules stay in `AGENTS.md` (sections "Working with the human in the loop" and "Hard rules").  

One person builds and runs the system with coding assistants, so what an assistant decides alone, what it asks
first and what it never does must be stated once. The process names a file `docs/architecture/decision-rights.md`
but defines no format for it. Readings: (a) keep the rules in `AGENTS.md` and write no file; (b) write the file as
three lists (decides alone, asks first, never does) and make `AGENTS.md` point at it.
