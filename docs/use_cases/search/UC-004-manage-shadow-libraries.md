# Use Case: Manage Shadow Libraries

## Overview

**Use Case ID:** UC-004  
**Use Case Name:** Manage Shadow Libraries  
**Primary Actor:** Administrator  
**Goal:** Keep the list of shadow libraries (sites that share copyrighted books and papers without permission) so that no result, answer or document from them is ever shown  
**Trigger:** The administrator opens the shadow library list in the back office settings  
**Status:** Draft  

**Requirements:** [FR-036](../../requirements.md)  
**Mockup:** [Screens and states](../../mockups/back-office.md)  
**API:** [listShadowLibraries](../../../api/admin.yaml#listShadowLibraries), [addShadowLibrary](../../../api/admin.yaml#addShadowLibrary), [removeShadowLibrary](../../../api/admin.yaml#removeShadowLibrary)

## Preconditions

- The administrator is signed in to the back office with the administrator role.

## Main Success Scenario

1. Administrator opens the shadow library list.
2. System shows every listed name with who added it, when, and why, and the largest number of names the list can hold.
3. Administrator enters a name and, optionally, a reason.
4. System checks the reason and the name, stores the name, and records the action in the audit trail with the reason.
5. System applies the new list at once, so the next search, guide answer and setup guide leave the site out.
6. Administrator removes a name, optionally with a reason.
7. System deletes the name, records the action in the audit trail with the reason, and applies the new list at once.

## Alternative Flows

### A1: Invalid Reason

**Trigger:** The reason holds control or hidden characters, or is longer than 500 characters (step 4)  
**Flow:**

1. System refuses the change and says a reason is one plain line of at most 500 characters.
2. Use case continues at step 3.

### A2: Invalid Name

**Trigger:** The name is empty, is not one part of a host name, is shorter than 3 characters, is too common, or is used by a host of a helper2.ai source (step 4)  
**Flow:**

1. System refuses the name and says which rule it breaks.
2. Use case continues at step 3.

### A3: Already Listed

**Trigger:** The name is in the list already (step 4)  
**Flow:**

1. System refuses the name and says it is listed already.
2. Use case continues at step 3.

### A4: List Full

**Trigger:** The list holds 200 names (step 4)  
**Flow:**

1. System refuses the name and asks the administrator to remove one first.
2. Use case continues at step 6.

### A5: Name Not Listed

**Trigger:** The name to remove is not in the list (step 7)  
**Flow:**

1. System says the name is not in the list and records the attempt in the audit trail.
2. Use case ends.

### A6: List Not Applied at Once

**Trigger:** The new list cannot be read again right after the change (step 5)  
**Flow:**

1. System keeps the change stored; the list is applied at the next periodic refresh.
2. Use case continues at step 6.

## Postconditions

### Success Postconditions

- The list holds the added name, or no longer holds the removed one.
- The change is recorded in the audit trail with the administrator, the name, the outcome and the reason.
- Results, cached guide answers and setup documents on a listed site are hidden from then on.

### Failure Postconditions

- A refused add or remove leaves the list unchanged.

## Business Rules

### BR-001: Name Format

A name is one part of a host name: 3 to 63 lower-case letters, digits and hyphens, starting and ending with a letter or a digit, without a dot. It is stored in lower case.

### BR-002: Names Too Common

A name that many sites use as part of their host name is refused: www, com, net, org, edu, gov, mil, int, biz, info, app, dev, api, cdn, web, blog, docs, news, mail and static.

### BR-003: Names of Sources

A name that is part of the host name of a helper2.ai source (such as github) is refused, so the list never hides a source.

### BR-004: Reason

A reason is optional. When given, it is one plain line of at most 500 characters without control or hidden format characters.

### BR-005: List Size

The list holds at most 200 names.

### BR-006: Shadow Libraries Hidden

A site is a shadow library when one part of its host name, before the last part, is a listed name: libgen matches libgen.rs and ka.libgen.is, not my-libgen-notes.dev. A result whose address or canonical address is on a shadow library is never shown in a search, a guide answer or a catalog card. A cached guide answer that cites such a site is not served, and a setup guide never reads a document on such a site. The list starts with nine seeded names (z-lib, z-library, zlibrary, 1lib, singlelogin, libgen, sci-hub, annas-archive, pdfdrive), which also apply when the list cannot be read at start.

### BR-007: Refresh

A change applies at once on the server that made it, and every server reads the list again every 30 seconds.

### BR-008: Audit

Every add and remove whose reason and name format pass their checks is recorded in the audit trail with the administrator, the name, the outcome (added, already listed, list full, removed, not found) and the reason.
