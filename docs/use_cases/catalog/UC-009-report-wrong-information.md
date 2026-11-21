# Use Case: Report Wrong Information

## Overview

**Use Case ID:** UC-009  
**Use Case Name:** Report Wrong Information  
**Primary Actor:** Visitor  
**Goal:** Tell the team that a fact on a resource page is wrong, outdated or broken, so a person reviews and corrects it.  
**Trigger:** The visitor chooses "Report a problem" on the page of a resource.  
**Status:** Draft  

**Requirements:** [FR-014](../../requirements.md)  
**Mockup:** [Screens and states](../../mockups/uc-009-report-wrong-information.md)  
**API:** [reportResource](../../../api/public.yaml#reportResource)

## Preconditions

- The visitor is on the page of a resource (UC-007).

## Main Success Scenario

1. Visitor chooses to report a problem.
2. System shows the report form: the kind of problem, an optional field and a required message.
3. Visitor chooses the kind, optionally names the field, writes the message and submits the report.
4. System checks the length of the message.
5. System checks that the resource is in the catalog.
6. System stores the issue report and, together with it, opens a user report item for the resource in the review queue.
7. System thanks the visitor and says that a person will review the report; nothing on the page changes.

## Alternative Flows

### A1: Message Too Short or Too Long

**Trigger:** The message, without leading and trailing spaces, has fewer than 5 or more than 2,000 characters (step 4)  
**Flow:**

1. System shows the error next to the message and keeps the text.
2. Use case continues at step 3.

### A2: Resource Not Found

**Trigger:** The resource is not in the catalog (step 5)  
**Flow:**

1. System says that the resource is not in the catalog.
2. Use case ends.

### A3: Rate Limit Reached

**Trigger:** The visitor has sent more reports than the limit allows (step 4)  
**Flow:**

1. System refuses the report and says when the visitor can try again.
2. Use case ends.

### A4: Report Cannot Be Sent

**Trigger:** The report cannot be delivered (step 6)  
**Flow:**

1. System shows the reason and keeps the text the visitor wrote.
2. Visitor submits again.
3. Use case continues at step 4.

## Postconditions

### Success Postconditions

- An issue report is stored for the resource with its kind, its field (when given), its message and the time it was made.
- An open review item of kind user report points at the resource and the issue report, so an administrator can handle it (UC-024).
- The resource itself is unchanged.

### Failure Postconditions

- Neither an issue report nor a review item is stored.
- The resource is unchanged.

## Business Rules

### BR-001: Kinds of Problem

A report is one of four kinds: wrong, outdated, broken link or other.

### BR-002: Message Length

The message is required and holds 5 to 2,000 characters after leading and trailing spaces are removed.

### BR-003: Optional Field

The field the report is about is optional and holds at most 60 characters; an empty or blank field counts as none.

### BR-004: A Report Changes Nothing by Itself

A report never changes the resource; it only opens a review item, with the reason "User report:" followed by the kind, which a person decides (UC-024).

### BR-005: Report and Review Item Together

The issue report and its review item are stored together or not at all.

### BR-006: Report Rate Limit

A visitor may send at most 20 reports per hour by default (H2_RATE_LIMIT_REPORT_PER_HOUR).

### BR-007: Retention of Reports

An issue report is kept while its review item is open, and deleted once every review item that points at it was resolved more than 365 days ago by default (H2_RETENTION_RESOLVED_ISSUE_REPORTS). A report is also deleted with its resource.

### BR-008: No Personal Data

An issue report stores no data about the visitor who sent it: only the resource, the kind, the field, the message and the time.
