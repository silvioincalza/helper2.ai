# Use Case: Read Lists

## Overview

**Use Case ID:** UC-030  
**Use Case Name:** Read Lists  
**Primary Actor:** Scout  
**Secondary Actors:** Source Provider  
**Goal:** Read the lists of AI resources, awesome lists on GitHub and list pages on the web, follow each link to the item's own source, and keep which list names which resource, so that what a list names reaches the catalog checked and a visitor sees how known a resource is.  
**Trigger:** A pass of the awesome-list collector starts in a run of the Scout (UC-018).  
**Status:** Approved  

**Requirements:** [FR-041, NFR-015, C-008](../../requirements.md)

## Preconditions

- The awesome-list collector is switched on and due (UC-018 BR-004).
- The discovery hosts can be read (UC-019).

## Main Success Scenario

1. Scout gathers the lists to read: the GitHub lists and web pages an administrator added (UC-020), the list pages discovery added on directory hosts, and the top lists its own search finds.
2. Scout reads each GitHub list from the Source Provider, and each web page that is due through the live web page reader.
3. System keeps the text of the last read of each web page.
4. Scout follows the lists a list links to, within the limits of a pass.
5. Scout takes every link of every list entry and sorts it by its kind.
6. Scout asks the Source Provider for each repository, model, paper and package by its id.
7. System indexes what the Source Provider confirms, by the admission rules (UC-018).
8. System proposes the other links (product sites, course pages, articles, and items under the index floor) as candidates in the review queue (UC-024), within the daily cap.
9. System adds each list as a resource of the catalog, except a list page of a directory host.
10. System records which list names which resource, with the headings above the entry.
11. System removes the records of a list that was not read for 30 days.

## Alternative Flows

### A1: Web Page Not Due or Waiting

**Trigger:** A web page was read less than 7 days ago, the reads of the day or the credits are spent, or the page reader is off (step 2)  
**Flow:**

1. Scout does not read the page.
2. System uses the text of its last read; a page never read gives nothing.
3. Use case continues at step 4.

### A2: Web Page Cannot Be Read

**Trigger:** The page reader fails, answers with no text or answers from another host (step 2)  
**Flow:**

1. System counts the failed read and keeps the text of the last successful read.
2. Scout tries the page again by the rule of failed reads (BR-002).
3. Use case continues at step 4.

### A3: Host Never Read

**Trigger:** A web page is on a host the configuration names as never read (step 2)  
**Flow:**

1. Scout does not read the page and does not use its last read.
2. Use case continues at step 2.

### A4: Reading of the Host Turned Off

**Trigger:** A list page discovery added is on a directory host whose switch is off, or on a host that is no longer a directory host (step 2)  
**Flow:**

1. Scout does not read the page.
2. System uses the text of its last read, so what the list named stays.
3. Use case continues at step 4.

### A5: Source Does Not Confirm

**Trigger:** The Source Provider does not know the item, or the item fails the checks of its source (step 6)  
**Flow:**

1. System adds nothing for this link.
2. Use case continues at step 5.

### A6: Budget of the Pass Spent

**Trigger:** The time or size budget of the pass is used up, or a source limits its calls (step 2, step 4 or step 6)  
**Flow:**

1. Scout stops reading; the lists and links left wait for the next pass.
2. Use case continues at step 7 with what was read.

## Postconditions

### Success Postconditions

- Every list that was read is a resource of the catalog, except a list page of a directory host.
- Every repository, model, paper and package a list names and its source confirms is in the catalog; every other kept link waits in the review queue.
- The catalog holds which list names which resource.
- The text of the last read of each web page is kept for the passes until its next read.

### Failure Postconditions

- A list that could not be read keeps what its last read gave.
- No item enters the catalog from a link alone.

## Business Rules

### BR-001: Reading an Added Repository

The awesome-list collector reads every GitHub repository an administrator added (UC-020) at the start of each of its passes, before the lists its own search finds, whatever the repository's stars and name.

### BR-002: Reading an Added Web Page

An added web page is read through the live web page reader at most once every 7 days by default. After a failed read, the next try comes after 1 hour by default, for up to 3 failed tries; then the page waits the full 7 days again. The text of the last successful read is kept and used while the page is not due, waits or cannot be read. A page waits when the page reader is off, its daily credits are spent, or the time or size budget of the collector's pass is used up. A page on a host that is never read (UC-020 BR-002) is not read and its last read is not used.

### BR-003: A List Page Discovery Finds

The searches of discovery do not exclude a directory host whose switch is on (UC-019 BR-007), so that its list pages can be found. When discovery finds a page whose title reads as a list on such a host, it adds the page as a web page to read, stored with the discovery collector that found it and no reason. The page follows the rules of a web page an administrator adds: an accepted address (UC-020 BR-001) and a host that is not one never read (UC-020 BR-002). At most 100 such lists are added by default; they do not count against the lists an administrator adds (UC-020 BR-003). A page these rules refuse, and a page found when the 100 are added, waits in the review queue as an article candidate, as with the switch off (UC-019 BR-001). A page that is a list to read already is not added again.

### BR-004: Reading the Lists Discovery Added

A list discovery added is read like an added web page (BR-002). In each pass the web pages an administrator added are read first. At most 10 lists discovery added are read per day by default; the others wait for the next day and use the text of their last read meanwhile.

### BR-005: Reading Turned Off

A list discovery added is read only while the switch of its host is on. When the switch is turned off, or the host is removed from the directory hosts, the list is no longer read. What its last read gave stays: the list still names its resources, and it stays a list of a directory host (BR-009). Turning the switch on again, or adding the host again with the switch on, resumes the reading.

### BR-006: Each Link Follows the Rule of Its Kind

A link of a list never enters the catalog by itself. A GitHub repository, a Hugging Face model, an arXiv paper and an npm package are asked of their own source by id, and indexed when the source confirms them and the admission rules allow (UC-018 BR-005, BR-006). A course page on a known learning platform and a product's own site become candidates in the review queue. A link that redirects, a link to a directory host and a link to an excluded domain are skipped. No page of a directory host other than an added list page is read. New candidates stay within the daily cap of the collector (UC-018 BR-011).

### BR-007: Links to Articles

A link of a list that reads as an article by the rule of discovery (a post on a writing host or below a writing place of a site) becomes an article candidate in the review queue. A blog's index, an author's page, a page on a directory host and a page on an excluded domain are skipped.

### BR-008: Which List Names Which Resource

For each list that was read, the catalog keeps the resources it names that are in the catalog, with the headings above each entry. The record of a list is replaced at each pass and removed 30 days after the last pass that read it. A curated list (an awesome list on GitHub, or a web page that is not on a directory host) is itself a resource; the public page of a resource names the curated lists that name it (UC-007 BR-008).

### BR-009: A List Page of a Directory Host Is Never a Resource

A list page of a directory host is never a resource of the catalog, whoever added it, and a list discovery added stays one after its host is removed from the directory hosts: it has no page, it is in no search result and it is not counted among the resources. The catalog keeps only its address, its title and the resources it names. The public page of a resource gives the number of such lists that name it (UC-007 BR-008); the back office names them (UC-024 BR-019).
