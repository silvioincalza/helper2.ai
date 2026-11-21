# Use Case: Manage the Enrichment Queue

## Overview

**Use Case ID:** UC-022  
**Use Case Name:** Manage the Enrichment Queue  
**Primary Actor:** Administrator  
**Goal:** Decide which resources the Scout enriches next, which it leaves out, and which it tries again, and re-check the stored answers against the current rules, so that the daily model budget goes to the resources that matter.  
**Trigger:** An administrator opens the enrichment queue of the back office.  
**Status:** Draft  

**Requirements:** [FR-029](../../requirements.md)  
**Mockup:** [Screens and states](../../mockups/back-office.md)  
**API:** [listEnrichmentQueue](../../../api/admin.yaml#listEnrichmentQueue), [prioritiseEnrichment](../../../api/admin.yaml#prioritiseEnrichment), [skipEnrichment](../../../api/admin.yaml#skipEnrichment), [requeueEnrichment](../../../api/admin.yaml#requeueEnrichment), [recheckEnrichments](../../../api/admin.yaml#recheckEnrichments)

## Preconditions

- Administrator is signed in to the back office with the ADMIN role.

## Main Success Scenario

1. Administrator opens the enrichment queue and chooses a view: waiting, skipped, or out of retries.
2. System shows one page of the chosen view in the order of the view, with each resource's state, first discovery, failed runs, last outcome and, for a skipped one, when and why it was skipped.
3. Administrator moves to the next page.
4. Administrator chooses an action for one resource: move it to the top, skip it with an optional reason, or put it back in the queue.
5. System checks the resource's current place in the queue against the action.
6. System applies the action: a resource moved to the top is taken in the next batch, a skipped resource leaves the queue until it is put back, and a resource put back takes its own place again.
7. System records the action and its result in the audit trail.

## Alternative Flows

### A1: Unreadable Page Position

**Trigger:** The page position sent with the request is not one this queue gave (step 3)  
**Flow:**

1. System tells the administrator that the position is not from this queue.
2. Use case continues at step 1.

### A2: Resource Not Found

**Trigger:** The resource is not in the catalog or not eligible for enrichment (step 5)  
**Flow:**

1. System tells the administrator that the resource is not found and records the attempt in the audit trail.
2. Use case ends.

### A3: Resource Does Not Wait

**Trigger:** The administrator moves to the top or skips a resource that does not wait in the queue (step 5)  
**Flow:**

1. System tells the administrator that the resource does not wait in the enrichment queue and records the attempt in the audit trail.
2. Use case continues at step 4.

### A4: Already Skipped

**Trigger:** The administrator skips a resource that is already skipped (step 5)  
**Flow:**

1. System tells the administrator that the resource is already skipped and records the attempt in the audit trail.
2. Use case continues at step 4.

### A5: Already at the Top

**Trigger:** The administrator moves to the top a resource that already waits there (step 5)  
**Flow:**

1. System tells the administrator that the resource is already at the top and records the attempt in the audit trail.
2. Use case continues at step 4.

### A6: Top Full

**Trigger:** The maximum number of resources already wait at the top (step 5)  
**Flow:**

1. System tells the administrator to try again after the next run and records the attempt in the audit trail.
2. Use case continues at step 4.

### A7: Nothing to Put Back

**Trigger:** The administrator puts back a resource that is neither skipped nor out of retries (step 5)  
**Flow:**

1. System tells the administrator that the resource is neither skipped nor out of retries and records the attempt in the audit trail.
2. Use case continues at step 4.

### A8: Skip Reason Too Long

**Trigger:** The skip reason is longer than 200 characters (step 4)  
**Flow:**

1. System refuses the skip and nothing changes.
2. Use case continues at step 4.

### A9: Re-check the Stored Answers

**Trigger:** The administrator asks to re-check the stored enrichment answers instead of acting on one resource (step 4)  
**Flow:**

1. System re-checks every resource with a judged enrichment, in batches of 200, with no model call.
2. System rejects each verified pricing, type or use case whose quotes do not state the value, and each AI field the resource's type does not keep.
3. System drops each summary sentence that fails the claim check.
4. System puts back in the backlog each resource that was enriched with an older prompt and still misses facts or a summary it has more text for.
5. System closes the review items that are left with nothing to decide.
6. System shows the counts of resources checked, fields rejected, sentences dropped, runs requested and items closed, and records them in the audit trail.
7. Use case ends.

### A10: Scout Run in Progress

**Trigger:** A Scout run is in progress when the administrator asks for the re-check of A9 (step 4)  
**Flow:**

1. System tells the administrator that a run is in progress and changes nothing.
2. Use case ends.

## Postconditions

### Success Postconditions

- A resource moved to the top is enriched in the next batch, within the daily enrichment budget.
- A skipped resource is out of the backlog, with the time and the reason, until it is put back.
- A resource out of retries that is put back counts only the failed runs from now on.
- After a re-check, every stored verified fact and summary sentence passes the current rules.
- The audit trail holds every action asked for on a resource and every re-check.

### Failure Postconditions

- The queue is unchanged when the action does not fit the resource's place in the queue.
- A re-check changes nothing while a Scout run is in progress.

## Business Rules

### BR-001: Order of the Waiting Queue

The waiting queue holds first the resources an administrator moved to the top, then the resources never enriched, then the resources whose model was unavailable and the failed ones with retries left; within each part the most popular come first, then the newest discovery. The skipped view shows the latest skip first; the out-of-retries view shows the newest discovery first.

### BR-002: Page Size

A page holds 20 resources by default and at most 100.

### BR-003: Top of the Queue

At most 50 resources wait at the top. A resource stays at the top until its next enrichment run. Moving a resource to the top changes the order, not the number of model calls.

### BR-004: Skip and Move to the Top Exclude Each Other

A skipped resource is never at the top; skipping a resource drops its move to the top. A skip reason has at most 200 characters, without control characters.

### BR-005: Out of Retries

A resource is out of retries when its last run failed and it has 3 failed runs since it was last put back (UC-021 BR-010). Putting it back resets the count.

### BR-006: Re-check Without the Model

The re-check calls no model and is idempotent: a second re-check changes nothing. It also runs once by itself when an instance with the Scout switched on starts and it has never completed before; it never runs at the same time as a Scout run, so it waits for a later start while a run is in progress.
