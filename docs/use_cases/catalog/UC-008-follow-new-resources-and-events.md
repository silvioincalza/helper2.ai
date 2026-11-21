# Use Case: Follow New Resources and Events

## Overview

**Use Case ID:** UC-008  
**Use Case Name:** Follow New Resources and Events  
**Primary Actor:** Visitor  
**Goal:** See what is new in the catalog and which AI events are coming up, so the visitor can keep up and plan without searching.  
**Trigger:** The visitor opens the home page, the News page or the Events page.  
**Status:** Approved  

**Requirements:** [FR-012, FR-013](../../requirements.md)  
**Mockup:** [Screens and states](../../mockups/uc-008-follow-new-resources-and-events.md)  
**API:** [listRecent](../../../api/public.yaml#listRecent), [listUpcomingEvents](../../../api/public.yaml#listUpcomingEvents)

## Preconditions

- _None — the lists are public and need no sign-in._

## Main Success Scenario

1. Visitor opens the home page, the News page or the Events page.
2. System lists the newest resources whose source states a publication date inside the window of the page, newest first, with the total count: on the home page the tools and research of the last 7 days, on the News page the news of the last 7 days.
3. System lists the events whose source states their dates and that have not ended, soonest first, with the total count: on the home page the events of the next 90 days, on the Events page every event of the next 366 days, read page by page until the total is reached.
4. System shows each row with its title, its date, its type and its source; an event row also says how soon it starts, or that it is on now and until when.
5. Visitor opens a row to view the resource (UC-007).

## Alternative Flows

### A1: Nothing in the Window

**Trigger:** No resource or no event matches the window (step 2)  
**Flow:**

1. System says that the list has nothing in its window and points to the search box.
2. Use case continues at step 3.

### A2: Invalid Window or Filter

**Trigger:** The requested window, size, offset or type filter is outside its allowed range (step 2)  
**Flow:**

1. System refuses the request and says that it is invalid.
2. Use case ends.

### A3: Rate Limit Reached

**Trigger:** The visitor has asked for the new resources more often than the limit allows (step 2)  
**Flow:**

1. System refuses the request and says when the visitor can try again.
2. Use case ends.

### A4: List Cannot Be Loaded

**Trigger:** A list fails to load (step 2)  
**Flow:**

1. System shows an alert under the heading of the list with a way to try again.
2. Visitor tries again.
3. Use case continues at step 2.

## Postconditions

### Success Postconditions

- The visitor sees the newest resources and the upcoming events, each with its total count.
- The catalog is unchanged.

### Failure Postconditions

- The catalog is unchanged.

## Business Rules

### BR-001: Only Dates a Source States

The new resources are ordered by the publication date their source states, from the start of the window up to now; the date the system discovered a resource never counts. A resource without a stated publication date is never in this list.

### BR-002: Only Indexed or Observed Resources

The new resources list holds only resources the system indexed or observed from its sources; curated and demonstration resources are not listed.

### BR-003: New Resources Window and Size

The window of the new resources is 14 days by default and from 1 to 30 days; the list returns 12 resources by default and at most 60, and may be filtered by at most 20 resource types. The total counts every match, also those not returned.

### BR-004: Upcoming Events

Only resources of type event whose source states a start are listed. An event is listed while its end, or its start when no end is stated, is on or after the start of today in UTC. With a window, only events that start before the end of the window, counted from the start of today, are listed; the window is from 1 to 366 days. Events are ordered by start, then title. A page returns 6 events by default and at most 500, and may skip up to 100,000 events.

### BR-005: Shadow Libraries Are Excluded

A resource whose address is on a listed shadow library is never counted or listed (UC-007 BR-003).

### BR-006: Titles, Not AI Text

A row shows the title of a resource, never a description, so the lists carry no AI-written text; an event whose dates, kind or place an AI model read says "Extracted by AI" next to its date and carries the machine-readable mark.

### BR-007: Freshness

The new resources list may be served from a shared cache for up to 2 minutes, and the upcoming events for up to 5 minutes.

### BR-008: Rate Limits

A visitor may ask for the new resources at most 60 times per minute by default; the upcoming events fall under the general limit of 120 requests per minute (UC-007 BR-010).
