# Use Case: Purge Expired Data

## Overview

**Use Case ID:** UC-029  
**Use Case Name:** Purge Expired Data  
**Primary Actor:** Scheduler  
**Goal:** Remove every record that is past its retention period, so that helper2.ai keeps data no longer than its privacy notice states  
**Trigger:** The daily retention time is reached (03:17 UTC by default)  
**Status:** Approved  

**Requirements:** [FR-040, C-006](../../requirements.md)  
**Layers:** Service

## Preconditions

- Both applications are running and can reach the database.
- The retention periods are configured, or their defaults apply.

## Main Success Scenario

1. Scheduler starts every daily purge job of the public application and of the back office at the retention time.
2. System runs each job on one instance of the cluster only.
3. System deletes the accounts that have not signed in for longer than their retention period, together with their saved resources.
4. System deletes the administrator audit entries and the runtime setting changes that are older than their retention periods.
5. System deletes the catalog records past their retention periods: resolved review items, handled issue reports, superseded enrichment runs, web news records, past events, observed resources no source has seen again, and the awesome list index.
6. System deletes the Scout run records and the review research runs that are older than their retention periods.
7. System deletes the stored live web results, the guide's remembered similar questions and the shared AI cache entries that are past their retention period or expiry time.
8. System logs how many records each job deleted.

## Alternative Flows

### A1: A Purge Job Fails

**Trigger:** A job cannot complete its deletion (step 2)  
**Flow:**

1. System logs that the job failed and does not stop the other jobs.
2. The failed job runs again at the next retention time.
3. Use case continues at step 8.

### A2: The Job Already Runs on Another Instance

**Trigger:** Another instance of the same application holds the job (step 2)  
**Flow:**

1. System skips the job on this instance.
2. Use case continues at step 8.

## Postconditions

### Success Postconditions

- No record named in the business rules remains after its retention period has passed.
- The log states how many records each job deleted.

### Failure Postconditions

- No record that is still inside its retention period is deleted.
- A failed job leaves the data of the other jobs unaffected.

## Business Rules

### BR-001: Daily Retention Time

The purge jobs run once a day at 03:17 UTC by default (`H2_RETENTION_CRON`). Each job runs on one instance only, and a failing job never stops another one. The public application runs the jobs for accounts, live web results, remembered guide questions and the shared AI cache; the back office runs the jobs for the administrator audit entries, the runtime setting changes, the catalog records, the Scout runs and the review research.

### BR-002: Inactive Accounts

An account (APP_USER) whose last sign-in is more than 730 days ago by default (`H2_RETENTION_INACTIVE_ACCOUNTS`) is deleted, and its saved resources (BOOKMARK) are deleted with it.

### BR-003: Administrator Audit Entries

An administrator audit entry (ADMIN_AUDIT) older than 730 days by default (`H2_RETENTION_ADMIN_AUDIT`) is deleted. The period may never be shorter than 730 days: the application refuses to start with a shorter one, and the database refuses to delete an entry younger than 729 days.

### BR-004: Runtime Setting Changes

A runtime setting change (RUNTIME_SETTING) older than 730 days by default (`H2_RETENTION_RUNTIME_SETTINGS`) is deleted, except the newest change of each setting, which is always kept.

### BR-005: Resolved Review Items

A review item (REVIEW_ITEM) resolved more than 180 days ago by default (`H2_RETENTION_RESOLVED_REVIEW_ITEMS`) is deleted. Open review items and review items that belong to an issue report are kept.

### BR-006: Handled Issue Reports

An issue report (ISSUE_REPORT) is deleted when all its review items were resolved more than 365 days ago by default (`H2_RETENTION_RESOLVED_ISSUE_REPORTS`). A report with an open review item is kept, and so is a report that has no review item.

### BR-007: Enrichment Runs

An enrichment run (ENRICHMENT_RUN) started more than 90 days ago by default (`H2_RETENTION_ENRICHMENT_RUNS`) is deleted when a newer run exists for the same resource and no field assertion, relationship, summary claim or review item refers to it.

### BR-008: Web News

A news or article record (SOURCE_RECORD) found by the web search, a discovery host or a news feed and retrieved more than 180 days ago by default (`H2_RETENTION_WEB_NEWS`) is deleted, and its resource is updated to forget it.

### BR-009: Past Events

An event (RESOURCE of type event) that ended, or started when no end is stated, more than 3650 days ago by default (`H2_RETENTION_PAST_EVENTS`) is deleted together with the quotes that supported its fields. An event that an issue report or a review item refers to is kept.

### BR-010: Observed Resources No Source Sees Again

A resource of observed origin (RESOURCE) that no source has seen for more than 180 days by default (`H2_RETENTION_OBSERVED_UNSEEN`) is deleted. It is kept when it has a curated field, when a resource of another origin links to it, or when an issue report or a review item refers to it.

### BR-011: Awesome List Index

An awesome list (AWESOME_LIST) and an awesome list entry (AWESOME_LIST_ENTRY) not updated for more than 30 days by default (`H2_RETENTION_LIST_INDEX`) are deleted.

### BR-012: Scout Runs

A Scout run record (SCOUT_RUN) started more than 90 days ago by default (`H2_RETENTION_SCOUT_RUNS`) is deleted, never while the run is still running.

### BR-013: Review Research

A review research run (REVIEW_RESEARCH) that finished more than 90 days ago, or that has waited in the queue for more than 90 days, is deleted. The 90 days are fixed and cannot be configured.

### BR-014: Live Web Results

A stored live web result (LIVE_WEB_QUERY_RESULT) fetched more than 7 days ago by default (`H2_RETENTION_LIVE_WEB_RESULTS`) is deleted.

### BR-015: Remembered Guide Questions

A question the guide remembers to find similar questions (GUIDE_SIMILAR_QUESTION) is deleted once its own expiry time has passed.

### BR-016: Shared AI Cache

An entry of the shared AI cache is deleted once its own expiry time has passed.
