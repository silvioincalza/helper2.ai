# Use Case: Manage Home Examples

## Overview

**Use Case ID:** UC-003  
**Use Case Name:** Manage Home Examples  
**Primary Actor:** Administrator, Scheduler  
**Secondary Actors:** Language Model, Source Provider  
**Goal:** Keep a pool of example searches on the home page where every shown example finds a clearly fitting result near the top  
**Trigger:** The administrator opens the home examples in the back office, or the time of a scheduled check arrives  
**Status:** Draft  

**Requirements:** [FR-037](../../requirements.md)  
**API:** [listAdminHomeExamples](../../../api/admin.yaml#listAdminHomeExamples), [addHomeExample](../../../api/admin.yaml#addHomeExample), [draftHomeExamples](../../../api/admin.yaml#draftHomeExamples), [changeHomeExample](../../../api/admin.yaml#changeHomeExample), [removeHomeExample](../../../api/admin.yaml#removeHomeExample)

## Preconditions

- The administrator is signed in to the back office with the administrator role.

## Main Success Scenario

1. Administrator opens the list of home examples, optionally only those with one check outcome.
2. System shows each example with its label, query, audience path, status, origin, drafting model, expected results with their titles, last check result and version.
3. Administrator adds an example with a label, a query, an audience path and one to three expected catalog resources.
4. System checks the input and that every expected resource is in the catalog, stores the example as active with its check pending, and records the action in the audit trail.
5. Scheduler checks every example whose check is pending, and every night all examples: it runs each example's search and records whether an expected resource is shown near the top, with its best rank and the number of results.
6. Administrator changes an example's label, query, audience path, expected results or status (pause or activate), giving the version they read.
7. System stores the change, sets the check back to pending when the search changed, and records the action in the audit trail.
8. Administrator removes an example.
9. System deletes the example and records the action in the audit trail.
10. Administrator asks for AI drafts.
11. System gives the Language Model well-known catalog resources that no example or waiting draft covers yet, keeps each valid proposed example, opens it as an example candidate in the review queue, and records the action in the audit trail.
12. Administrator accepts a draft in the review queue, and System adds it as an AI-drafted example with the name of the model that drafted it.

## Alternative Flows

### A1: Invalid Example

**Trigger:** A label, query or list of expected results breaks a rule (step 4)  
**Flow:**

1. System refuses the example and lists every problem.
2. Use case continues at step 3.

### A2: Expected Resource Not in the Catalog

**Trigger:** An expected resource is not in the catalog (step 4)  
**Flow:**

1. System refuses the example and names each unknown resource.
2. Use case continues at step 3.

### A3: Duplicate Label or Query

**Trigger:** Another example already has the same label or the same query (step 4)  
**Flow:**

1. System refuses the example and says that the label or query is taken.
2. Use case continues at step 3.

### A4: Example Not Found

**Trigger:** The example to change no longer exists (step 7)  
**Flow:**

1. System says that the example was not found.
2. Use case continues at step 1.

### A5: Changed by Someone Else

**Trigger:** The version given is not the current version of the example (step 7)  
**Flow:**

1. System refuses the change and asks the administrator to reload the example and make the change again.
2. Use case continues at step 6.

### A6: Search Degraded During a Check

**Trigger:** Some sources did not answer or the catalog was unavailable while an example was checked (step 5)  
**Flow:**

1. System records nothing for that example, which keeps its previous check result.
2. Use case continues at step 6.

### A7: Drafts Unavailable

**Trigger:** No model is configured or drafting is switched off, today's draft runs are used up, the model cannot answer, or its answer cannot be read (step 11)  
**Flow:**

1. System opens no draft, records the reason in the audit trail and tells the administrator why, with when to try again.
2. Use case ends.

### A8: Draft Cannot Be Read

**Trigger:** The accepted review item holds no readable draft (step 12)  
**Flow:**

1. System adds no example and reports the item as unreadable.
2. Use case ends.

## Postconditions

### Success Postconditions

- The example pool holds the added and changed examples, without the removed ones.
- Each checked example holds its latest check outcome, best rank, result count and check time.
- Drafts wait in the review queue as example candidates.
- Every add, change, removal and draft run is recorded in the audit trail with the administrator and its outcome.

### Failure Postconditions

- A refused add or change leaves the example pool unchanged.
- A failed draft run opens no review item.

## Business Rules

### BR-001: Label and Query

A label holds 2 to 32 characters and a query 3 to 200 characters, after spaces are collapsed. Neither holds control characters, the characters < or >, or a web address.

### BR-002: Expected Results

An example names 1 to 3 distinct expected catalog resources, each in the catalog. When an example is changed, only the newly added expected resources are checked against the catalog.

### BR-003: Unique Label and Query

No two examples of the same language share a label or a query, ignoring case. Examples added in the back office are in English.

### BR-004: Check Outcome

A check searches the example's query with its audience path, without the live web search, and looks at the first 10 results. The outcome is PASS when an expected resource is among the first 3, WEAK when the best is ranked 4 to 10, and FAIL otherwise. A check whose search was degraded records nothing.

### BR-005: Check Schedule

Pending examples are checked every 5 minutes, starting 1 minute after the start, and all examples are checked every night at 02:41 UTC, by default. Only one server runs a check at a time.

### BR-006: Check Reset

A change to the query, the audience path or the expected results sets the check back to PENDING and clears the rank and the result count; a change of label or status keeps the last check.

### BR-007: Optimistic Change

A change names the version it was made on; each stored change raises the version by one.

### BR-008: Examples Served on the Home Page

The home page gets only active examples whose check outcome is PASS, in the language asked for, at most 50, ordered by audience path and label. When none exist in that language, the English examples are served. An AI-drafted example served is marked as AI-written with the provider and the model, in its label and query.

### BR-009: Drafting

Drafting is on only when a model is configured and drafting has calls left. A day allows 5 draft runs by default. One run gives the model 12 well-known catalog resources by default (from 1 to 30), leaving out resources an example or a waiting draft already covers. A draft is kept only when it names one of the given resources, each resource at most once, follows BR-001, and does not repeat the label or query of an example or a waiting draft.

### BR-010: Origin

An example is BUILT_IN (seeded with the system), CURATED (added by an administrator) or AI_DRAFTED (accepted from a draft). An AI-drafted example always names the model that drafted it, and only an AI-drafted example names one.

### BR-011: Removal

Removing an example that does not exist changes nothing; the attempt is still recorded in the audit trail.
