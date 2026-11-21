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

The vision only says that keyboard and screen-reader users can complete UC-001, UC-005, UC-007, UC-009 and UC-012.
The level comes from the
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

## OQ-009: Should a list page on a directory host still be indexed as a list?

**Element:** UC-019 BR-001  
**Raised by:** /use-case-spec, 2026-11-21  
**Blocking:** Yes  
**Status:** Resolved  

A page with a list-like title is taken as a list before the host lists are read, so a list page of a host marked as
a directory is indexed without review. The rule says a page on a directory host is never kept; ADR 0013 documents
the order the code uses. Readings: (a) intended: a directory host stops product and article candidates only; (b) not
intended: every page of a directory host is dropped.

**Answer:** Not intended. A list page of a directory host is not indexed without review: it goes to the review
list as an article candidate, and an administrator decides.  
**Answered by:** the human in the loop, 2026-11-21  
**Resolved in:** UC-019 BR-001  

## OQ-010: Must the re-check also reject a verified licence, open source or platform whose quote does not state the value?

**Element:** UC-022 A9  
**Raised by:** /use-case-spec, 2026-11-21  
**Blocking:** Yes  
**Status:** Resolved  

The re-check tests pricing, type and use case. The enrichment rules require six fields to state their value:
licence, open source, platforms, pricing, type and use case. The success postcondition says every stored verified
fact passes the current rules. Readings: (a) the three other fields were always checked when written, so the
postcondition holds; (b) they were not: extend the re-check or narrow the postcondition.

**Answer:** Extend the re-check to every field that must state its value. None of the six is mandatory: a field a
resource does not have is not checked.  
**Answered by:** the human in the loop, 2026-11-21  
**Resolved in:** UC-022 A9  

## OQ-011: Is a search partial when the web search was skipped for the daily limit?

**Element:** UC-001 BR-007  
**Raised by:** /use-case-spec, 2026-11-21  
**Blocking:** No  
**Status:** Open  
**Assumed:** BR-007 says nothing about this case.  

A web search skipped for the daily limit counts as queried only when stored web results are shown, so the search is
partial with stored results and not partial without them. ADR 0022 says a search is partial when any enabled source
did not succeed. Readings: (a) intended, and BR-007 and the ADR say so; (b) the skip is treated the same way in both
cases.

## OQ-012: Should hosted apps stay hidden when their live source is off, fails or times out?

**Element:** UC-001  
**Raised by:** /use-case-spec, 2026-11-21  
**Blocking:** No  
**Status:** Open  
**Assumed:** a hosted app only the catalog returns is never shown, whatever the outcome of its source.  

The rule reads the source's setting, not its outcome, so a hosted-apps source that is off or failing hides every
hosted app only the catalog holds. ADR 0022 gives the reason (the catalog does not know if the app runs) but not
this case. Readings: (a) never show an unconfirmed app; (b) show them when the source could not answer.

## OQ-013: Should a report be refused for a resource hidden as a shadow library?

**Element:** UC-009 A2  
**Raised by:** /use-case-spec, 2026-11-21  
**Blocking:** No  
**Status:** Open  
**Assumed:** A2 as written: only a resource that does not exist is refused.  

The report checks only that the resource exists, so a report on a hidden resource is stored and opens a review item.
UC-007 treats such a resource as not in the catalog. Its page cannot be reached, so only a direct call gets there.
Readings: (a) refuse as not found; (b) keep.

## OQ-014: Is up to 30 seconds acceptable before a new shadow library is hidden on the public servers?

**Element:** UC-004 BR-007  
**Raised by:** /use-case-spec, 2026-11-21  
**Blocking:** No  
**Status:** Open  
**Assumed:** yes: the public servers read the list every 30 seconds.  

The change is made in the back office and the public servers reload the list every 30 seconds. Readings: (a) fine;
(b) the change is pushed to the public servers.

## OQ-015: Should the specification state the network's share of free AI answers?

**Element:** UC-005 BR-005  
**Raised by:** /use-case-spec, 2026-11-21  
**Blocking:** No  
**Status:** Open  
**Assumed:** no: only the visitor's cap is stated.  

Answers given back are capped at 5 a day for a visitor and at 50 a day for a network; the 50 is derived and has no
stated reason. Readings: (a) state the derived rule; (b) leave it out.

## OQ-016: Should the page show the person as signed out after an account deletion that failed?

**Element:** UC-011 A6  
**Raised by:** /use-case-spec, 2026-11-21  
**Blocking:** No  
**Status:** Open  
**Assumed:** the sign-in has ended and the account stays.  

The sign-in ends first, then the account is deleted (ADR 0036). When the deletion fails, the page says the account
could not be deleted and still looks signed in until the next request. Readings: (a) keep; (b) the page shows the
signed-out state and says so.

## OQ-017: Should a move above 500 saved resources say so and stop offering to try again?

**Element:** UC-012 A4  
**Raised by:** /use-case-spec, 2026-11-21  
**Blocking:** No  
**Status:** Open  
**Assumed:** the page shows the general message and the resources stay in the browser.  

The server refuses with the limit; the page shows only that it could not sync, with a try-again that is refused
every time. Readings: (a) keep the general message; (b) show the limit and no retry.

## OQ-018: Should the list kept in the browser show the most recently saved first, like the account?

**Element:** UC-012 BR-007  
**Raised by:** /use-case-spec, 2026-11-21  
**Blocking:** No  
**Status:** Open  
**Assumed:** the order is stated for the account only.  

The account list is newest first; the browser list is in the order of saving. Readings: (a) the browser order is not
specified; (b) both are newest first.

## OQ-019: Should the Privacy page read the two known-target proposal numbers from the running application?

**Element:** UC-015 BR-001  
**Raised by:** /use-case-spec, 2026-11-21  
**Blocking:** No  
**Status:** Open  
**Assumed:** BR-001 states the rule of ADR 0041; the page is right while the defaults hold.  

The Privacy text types the owner threshold (5) and the daily number of new names (25). Both can be changed by
configuration and are not in the policy the pages read, against ADR 0041. Readings: (a) add both to the policy; (b)
make both fixed.

## OQ-020: Should the review research purge log how many runs it deleted?

**Element:** UC-029  
**Raised by:** /use-case-spec, 2026-11-21  
**Blocking:** No  
**Status:** Open  
**Assumed:** every purge job logs its count, as ADR 0010 says.  

Every purge job logs what it deleted except the review research job. Readings: (a) add the log line; (b) weaken the
statement.

## OQ-021: Why 500 saved resources and 200 for one move, and do the Terms state the limit?

**Element:** UC-012 BR-002  
**Raised by:** /use-case-spec, 2026-11-21  
**Blocking:** No  
**Status:** Open  
**Assumed:** the numbers as they are.  

ADR 0036 states the numbers without a reason, and the Terms page does not state the limit of 500. Readings: (a) keep
and add the reason to the ADR; (b) also state the limit in the Terms.

## OQ-022: Is a discovery host a host discovery may read?

**Element:** FR-026  
**Raised by:** /use-case-spec, 2026-11-21  
**Blocking:** No  
**Status:** Open  
**Assumed:** the use case describes what the hosts do: they sort pages into writing and directories.  

FR-026 and the glossary say an administrator names the hosts discovery may read. The hosts sort pages; they are not
an allow-list. Readings: (a) reword the requirement and the glossary term; (b) the allow-list was meant and is not
built.

## OQ-023: How many model calls may one enriched resource take?

**Element:** UC-021  
**Raised by:** /use-case-spec, 2026-11-21  
**Blocking:** No  
**Status:** Open  
**Assumed:** three.  

One enrichment takes up to three calls (records, read-me, official pages), as ADR 0014 says. ADR 0011 says at most
twice as many calls. Readings: (a) ADR 0011 is corrected; (b) the cap is two.

## OQ-024: What does the run listing show?

**Element:** UC-018 step 13  
**Raised by:** /use-case-spec, 2026-11-21  
**Blocking:** No  
**Status:** Open  
**Assumed:** the latest run of each source.  

The listing shows the latest run of each source. ADR 0011 says it lists the last 50 runs. Readings: (a) the ADR is
corrected; (b) the listing shows the last 50.

## OQ-025: Should the waiting view show the order the Scout really takes?

**Element:** UC-022 BR-001  
**Raised by:** /use-case-spec, 2026-11-21  
**Blocking:** No  
**Status:** Open  
**Assumed:** both orders are stated, each in its use case.  

A Scout batch takes accepted candidates second and gives a quarter to the oldest resources never run; the view shows
neither (ADR 0038). The contract says the view is in the order the Scout takes. Readings: (a) accept and correct the
contract; (b) the view shows the parts.

## OQ-026: Why these floors for indexing without review?

**Element:** UC-018 BR-006  
**Raised by:** /use-case-spec, 2026-11-21  
**Blocking:** No  
**Status:** Open  
**Assumed:** the numbers as configured.  

1,000 stars and a change in 90 days for topic leaders, 20 likes for trending models, 10 likes for models found in
lists. The specification states no reason. Readings: (a) state the reason; (b) set them from measurement.

## OQ-027: Should a resource that changed be enriched again after it ran out of retries?

**Element:** UC-021 BR-010  
**Raised by:** /use-case-spec, 2026-11-21  
**Blocking:** No  
**Status:** Open  
**Assumed:** yes: a changed resource is tried again.  

A resource whose text changed is tried again in that run even after three failed runs. UC-022 presents out of
retries as final until an administrator puts it back. Readings: (a) intended, the text changed; (b) the cap applies
there too.

## OQ-028: Should the daily cap of list reads count tries, not lists?

**Element:** UC-030 BR-004  
**Raised by:** review of pull request #990, 2026-11-21  
**Blocking:** No  
**Status:** Open  
**Assumed:** it counts lists.  

At most 10 lists discovery added are read per day. The count is taken from the last try of each list, so a page
that fails and is tried again on the same day counts once: a day can go past 10 reads. Three tries a page and the
Scout's daily credits bound it. Readings: (a) accepted, the bounds are enough; (b) count every try, which needs a
counter of its own.
