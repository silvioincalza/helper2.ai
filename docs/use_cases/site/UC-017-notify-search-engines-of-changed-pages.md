# Use Case: Notify Search Engines of Changed Pages

## Overview

**Use Case ID:** UC-017  
**Use Case Name:** Notify Search Engines of Changed Pages  
**Primary Actor:** Scheduler  
**Secondary Actors:** Search Engine  
**Goal:** Tell search engines once a day which resource pages changed, so that they index new and changed pages soon  
**Trigger:** The daily notification time is reached (05:47 UTC by default)  
**Status:** Reviewed  

**Requirements:** [FR-024](../../requirements.md)  
**Layers:** Service

## Preconditions

- The site has a notification key and is configured to be indexed.

## Main Success Scenario

1. Scheduler starts the notification on one instance of the back office.
2. System lists the resource pages worth indexing that changed in the last 25 hours.
3. System sends their addresses, the site's key and where the key can be read to the shared notification service of the search engines.
4. Search Engine reads the key file on the site to confirm that the site sent the notice.
5. System counts the batch and the number of addresses sent.

## Alternative Flows

### A1: Notification Is Off

**Trigger:** The site has no notification key or is configured not to be indexed (step 1)  
**Flow:**

1. System sends nothing.
2. Use case ends.

### A2: No Page Changed

**Trigger:** No page worth indexing changed in the last 25 hours (step 2)  
**Flow:**

1. System sends nothing and counts a batch with nothing to send.
2. Use case ends.

### A3: The Notice Is Not Accepted

**Trigger:** The notification service refuses the batch or does not answer in time (step 3)  
**Flow:**

1. System counts a failed batch and logs it.
2. Use case ends.

### A4: The Key File Is Asked for by Another Name

**Trigger:** The requested key file does not have the name of the site's key (step 4)  
**Flow:**

1. System answers that the file does not exist.
2. Use case ends.

## Postconditions

### Success Postconditions

- The search engines have received the addresses of the changed pages.
- The counters show the batch and the number of addresses sent.

### Failure Postconditions

- No catalog data is changed.

## Business Rules

### BR-001: Schedule

The notification runs once a day at 05:47 UTC by default (`H2_INDEXNOW_CRON`), on one instance only.

### BR-002: Changed Pages

A page is sent when it is worth indexing (UC-014 BR-003) and changed in the last 25 hours. At most 10,000 pages, the newest change first, are read for one batch. A page is sent under its current address (UC-014 BR-008).

### BR-003: Key

The key has 8 to 128 letters, digits or hyphens (`H2_INDEXNOW_KEY`). The site serves it as a text file named after the key, and only while the site is to be indexed.

### BR-004: On Only for an Indexed Site

Notification is on only when a key is set and the site is configured to be indexed (UC-014 BR-006).

### BR-005: Time Limit

The notification service has 20 seconds to accept a batch.
