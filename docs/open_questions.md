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

The vision only says that keyboard and screen-reader users can complete UJ-1 to UJ-6. The level comes from the
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
