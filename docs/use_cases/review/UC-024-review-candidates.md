# Use Case: Review Candidates

## Overview

**Use Case ID:** UC-024  
**Use Case Name:** Review Candidates  
**Primary Actor:** Administrator  
**Goal:** Decide what the machine could not decide on its own (new resource, course, example and known target candidates, enrichment checks, unverified relationships and reported errors), so that only checked content reaches the catalog.  
**Trigger:** The Administrator opens the back office to work through what needs a person.  
**Status:** Draft  

**Requirements:** [FR-031, FR-032, NFR-012](../../requirements.md)  
**API:** [getAdminOverview](../../../api/admin.yaml#getAdminOverview), [listReviewItems](../../../api/admin.yaml#listReviewItems), [getReviewEvidence](../../../api/admin.yaml#getReviewEvidence), [resolveReviewItem](../../../api/admin.yaml#resolveReviewItem), [resolveReviewItems](../../../api/admin.yaml#resolveReviewItems), [editCandidate](../../../api/admin.yaml#editCandidate), [editKnownTargetCandidate](../../../api/admin.yaml#editKnownTargetCandidate), [changeRelationshipTarget](../../../api/admin.yaml#changeRelationshipTarget)

## Preconditions

- The Administrator is signed in to the back office with the ADMIN role, over the VPN.
- Other parts of the system have opened review items: enrichment runs, relationship extraction, user reports, discovery of new pages, drafted home examples and known target proposals.

## Main Success Scenario

1. The Administrator opens the back office overview.
2. The system shows how many review items are open for each kind, zero included, each count linked to the review queue filtered by that kind.
3. The Administrator opens the review queue and filters it by status, kind, discovery category or research verdict.
4. The system shows one page of review items, newest first, with the total number of matching items, and selects the first item.
5. The Administrator selects an item.
6. The system shows the item's details and its evidence: for an item from an enrichment run, the run, its validation errors and the fields it stored, conflicts first, each with its source quotes; for a relationship candidate, the run and the relationship with its quotes; for any other item, the item alone. Every value a model wrote is labelled as written with AI.
7. The Administrator accepts or rejects the item, with an optional note.
8. The system records the decision with the Administrator, the time and the note, and applies its effect for the kind of the item.
9. The system remembers the decision for the candidate's address or the proposed name, records the decision in the audit trail and selects the next open item.

## Alternative Flows

### A1: Edit a resource or course candidate before deciding

**Trigger:** The selected item is an open resource or course candidate whose title, type, tags or other values are wrong (step 6)  
**Flow:**

1. The Administrator changes the title, type or tags, or confirms other values such as the canonical address, pricing, licence, audience, tech level, description, language or published date.
2. The system checks the values, saves them into the candidate, marks the changed fields as edited by an administrator and rewrites the item's reason line.
3. The system records the edit in the audit trail. The item stays open.
4. Use case continues at step 6.

### A2: Candidate changed while it was being edited

**Trigger:** A collector or another administrator changed the candidate after the Administrator opened it (step 6)  
**Flow:**

1. The system refuses the edit and asks the Administrator to reload the candidate and edit it again.
2. Use case continues at step 6.

### A3: Edited candidate value is not allowed

**Trigger:** An edited value breaks a rule of the catalog, or no value was changed (step 6)  
**Flow:**

1. The system refuses the edit, names the field and the reason, and saves nothing.
2. Use case continues at step 6.

### A4: Edit a known target proposal before deciding

**Trigger:** The selected item is an open known target proposal whose name, kind or other names are wrong (step 6)  
**Flow:**

1. The Administrator changes the kind, the name and the other names.
2. The system checks the names, saves the proposal and records the edit in the audit trail. The item stays open.
3. Use case continues at step 6.

### A5: Known target proposal names are not valid

**Trigger:** A name is too short, too long, not on one plain line, or there are too many other names (step 6)  
**Flow:**

1. The system refuses the edit, states the rule for names and saves nothing.
2. Use case continues at step 6.

### A6: Change the target of a relationship candidate

**Trigger:** The selected item is an open relationship candidate whose target is not the right resource (step 6)  
**Flow:**

1. The Administrator picks a resource found with the catalog search, adds a new resource with its name, official address and type, or gives another name with no resource.
2. The system points the relationship at the chosen target and marks the target as chosen by a person; the kind of the relationship stays written with AI.
3. For a new resource whose address the catalog already lists, the system uses the listed resource instead of adding a second one; otherwise it adds the resource for the next enrichment run.
4. The system rewrites the item's reason line and note and records the change in the audit trail. The item stays open.
5. Use case continues at step 6.

### A7: Relationship target change is refused

**Trigger:** The item is no longer open or is not a relationship candidate, the resource already has a relationship of that kind to that target, the chosen resource does not exist or is the resource itself, the address is not a secure web address, the type is not a tool or a model, or the name is empty or too long (step 6)  
**Flow:**

1. The system refuses the change and says why; a resource added for the change is not kept.
2. Use case continues at step 6.

### A8: Item is no longer open

**Trigger:** The item was already decided by another decision or superseded by a newer enrichment run, or it no longer exists (step 8)  
**Flow:**

1. The system changes nothing and tells the Administrator the item's current status, or that it was not found.
2. The system records the refused decision in the audit trail.
3. Use case ends.

### A9: Accepted page is already listed

**Trigger:** The Administrator accepts a resource or course candidate whose address another source listed in the meantime (step 8)  
**Flow:**

1. The system adds nothing to the catalog and does not apply the candidate's edits.
2. The system closes the item as accepted with the note "Already listed as" and the title of the listed resource, and links to that resource.
3. Use case continues at step 9.

### A10: Accepted item cannot be added

**Trigger:** The candidate, proposal or drafted example cannot be read, the catalog refuses a confirmed value, the proposed name cannot be a known target or already means another target, the list of known targets is full, or the drafted example duplicates or breaks the rules of another example (step 8)  
**Flow:**

1. The system adds nothing, explains why, and leaves the item open.
2. Use case ends.

### A11: Decide several items at once

**Trigger:** The Administrator wants to accept or reject many open items together (step 7)  
**Flow:**

1. The Administrator picks open items on the page and chooses accept or reject.
2. The system asks for confirmation and repeats the number of picked items for each kind.
3. The Administrator confirms.
4. The system decides each item on its own, in the order picked, and keeps an item it may not accept open.
5. The system shows how many items were decided and how many failed, and why, for each item; the items that failed stay picked.
6. Use case ends.

### A12: An accepted item of a batch cannot be added

**Trigger:** During a decision on several items, an accepted candidate cannot be read or the catalog refuses one of its confirmed values (step 7)  
**Flow:**

1. The system stops the batch and explains the problem; the items decided before it stay decided and the items after it are not tried.
2. Use case ends.

## Postconditions

### Success Postconditions

- The review item is accepted or rejected, with the Administrator, the time of the decision and the note.
- An accepted resource or course candidate is in the catalog with its confirmed values as the resource's own values; an accepted known target proposal is a known target; an accepted drafted example is a home example waiting for its check.
- The relationship of a decided relationship candidate is verified when accepted and rejected when rejected.
- The decision on a candidate's address or proposed name is kept, so the same candidate is not proposed again.
- The audit trail holds one entry for each decided, edited or changed item.

### Failure Postconditions

- A refused decision, edit or target change leaves the item open and unchanged.
- Nothing is added to the catalog for an item that is not accepted.

## Business Rules

### BR-001: Kinds of review items

A review item is one of seven kinds: an enrichment validation (what an enrichment run stated that failed a check or contradicts a source), a relationship candidate (a relationship whose target is not a known resource), a user report, a course candidate, a resource candidate (a page found by discovery and not yet in the catalog), a drafted home example, and a known target candidate (a vendor or service a rule proposes).

### BR-002: Statuses of review items

A review item is open, accepted, rejected or superseded. Only a person accepts or rejects. An item is superseded when a newer enrichment run of the same resource replaces it, or when a decision by target name closes it (UC-025). An item has a decision time exactly when it is no longer open.

### BR-003: Decisions are final

Only an open item can be decided, edited or have its target changed. There is no undo. Accepting an item locks it first, so what is added is what was decided.

### BR-004: Effect of accepting by kind

Accepting a resource or course candidate indexes its page in the catalog with the title, type and tags it holds, at its confirmed canonical address. Accepting a known target proposal adds the known target, or adds its names to the target of the same kind that already holds its name. Accepting a drafted example adds it as a home example. Accepting a relationship candidate verifies the relationship, and rejecting it rejects the relationship. Accepting an enrichment validation or a user report closes the item and adds nothing.

### BR-005: A decided candidate is not proposed again

The decision on a resource, course, example or known target candidate is kept for its address or its name key and kind. A page whose address is already listed or already decided is never proposed again, and there is at most one item for each kind and address and one proposal for each name key.

### BR-006: Values of a candidate edit

An edit changes at least one value. The title is 1 to 300 characters on one line. The type is a candidate type (PRODUCT, AGENT, PROMPT, SKILL, MCP_SERVER, LIBRARY, MODEL, DATASET, ARTICLE, NEWS, EVENT, COURSE, AWESOME_LIST), never OTHER, and need not be one the finders declare. There are at most 12 tags, each at most 40 characters of lower-case words joined by hyphens. The canonical address is a secure web address of at most 2,000 characters. Pricing is FREE, FREEMIUM, PAID or OPEN_SOURCE_SELF_HOSTED; the licence is an SPDX identifier; the audience is USE_AI, BUILD_WITH_AI or BOTH; the tech level is BEGINNER, INTERMEDIATE or ADVANCED; the description is 1 to 4,000 characters; the language is one the catalog takes; the published date is not in the future.

### BR-007: No lost update on a candidate

An edit applies only to the version of the candidate the Administrator saw. When a collector or another administrator changed the candidate since, the edit is refused.

### BR-008: An administrator's edit is kept

When another collector finds the page later, it joins the candidate's finders but does not change an edited type or edited tags, and never the title. The edited fields are kept with the accepted record.

### BR-009: Confirmed values become the resource's own values

On accept, every confirmed value of a candidate becomes a curated value of the new resource, as UC-026 sets it. A description written by the model and confirmed as it is stays marked as written with AI; a description the Administrator wrote is curated.

### BR-010: Names of a known target proposal

A name is 2 to 60 characters on one plain line, with at most 20 other names. A proposed name that already means another target is refused, and the catalog holds at most 1,000 known targets.

### BR-011: Target of a relationship candidate

A change gives exactly one of: a resource of the catalog, a new resource (a name of 1 to 300 characters, its official secure web address, and a type that is a tool or a model: PRODUCT, MODEL, AGENT, PROMPT, SKILL, MCP_SERVER, LIBRARY or DATASET), or another name of 1 to 120 characters. A relationship never points at its own resource, and a resource has at most one relationship of a kind to a target. The item stays open after the change, and a later enrichment run neither supersedes it nor deletes its relationship.

### BR-012: Deciding several items at once

One call decides at most 100 items, each named once. Rejecting is allowed for every kind. Accepting is allowed only for an enrichment validation whose run has no conflicting licence, open source or pricing, and for a resource or course candidate whose newest research is done with the verdict keep, has no conflicting value and still describes the candidate (UC-027); its confirmed changed values are applied to the candidate first. Every other item is not allowed and stays open. Each item is decided on its own, so one failure does not undo the others.

### BR-013: The overview

The overview shows the open review items of every kind, zero included, next to the other numbers that need a person. It is computed at most once a minute.

### BR-014: Paging and filters of the queue

A page holds 1 to 100 items, 20 by default, newest first. The category filter matches only resource and course candidates carrying that discovery category. The research filter keeps items by the verdict of their newest research (UC-027).

### BR-015: Audit of review actions

Every decision is recorded in the audit trail with its outcome (accepted, rejected, not open, not found or not allowed) and, for a batch, the batch identifier. Every candidate or proposal edit, relationship target change and resource added for it is recorded. A refused edit or target change is not recorded, since nothing changed.

### BR-016: Notes

A decision note is at most 1,000 characters.

### BR-017: Marking of AI-written text

Every value a model wrote (the run's field values, its validation errors, the relationship's kind, and the label and query of a drafted example) is labelled as written with AI on the page and marked as AI-written in the answer, with the model that wrote it. Source quotes are labelled as source quotes.

### BR-018: Retention of decided items

A decided review item is kept for 180 days by default after its decision, unless it belongs to a user report.
