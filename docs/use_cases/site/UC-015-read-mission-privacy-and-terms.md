# Use Case: Read Mission, Privacy and Terms

## Overview

**Use Case ID:** UC-015  
**Use Case Name:** Read Mission, Privacy and Terms  
**Primary Actor:** Visitor  
**Goal:** Understand what helper2.ai is for, what data it keeps and for how long, and which terms apply, with the numbers the code really applies  
**Trigger:** The visitor opens Mission, Privacy or Terms from the footer  
**Status:** Reviewed  

**Requirements:** [FR-021, C-016](../../requirements.md)  
**Mockup:** [Screens and states](../../mockups/uc-015-read-mission-privacy-and-terms.md)  
**API:** [getPolicy](../../../api/public.yaml#getPolicy)

## Preconditions

- _None — the pages are public._

## Main Success Scenario

1. Visitor opens the mission, privacy or terms page.
2. System shows the text page with its title, the date of its last change for privacy and terms, a short lede, the contact and its sections.
3. System fills the text with the numbers the code applies: the candidates and picks of the AI guide, the daily limits, and how long each store keeps its data.
4. Visitor reads the page.

## Alternative Flows

### A1: The Numbers Cannot Be Loaded

**Trigger:** The numbers cannot be read (step 3)  
**Flow:**

1. System states the limits and periods in words without numbers and says in one line under the head that reloading the page shows them.
2. Use case ends.

## Postconditions

### Success Postconditions

- The visitor has read the page with the numbers in force.

### Failure Postconditions

- No wrong or typed-in number is shown in place of a number the code applies.

## Business Rules

### BR-001: Numbers Come from the Code

A number on the pages that a configuration variable can change is the value the running application started with, never a number typed into the text: the AI guide's maximum candidates and picks, the daily guide questions, free answers and web searches, the retention period of every store, and the facts about sources such as the number of categories and the length of quotes. A number that no variable can change, such as the lifetime of a sign-in (UC-011 BR-009), is written in the text.

### BR-002: Retention Periods Shown

The retention periods shown are the ones the daily purge applies (UC-029 BR-002, BR-003, BR-004, BR-005, BR-006, BR-008, BR-009, BR-011 and BR-014), plus the periods of the caches, counters, pages read and query vectors kept by the other modules.

### BR-003: Backups

The privacy page states that backups are kept 7 days by default (`H2_RETENTION_BACKUPS`).
