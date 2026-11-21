# Use Case: Collect New Resources

## Overview

**Use Case ID:** UC-018  
**Use Case Name:** Collect New Resources  
**Primary Actor:** Scheduler, Administrator  
**Secondary Actors:** Source Provider  
**Goal:** Bring the new and changed AI resources of every source into the catalog, or into the review queue when a person must decide, so that search finds them without anyone adding them by hand.  
**Trigger:** The daily run time of the Scout has passed, or an administrator asks to start a run for all sources or for one source.  
**Status:** Reviewed  

**Requirements:** [FR-025, C-008](../../requirements.md)  
**Mockup:** [Screens and states](../../mockups/back-office.md)  
**API:** [listScoutRuns](../../../api/admin.yaml#listScoutRuns), [startScoutRun](../../../api/admin.yaml#startScoutRun)

## Preconditions

- At least one collector is configured for the sources the Scout reads.
- For a scheduled run, the daily run of the Scout is switched on.
- For a run an administrator starts, the administrator is signed in to the back office with the ADMIN role.
- For a repository sweep, the sweep is switched on and GitHub can be read.

## Main Success Scenario

1. Scheduler checks whether the daily run is due, or Administrator asks to start a run for all sources or for one source.
2. System makes sure that no other run is in progress on any instance and closes the run records that a stopped instance left running.
3. System records one run per selected collector, running when the collector is switched on and skipped when it is switched off, and shows these records to the administrator at once.
4. For each running collector, System resumes from the position the collector saved last time and reads new and changed items from the Source Provider, up to the item budget of a run.
5. System decides for each item whether it is indexed at once, left to a person, or refused, by the admission rules.
6. System adds the indexed items to the catalog, keeps the text of the curated lists the collector read, and proposes the items left to a person to the review queue within its daily cap.
7. System removes from the catalog the records that the source has withdrawn.
8. System saves the collector's new position for the next run.
9. System enriches the resources that changed in this collector's pass, within the enrichment budget of the run (UC-021).
10. System records the run of the collector as succeeded, with the number of items seen, items changed and enrichments kept, and a note on anything left for later.
11. After the last collector, System spends the rest of the enrichment budget on the enrichment backlog, links relationship candidates to targets that are now in the catalog, and proposes known targets for review.
12. For a scheduled run, System records the time the run ended.
13. Administrator follows the progress on the list of the latest run of each source.

## Alternative Flows

### A1: Run Not Due

**Trigger:** The daily run time has not passed since the last scheduled run ended (step 1)  
**Flow:**

1. System does nothing until the next check.
2. Use case ends.

### A2: Unknown Source

**Trigger:** The administrator names a source that no collector has (step 1)  
**Flow:**

1. System tells the administrator that no collector has this source.
2. Nothing starts, no budget is spent and nothing is recorded in the audit trail.
3. Use case ends.

### A3: Run Already in Progress

**Trigger:** Another run holds the Scout on some instance (step 2)  
**Flow:**

1. For a scheduled check, System defers the run; the next check tries again.
2. For a run an administrator starts, System tells the administrator that a run is already in progress and to try again when it ends.
3. Use case ends.

### A4: Collector Not Due

**Trigger:** The collector's own interval has not passed since its last pass, or the collector reports a reason to wait (step 4)  
**Flow:**

1. System records the collector's run as skipped, with the reason or "not due yet".
2. When the collector waits for a daily limit of its source, System keeps the pass open, so that the next run goes on with it whatever the interval says.
3. Use case continues at step 4 with the next collector.

### A5: Collection Fails

**Trigger:** The Source Provider does not answer or answers with an error, or the items read cannot be stored or proposed (step 4)  
**Flow:**

1. System keeps the collector's pass open, so that the next run reads the same items again.
2. System records the collector's run as failed, with the kind of error.
3. Use case continues at step 4 with the next collector.

### A6: Item Budget Reached

**Trigger:** The collector stops at the item budget before the end of its pass (step 8)  
**Flow:**

1. System saves the position where the collector stopped.
2. System notes on the run that the next run resumes from the saved position.
3. Use case continues at step 9.

### A7: Review Cap Holds Candidates Back

**Trigger:** The daily cap of the review queue holds some proposed items back at the end of a pass (step 8)  
**Flow:**

1. System marks the pass as held back, so that the next run reads the same window again and proposes the next items.
2. Use case continues at step 9.

### A8: Withdrawn Records Cannot Be Removed

**Trigger:** Removing the records the source withdrew fails (step 7)  
**Flow:**

1. System does not move the collector's position forward, so that the next run reads the withdrawn records again.
2. Use case continues at step 9.

### A9: Enrichment Budget Spent

**Trigger:** The enrichment budget of the run is used up while changed resources remain (step 9)  
**Flow:**

1. System puts each changed resource that still needs a judgement back in the enrichment backlog.
2. System notes on the run how many changed resources wait for the backlog.
3. Use case continues at step 10.

### A10: Repository Sweep

**Trigger:** The repository sweep is due instead of the daily run (step 1)  
**Flow:**

1. System takes the source records whose GitHub repository was visited the longest time ago, up to the sweep batch.
2. System asks the Source Provider for the current state of each repository.
3. System records for each record whether its repository was found, is archived, or was not found, and when it was visited; a record whose repository did not answer keeps its state and gets only the time of the visit.
4. Use case ends.

## Postconditions

### Success Postconditions

- Every selected collector has a run record that ends as succeeded, failed or skipped.
- The indexed items are in the catalog, the items left to a person are in the review queue, and the records the source withdrew are gone.
- Each collector that succeeded has a saved position, and the next run resumes from it.
- After a scheduled run, the end time of the run is stored, and the next scheduled run waits for the next daily run time.

### Failure Postconditions

- No run starts while another run is in progress, and no run starts for a source that no collector has.
- A collector whose pass failed keeps its previous position, so that no item of the pass is lost.

## Business Rules

### BR-001: Daily Schedule

The scheduled run is off unless the Scout is switched on (off by default, on in the deployed environments). When it is on, the Scout runs once a day at 03:07 UTC by default. Each instance checks whether the run is due 20 seconds after it starts and then every 10 minutes by default; a run missed while no instance was up starts at the next check. A run an administrator starts does not count as the scheduled run.

### BR-002: One Run at a Time

Only one run of the Scout, scheduled or started by an administrator, can be in progress across all instances. At startup, an instance closes as failed the run records that a stopped instance left running.

### BR-003: Item Budget per Run

A collector reads at most 1,000 items in one run by default (5,000 for the MCP Registry). A pass that is not finished resumes in the next run from the saved position.

### BR-004: Collector Interval

Each collector reads its source only when its own interval has passed since its last pass, for example every hour for the MCP Registry, once a day for Hugging Face trending and the awesome lists, every 5 days for the live web search, and every 7 days for the discovery categories, courses and the GitHub topic leaders by default. An interval of whole days counts calendar days in UTC. An interval shorter than a day takes effect only in the runs an administrator starts, because the scheduled run is daily. A collector that is switched off in the configuration is recorded as skipped.

### BR-005: Refused Items

An item is refused and never indexed or proposed when its source states that it is archived or deprecated, when it is a hosted app that is not running or has fewer than 3 likes, or when it is a fork with fewer than 10 stars that was not changed in the last 1,095 days.

### BR-006: Index Floor

An item that is not refused is indexed at once unless its collector sets a floor that it misses; then it is left to a person in the review queue. A GitHub topic leader is indexed at once only with at least 1,000 stars and a change in the last 90 days, a Hugging Face trending model only with at least 20 likes, a model linked from an awesome list only with at least 10 likes, and a PyPI package only with both a GitHub repository and a description, by default.

### BR-007: Enrichment Budget per Run

A run enriches at most 20 resources by default (200 in the deployed environments); an administrator can change this number at runtime. The budget counts resources, not model calls (UC-021 BR-014). A resource whose text and model have not changed since its last judged enrichment gives its place back to the budget. When the language model is unavailable, the run stops enriching for the rest of the run.

### BR-008: Run History

The run records of the Scout are kept for 90 days by default; older records that are not running are deleted.

### BR-009: Repository Sweep

The repository sweep is on by default and visits 50 source records every 10 minutes, starting 2 minutes after startup. A record whose repository is not found is marked gone only when the repository was also missing at a visit at least 24 hours earlier and the record is the repository itself, not a record that only links to it.

### BR-010: Audit of Started Runs

Every run an administrator asks for is recorded in the audit trail with the administrator, the source asked for, and whether the run started or was refused because another run was in progress. A request for a source that no collector has is refused before anything starts and is not recorded.

### BR-011: Daily Cap of Proposed Candidates

A collector may cap the new candidates it proposes to the review queue per UTC day: 25 for the GitHub topic leaders, 50 for the links of the awesome lists, 20 for PyPI, and 500 for the libraries and SDKs, by default. The candidates above the cap are held back for the next run (A7).
