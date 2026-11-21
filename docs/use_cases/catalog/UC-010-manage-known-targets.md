# Use Case: Manage Known Targets

## Overview

**Use Case ID:** UC-010  
**Use Case Name:** Manage Known Targets  
**Primary Actor:** Administrator  
**Goal:** Keep the list of vendors, services and tools that a relationship may name without a catalog resource, so relationships to them are verified and the rest go to review.  
**Trigger:** The administrator opens the known vendors, services and tools on the Settings page of the back office.  
**Status:** Draft  

**Requirements:** [FR-035](../../requirements.md)  
**Mockup:** [Screens and states](../../mockups/back-office.md)  
**API:** [listKnownTargets](../../../api/admin.yaml#listKnownTargets), [addKnownTarget](../../../api/admin.yaml#addKnownTarget), [changeKnownTarget](../../../api/admin.yaml#changeKnownTarget), [removeKnownTarget](../../../api/admin.yaml#removeKnownTarget)

## Preconditions

- The administrator is signed in to the back office with the administrator role.

## Main Success Scenario

1. Administrator opens the known vendors, services and tools.
2. System lists every known target by kind and name, each with its other names, who added it and when, whether a person typed it or accepted it from a proposal, and how many relationships carry it, together with the largest number of targets the list may hold.
3. Administrator adds a target with its kind, its name and its other names.
4. System checks the names, checks that no other target holds any of them and that the list is not full.
5. System stores the target with every name, records the addition in the audit trail and shows the new entry.
6. Administrator changes the kind, the name or the other names of a target.
7. System checks the names as in step 4, sends the relationships under every dropped name back to review, gives the relationships under the kept names the new kind, stores the new names and records the change in the audit trail with the number of relationships it released.
8. Administrator chooses to remove a target; System says how many relationships carry it and asks for confirmation; Administrator confirms.
9. System sends every relationship that carries the target back to review, removes the target with all its names and records the removal in the audit trail with the number of relationships it released.

## Alternative Flows

### A1: Unusable Name

**Trigger:** The name is empty, shorter than 2 or longer than 60 characters, not one plain line, or comes with more than 20 other names (step 4)  
**Flow:**

1. System says what a usable name is; an empty name is caught before anything is sent.
2. Use case continues at step 3.

### A2: Name Already Taken

**Trigger:** Another known target already holds one of the names (step 4)  
**Flow:**

1. System says which name already means which target and stores nothing.
2. Use case continues at step 3.

### A3: List Full

**Trigger:** The list already holds the largest number of targets (step 4)  
**Flow:**

1. System says that the list is full and stores nothing.
2. Use case ends.

### A4: Target No Longer Exists on Change

**Trigger:** The target to change is not in the list any more (step 7)  
**Flow:**

1. System says that the known target was not found and changes nothing.
2. Use case ends.

### A5: Target No Longer Exists on Removal

**Trigger:** The target to remove is not in the list any more (step 9)  
**Flow:**

1. System says that the known target was not found and changes nothing.
2. Use case ends.

### A6: Removal Cancelled

**Trigger:** The administrator does not confirm the removal (step 8)  
**Flow:**

1. System closes the question and keeps the target.
2. Use case ends.

## Postconditions

### Success Postconditions

- The known target is stored with every name that means it, and each name belongs to exactly one target.
- A relationship under a dropped or removed name is unverified, no longer names a kind of target, and has one open relationship candidate in the review queue.
- A relationship under a kept name carries the new kind of the target.
- The audit trail holds one entry per addition, change or removal with its outcome and the number of relationships it released.
- The open relationship candidates that name an added target are confirmed at the end of the next Scout run.

### Failure Postconditions

- No known target, name or relationship is changed.
- The audit trail records the refused attempt with its outcome.

## Business Rules

### BR-001: Three Kinds of Target

A known target is a vendor (a company or organisation), a service (something a resource connects to, such as Slack or GitHub) or a tool (an application a resource runs in or plugs into, such as an editor or a desktop app). A relationship that names a known target is verified without a target resource in the catalog.

### BR-002: Usable Names

A name holds 2 to 60 characters on one plain line, without control or invisible formatting characters, and a target has at most 20 other names.

### BR-003: Names Compared as Keys

Names are compared without case, with repeated spaces as one space and without a final "'s"; each such key belongs to one target only.

### BR-004: Size of the List

The list holds at most 1,000 known targets.

### BR-005: A Change Releases the Dropped Names

A change works as a removal for every name it drops: each relationship carrying a dropped or removed name becomes unverified, loses its kind of target and gets an open relationship candidate with the reason that its target is no longer a known vendor, service or tool.

### BR-006: Kept Names Take the New Kind

When a change sets a new kind, the relationships under the names the target keeps take the new kind.

### BR-007: No Crossing with Enrichment

A change to the list and the saving of an AI enrichment never interleave, so no relationship stays verified under a name that is gone.

### BR-008: Every Attempt Is Audited

Every addition, change and removal, also a refused one, is one entry in the admin audit trail with its outcome.

### BR-009: Origin of a Target

A target is curated when a person typed it, and discovered when a person accepted a Scout proposal for it; the first 31 vendors ship with the system and are recorded as added by the migration.
