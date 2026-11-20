# Use Case: Research Review Items

## Overview

**Use Case ID:** UC-027  
**Use Case Name:** Research Review Items  
**Primary Actor:** Administrator  
**Secondary Actors:** Scheduler, Language Model, Source Provider  
**Goal:** Have an AI research agent check resource and course candidates on the web and propose a verdict and a value for each attribute with its source quotes, so the Administrator decides faster without anything reaching the catalog unconfirmed.  
**Trigger:** The Administrator starts research for picked candidates, for a page of the review queue, or for every open candidate.  
**Status:** Draft  

**Requirements:** [FR-034](../../requirements.md)  
**API:** [startReviewResearch](../../../api/admin.yaml#startReviewResearch), [listReviewResearch](../../../api/admin.yaml#listReviewResearch), [estimateReviewResearch](../../../api/admin.yaml#estimateReviewResearch), [getReviewResearch](../../../api/admin.yaml#getReviewResearch), [cancelReviewResearch](../../../api/admin.yaml#cancelReviewResearch), [streamReviewResearch](../../../api/admin.yaml#streamReviewResearch)

## Preconditions

- The Administrator is signed in to the back office with the ADMIN role, over the VPN.
- Research is on for the back office.
- The review queue holds open resource or course candidates.

## Main Success Scenario

1. The Administrator chooses to research every open candidate.
2. The system shows how many items it would queue, the most model calls they can take, and how many open candidates are already researched.
3. The Administrator confirms.
4. The system queues one research run for each open resource or course candidate that has no queued or running run, and each row of the queue shows its research as queued.
5. The Scheduler takes the oldest queued run while today's budget still allows one full run, and marks it running.
6. The system sends the candidate to the Language Model together with read-only research tools.
7. The Language Model asks for tool calls; the system runs each one against a Source Provider (reading a web page, searching the web, looking up a package, a GitHub repository or a Hugging Face entry, checking an address) or against the catalog, cleans the answer as untrusted text, stores it as a step, and sends it back.
8. The Language Model answers with a verdict (keep, reject with a reason, or duplicate of a listed resource) and a value for each attribute, each with the quotes it cites.
9. The system checks every citation against what the tools returned, computes the status of each attribute (confirmed, one source, conflict or not found), stores the checked result and marks the run done.
10. The Administrator follows the run live or opens its steps, and sees the proposal labelled as proposed with AI: the verdict, its reason, and for each attribute the value now, the proposed value, its status and its source quotes.
11. The Administrator confirms, edits or keeps each changed value, a conflict after picking one of its values.
12. The Administrator accepts the item with the confirmed values, as UC-024 accepts a candidate.

## Alternative Flows

### A1: Research picked items or a page

**Trigger:** The Administrator picks items, or the current page, instead of every open candidate (step 1)  
**Flow:**

1. The system leaves out the items that are already researched or running and says how many are new.
2. Use case continues at step 4.

### A2: Research is off

**Trigger:** Research is switched off for the back office (step 4)  
**Flow:**

1. The system queues nothing and says that research is off.
2. Use case ends.

### A3: Request is not valid

**Trigger:** The Administrator gives both picked items and every open candidate, neither, or more than 500 items (step 4)  
**Flow:**

1. The system queues nothing and says why.
2. Use case ends.

### A4: Daily budget is spent

**Trigger:** Fewer model calls are left today than one run can take, or the budget runs out during a run (step 5)  
**Flow:**

1. The system leaves the runs queued; a run stopped half way goes back to the queue without its steps.
2. The runs start when the budget resets.
3. Use case continues at step 5.

### A5: Cancel a run

**Trigger:** The Administrator cancels a queued or running run (step 5)  
**Flow:**

1. The system marks the run cancelled; a running run stops before its next step.
2. Use case ends.

### A6: Run has already ended

**Trigger:** The Administrator cancels a run that is done, failed or cancelled (step 5)  
**Flow:**

1. The system refuses and says that the run has ended.
2. Use case ends.

### A7: Run fails

**Trigger:** The Language Model is unavailable, the run takes longer than 3 minutes, the model still asks for tools after its last call, its answer cannot be read twice, or the item is no longer an open candidate (step 7)  
**Flow:**

1. The system marks the run failed with the reason, and the row shows it.
2. The Administrator may research the item again.
3. Use case ends.

### A8: Run lost its worker

**Trigger:** A running run has stored no step for 5 minutes (step 7)  
**Flow:**

1. The Scheduler marks the run failed.
2. Use case ends.

### A9: Tool calls are used up

**Trigger:** The model made its 12th tool call, or two turns in a row ran no call (step 7)  
**Flow:**

1. The system gives the Language Model one more call with no tools and asks it to answer with what it has read.
2. Use case continues at step 8.

### A10: Too many live views

**Trigger:** The back office already serves its most live views of research runs (step 10)  
**Flow:**

1. The system refuses the live view and asks the Administrator to close one and try again.
2. Use case continues at step 10.

### A11: Reject the items the AI marked as reject

**Trigger:** The Administrator chooses to reject the items of the page whose verdict is reject (step 11)  
**Flow:**

1. The system lists those items with their reasons, labelled as written with AI.
2. The Administrator unticks any item and confirms.
3. The system rejects the remaining items as UC-024 rejects several items at once.
4. Use case ends.

## Postconditions

### Success Postconditions

- Each queued item has a research run, and a finished run holds its verdict, the checked value of each attribute and every step, request and response.
- The catalog changes only when the Administrator accepts the item with the values confirmed.

### Failure Postconditions

- No research run changes a review item or the catalog.
- A cancelled or failed run keeps its steps and its reason, and the item stays open.

## Business Rules

### BR-001: What can be researched

Only an open resource candidate or an open course candidate held as a structured candidate is researched; other items are skipped. An item has at most one queued or running run at a time; researching it again starts a new run.

### BR-002: How much one request queues

Picked items are at most 500 at a time. Every open candidate means every open resource and course candidate whose newest run is not done, at most 20,000, chosen by the system, and the estimate is shown before anything starts.

### BR-003: Limits of one run

A run makes at most 12 tool calls and at most 14 model calls, and stops after 3 minutes. Web search is called at most 4 times in a run. A call past these limits, or the same call made a second time, is not run and still counts. One more call is allowed when the final answer is not in the asked format.

### BR-004: Daily caps

The research agent makes at most 3,000 model calls a day by default, a limit an administrator changes at runtime, and reads at most 300 pages a day by default. A run starts only while 14 model calls are left today. A spent page-read cap does not stop a run: the page read answers with an error and the model goes on.

### BR-005: Workers

Each back office runs 4 research workers by default and looks for queued runs every 2 seconds. Two back offices never run the same item.

### BR-006: Read-only tools

The agent only reads: web pages, web search results (leads, not evidence), package registries, GitHub, Hugging Face, address checks and the catalog. Private, loopback, link-local, reserved and documentation addresses are refused, address checks use secure web addresses only and stop after 20 seconds by default, and a shadow library is never read.

### BR-007: Untrusted text

Every tool answer is cleaned of hidden text, tags, links, contacts and instruction lines before the model or the check sees it. An answer with no text left is no evidence.

### BR-008: Status of an attribute

The status of each attribute is computed by the system, not by the model. A citation counts only when its excerpt of 20 to 300 characters is found word for word in the evidence it cites, and for licence, open source, pricing and type when the excerpt states the value. An attribute is confirmed when an authoritative source supports it (the item's own repository for licence and open source, the item's own site for the other attributes) or two sources on different hosts agree; one source when only one other source supports it; conflict when supported values disagree; not found otherwise.

### BR-009: Attributes researched

The research proposes the verdict, canonical address, title, type, audience, tech level, pricing, open source, licence, description, tags, language, published date, and the listed resource an item duplicates.

### BR-010: Applying the proposal

Accept is possible only when every changed value has a decision the candidate can take; each value is checked against the rules of a candidate edit (UC-024 BR-006) before it is confirmed. Keep and duplicate are decided one item at a time; only reject verdicts are applied to several items together, after the Administrator confirms the list.

### BR-011: Steps are stored and shown as text

Every model call, tool call and check is stored as a step with its request, response, duration and tokens; a request or response is cut at about 200 KB and an error at 2,000 characters. Steps are shown as plain text only.

### BR-012: Live view

The live view sends each stored step and each status change of one run, reads new steps once a second, keeps a quiet view open every 15 seconds, and ends with the run. A back office serves at most 8 live views at a time by default. The queue rows refresh their research every 2 seconds while a run is active.

### BR-013: Marking of AI-written text

The proposal is labelled "Proposed with AI" and marked as AI-written with its model; the model's answers in the steps are labelled as written with AI.

### BR-014: Retention

Research runs and their steps are deleted 90 days after the run ends, and together with their review item.
