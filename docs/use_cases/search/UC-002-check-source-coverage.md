# Use Case: Check Source Coverage

## Overview

**Use Case ID:** UC-002  
**Use Case Name:** Check Source Coverage  
**Primary Actor:** Visitor  
**Goal:** Understand which sources helper2.ai searches, what each covers and how well each works right now, so the visitor can judge the results  
**Trigger:** The visitor opens the Sources page  
**Status:** Draft  

**Requirements:** [FR-005, C-015](../../requirements.md)  
**Mockup:** [Screens and states](../../mockups/uc-002-check-source-coverage.md)  
**API:** [listSources](../../../api/common.yaml#listSources)

## Preconditions

- At least one source is configured.

## Main Success Scenario

1. Visitor opens the Sources page.
2. System lists every configured source, ordered by priority and then by name.
3. System shows for each source its mode, whether it is enabled and metered, the types of resources it covers, its homepage, its terms, its attribution and its family.
4. System shows for each source its last outcome and when it was checked, from the last searches or from the last catalog collection runs.
5. System shows for each called source its response time, and for an enabled source whose calls are paused after repeated failures, since when it is paused or that it is trying again.
6. System shows for the metered web source the web credits all visitors used today and the daily cap.
7. System shows for each source how many resources it brought to the catalog in the last 7 days.
8. Visitor reads the list and the statement that helper2.ai searches only the listed sources.

## Alternative Flows

### A1: Source Never Called

**Trigger:** A live source has no outcome yet (step 4)  
**Flow:**

1. System shows that the source has had no calls yet.
2. Use case continues at step 5.

### A2: Recorded Outcomes Unavailable

**Trigger:** The outcomes of the catalog collection runs cannot be read (step 4)  
**Flow:**

1. System shows only the outcomes of the last searches.
2. Use case continues at step 5.

### A3: Web Credits Unavailable

**Trigger:** Today's web credits cannot be read (step 6)  
**Flow:**

1. System shows that the web credits are not available right now.
2. Use case continues at step 7.

### A4: New Resource Counts Unavailable

**Trigger:** The counts of new resources cannot be read (step 7)  
**Flow:**

1. System shows the list without the counts.
2. Use case continues at step 8.

### A5: Refresh Fails

**Trigger:** The visitor returns to the page after a minute or more and the list cannot be read again (step 2)  
**Flow:**

1. System keeps the list shown before and says when it was read.
2. Use case ends.

## Postconditions

### Success Postconditions

- The visitor sees every configured source with its coverage and current health.

### Failure Postconditions

- No data is changed.

## Business Rules

### BR-001: Every Configured Source Listed

The list holds every configured source, also a disabled one, ordered by priority and then by identifier.

### BR-002: New Resources Window

"New this week" counts the resources a source brought to the catalog in the last 7 days.

### BR-003: Pause State Only for Enabled Sources

The pause state after repeated failures is shown only for an enabled source.

### BR-004: Web Credits Only for the Metered Source

Today's web credits are shown only for a metered source that is enabled; they count the credits of all visitors together, with the daily cap of UC-001 BR-008.

### BR-005: Refresh on Return

The page reads the list again when the visitor returns to it after a minute or more, and keeps the old list on screen meanwhile.
