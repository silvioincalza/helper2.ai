# Use Case: Review the Administrator Audit Log

## Overview

**Use Case ID:** UC-013  
**Use Case Name:** Review the Administrator Audit Log  
**Primary Actor:** Administrator  
**Goal:** See who changed what in the back office, when, why and with which outcome, so that every administrator action can be traced  
**Trigger:** The administrator opens the audit page of the back office  
**Status:** Draft  

**Requirements:** [FR-039, NFR-012](../../requirements.md)  
**Mockup:** [Screens and states](../../mockups/back-office.md)  
**API:** [listAuditEntries](../../../api/admin.yaml#listAuditEntries)

## Preconditions

- The administrator is signed in on the back office with the administrator role (UC-011 BR-005).

## Main Success Scenario

1. Administrator opens the audit page.
2. System shows the first page of audit entries, the newest first, with the total number of entries.
3. System shows for each entry the administrator, the action, its target, the time, the outcome, the value before and after, and the reason.
4. Administrator moves to another page.
5. System shows the entries of that page.

## Alternative Flows

### A1: The Page Request Is Not Valid

**Trigger:** The page number is below 0, or the page size is below 1 or above 100 (step 4)  
**Flow:**

1. System refuses the request.
2. Use case ends.

## Postconditions

### Success Postconditions

- The administrator has seen the requested entries; the log is unchanged.

### Failure Postconditions

- The audit log is unchanged.

## Business Rules

### BR-001: Recorded Actions

The log records these administrator actions: resolving a review item, starting a Scout run, adding, changing, removing and drafting home examples, checking, requeueing, skipping and prioritising enrichment, backfilling categories, changing a setting, adding and removing a discovery host, changing a relationship target, adding and removing a resource, editing a candidate, setting and dropping a field, adding, editing and removing a known target, adding and removing a shadow library, and adding and removing a list seed.

### BR-002: The Log Cannot Be Changed

An audit entry is never changed after it is written. Entries are removed only after their retention period (UC-029 BR-003).

### BR-003: Pages

Entries are shown the newest first, 20 per page by default and at most 100 per page.

### BR-004: Entry Content

The value before and the value after are at most 100 characters each, and the reason at most 500 characters.
