# Use Case: Manage List Seeds

## Overview

**Use Case ID:** UC-020  
**Use Case Name:** Manage List Seeds  
**Primary Actor:** Administrator  
**Goal:** Choose curated lists of AI resources, on GitHub or on the web, that the Scout reads on every pass whatever its own search finds, so that good lists the search misses still feed the catalog.  
**Trigger:** An administrator opens the added lists of the back office to look at them, add a list or stop reading one.  
**Status:** Reviewed  

**Requirements:** [FR-027](../../requirements.md)  
**API:** [listListSeeds](../../../api/admin.yaml#listListSeeds), [addListSeed](../../../api/admin.yaml#addListSeed), [removeListSeed](../../../api/admin.yaml#removeListSeed)

## Preconditions

- Administrator is signed in to the back office with the ADMIN role.

## Main Success Scenario

1. Administrator opens the added lists.
2. System shows every added list, oldest first, with its kind, who added it (an administrator, or discovery on a directory host), when, and why.
3. Administrator enters the address of a GitHub repository or of a web page, and may give a reason.
4. System checks the reason and the address and brings the address to its plain form.
5. System checks that a web page is not on a host that is never read.
6. System checks that the list is not added yet and that there is room for another list.
7. System adds the list, records the change in the audit trail, and shows the new entry.
8. Administrator chooses an added list to stop reading and may give a reason.
9. System removes the list and records the change in the audit trail; the list and the resources found in it stay in the catalog.

## Alternative Flows

### A1: Reason Not One Plain Line

**Trigger:** The reason holds a control or hidden character or is longer than 500 characters (step 4 or step 9)  
**Flow:**

1. System tells the administrator that the reason must be one plain line of at most 500 characters.
2. Nothing changes.
3. Use case continues at step 3 for an addition, or at step 8 for a removal.

### A2: Address Not Accepted

**Trigger:** The address is empty, is a file path, a branch or a query on GitHub, does not use https, has user info, a port, a fragment, a space or a hidden character, is longer than 300 characters, or is on an IP address, a private host name or a host name written in punycode (step 4)  
**Flow:**

1. System tells the administrator what is wrong with the address.
2. Use case continues at step 3.

### A3: Host Never Read

**Trigger:** The web page is on a host whose pages the Scout never reads (step 5)  
**Flow:**

1. System tells the administrator the host and the reason it is never read.
2. Use case continues at step 3.

### A4: List Already Added

**Trigger:** The same repository or the same page is added already (step 6)  
**Flow:**

1. System tells the administrator that the list is added already.
2. System records the refused attempt in the audit trail.
3. Use case continues at step 3.

### A5: Too Many Lists

**Trigger:** The maximum number of lists is added already (step 6)  
**Flow:**

1. System tells the administrator to remove a list first.
2. System records the refused attempt in the audit trail.
3. Use case continues at step 3.

### A6: List Not Found

**Trigger:** No added list has the chosen identity (step 9)  
**Flow:**

1. System tells the administrator that the list is not found.
2. System records the attempt in the audit trail.
3. Use case ends.

## Postconditions

### Success Postconditions

- The added list is stored with the administrator, the time and the reason, and the next pass of the awesome-list collector reads it.
- A removed list is no longer read; the resources found in it stay in the catalog.
- The audit trail holds the change.

### Failure Postconditions

- The added lists are unchanged when the reason, the address, the host or the room for another list is refused.

## Business Rules

### BR-001: Accepted Addresses

A GitHub repository is entered as "owner/name" or as its https address on github.com and is kept in lower case as "owner/name". A web page is an https address with a public host name (no IP address, private name or punycode) and no user info, port or fragment, of at most 300 characters. Anything else is refused.

### BR-002: Hosts Never Read

A web page on a host the configuration names as never read is refused, with the reason the configuration gives. By default, coursera.org is never read because its terms forbid automated reading without written consent. A host covers its subdomains. The rule is applied again at every read, so a page added before its host was named is no longer read.

### BR-003: Number of Lists

An administrator can add at most 50 lists. The lists discovery adds have their own number (BR-008) and do not count.

### BR-004: Reason

The reason of a list follows the same rule as the reason of a discovery host (UC-019 BR-005).

### BR-005: Reading an Added Repository

The awesome-list collector reads every added GitHub repository at the start of each of its passes, before the lists its own search finds, whatever the repository's stars and name.

### BR-006: Reading an Added Web Page

An added web page is read through the live web page reader at most once every 7 days by default. After a failed read, the next try comes after 1 hour by default, for up to 3 failed tries; then the page waits the full 7 days again. The text of the last successful read is kept and used while the page is not due, waits or cannot be read. A page waits when the page reader is off, its daily credits are spent, or the time or size budget of the collector's pass is used up.

### BR-007: Audit

Every attempt to add a list whose reason, address and host pass the checks, and every attempt to remove a list with a valid reason, is recorded in the audit trail with the administrator, the address, the result and the reason.

### BR-008: Lists Discovery Adds

Discovery adds a list page it finds on a directory host whose lists are read (UC-019 BR-007) as a web page to read. The page follows the rules of a web page an administrator adds: an accepted address (BR-001) and a host that is not one never read (BR-002). The list is stored with the discovery collector that found it and no reason. At most 100 such lists are added by default. A page these rules refuse, and a page found when the 100 are added, waits in the review list as an article candidate. A page that is a list to read already is not added again. An administrator removes such a list like any other.

### BR-009: Reading the Lists Discovery Added

A list discovery added is read like an added web page (BR-006), and only while the switch of its host is on (UC-019 BR-008). In each pass the web pages an administrator added are read first. At most 10 lists discovery added are read per day by default; the others wait for the next day and use the text of their last read meanwhile.

### BR-010: Links to Articles

A link of a list that reads as an article by the rule of discovery (a post on a writing host or below a writing place of a site) becomes an article candidate in the review list, within the limits of the collector for new candidates. A blog's index, an author's page, a page on a directory host and a page on an excluded domain are skipped.
