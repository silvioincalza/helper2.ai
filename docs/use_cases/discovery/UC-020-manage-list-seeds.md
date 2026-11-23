# Use Case: Manage List Seeds

## Overview

**Use Case ID:** UC-020  
**Use Case Name:** Manage List Seeds  
**Primary Actor:** Administrator  
**Goal:** Choose curated lists of AI resources, on GitHub or on the web, that the Scout reads on every pass whatever its own search finds, so that good lists the search misses still feed the catalog.  
**Trigger:** An administrator opens the added lists of the back office to look at them, add a list or stop reading one.  
**Status:** Approved  

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

- The added list is stored with the administrator, the time and the reason, and the next pass of the awesome-list collector reads it (UC-030).
- A removed list is no longer read; the resources found in it stay in the catalog.
- The audit trail holds the change.

### Failure Postconditions

- The added lists are unchanged when the reason, the address, the host or the room for another list is refused.

## Business Rules

### BR-001: Accepted Addresses

A GitHub repository is entered as "owner/name" or as its https address on github.com and is kept in lower case as "owner/name". A web page is an https address with a public host name (no IP address, private name or punycode) and no user info, port or fragment, of at most 300 characters. Anything else is refused.

### BR-002: Hosts Never Read

A web page on a host the configuration names as never read is refused, with the reason the configuration gives. By default, coursera.org is never read because its terms forbid automated reading without written consent. A host covers its subdomains. The rule is applied again at every read (UC-030 BR-002), so a page added before its host was named is no longer read.

### BR-003: Number of Lists

An administrator can add at most 50 lists. The lists discovery adds have their own number (UC-030 BR-003) and do not count.

### BR-004: Reason

The reason of a list follows the same rule as the reason of a discovery host (UC-019 BR-005).

### BR-005: Audit

Every attempt to add a list whose reason, address and host pass the checks, and every attempt to remove a list with a valid reason, is recorded in the audit trail with the administrator, the address, the result and the reason.
