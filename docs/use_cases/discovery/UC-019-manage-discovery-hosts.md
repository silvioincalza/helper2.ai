# Use Case: Manage Discovery Hosts

## Overview

**Use Case ID:** UC-019  
**Use Case Name:** Manage Discovery Hosts  
**Primary Actor:** Administrator  
**Goal:** Tell discovery by name which sites write about many products and which sites only list products, so that its web searches turn the right pages into candidates and skip the directories.  
**Trigger:** An administrator opens the discovery hosts of the back office to look at them, add a host, remove one or turn the reading of a directory host's lists on or off.  
**Status:** Reviewed  

**Requirements:** [FR-026](../../requirements.md)  
**API:** [listDiscoveryHosts](../../../api/admin.yaml#listDiscoveryHosts), [addDiscoveryHost](../../../api/admin.yaml#addDiscoveryHost), [removeDiscoveryHost](../../../api/admin.yaml#removeDiscoveryHost), [readDirectoryLists](../../../api/admin.yaml#readDirectoryLists)

## Preconditions

- Administrator is signed in to the back office with the ADMIN role.

## Main Success Scenario

1. Administrator opens the discovery hosts.
2. System shows every host by kind and name, with who added it, when, and why, and for a directory host whether its lists are read.
3. Administrator enters a host name, chooses whether it is a writing host or a directory host, and may give a reason.
4. System checks the reason and the host name and brings the host to its plain form.
5. System checks that the host is in no list yet and that the lists have room.
6. System adds the host to the chosen list, records the change in the audit trail, and shows the new entry.
7. Administrator chooses a host to remove and may give a reason.
8. System removes the host from its list and records the change in the audit trail with the list the host was in.
9. Administrator chooses a directory host, turns the reading of its lists on or off, and may give a reason.
10. System sets the switch of the host and records the change in the audit trail with the state before and after.

## Alternative Flows

### A1: Reason Not One Plain Line

**Trigger:** The reason holds a control or hidden character or is longer than 500 characters (step 4, step 8 or step 10)  
**Flow:**

1. System tells the administrator that the reason must be one plain line of at most 500 characters.
2. Nothing changes.
3. Use case continues at step 3 for an addition, at step 7 for a removal, or at step 9 for the switch.

### A2: Not a Host Name

**Trigger:** The entry is empty or is not a host name, for example it holds a scheme, a path or a port (step 4)  
**Flow:**

1. System tells the administrator to enter only a host name.
2. Use case continues at step 3.

### A3: Host Too Broad

**Trigger:** The host is a top-level domain, a registry suffix such as "co.uk", or an IP address (step 4)  
**Flow:**

1. System tells the administrator that a domain alone matches too many sites.
2. Use case continues at step 3.

### A4: GitHub Host

**Trigger:** The host is github.com or one of its subdomains (step 4)  
**Flow:**

1. System tells the administrator that discovery reads GitHub by its own rule.
2. Use case continues at step 3.

### A5: Host Already Listed

**Trigger:** The host is already in the writing or the directory list (step 5)  
**Flow:**

1. System tells the administrator which list holds the host and that it must be removed there first to move it.
2. System records the refused attempt in the audit trail.
3. Use case continues at step 3.

### A6: Lists Full

**Trigger:** The lists already hold the maximum number of hosts (step 5)  
**Flow:**

1. System tells the administrator to remove a host first.
2. System records the refused attempt in the audit trail.
3. Use case continues at step 3.

### A7: Host Not Listed

**Trigger:** The host to remove or to switch is in no list (step 8 or step 10)  
**Flow:**

1. System tells the administrator that the host is in no list.
2. System records the attempt in the audit trail.
3. Use case ends.

### A8: Not a Directory Host

**Trigger:** The host to switch is a writing host (step 10)  
**Flow:**

1. System tells the administrator that only a directory host has lists to read.
2. System records the refused attempt in the audit trail.
3. Use case ends.

## Postconditions

### Success Postconditions

- The added host is in exactly one list, with the administrator, the time and the reason; the removed host is in no list.
- A switched directory host has its new state; a host that is added has the switch off.
- The next discovery run reads the changed lists and switches.
- The audit trail holds the change.

### Failure Postconditions

- The lists are unchanged when the reason, the host name or the room in the lists is refused.

## Business Rules

### BR-001: Kinds of Hosts

A writing host publishes articles about many products: a post on it becomes an article candidate. A directory host lists products: no page on it becomes a product candidate or an article candidate, and none is indexed without review. A page on it whose title reads as a list is proposed as an article candidate in the review list, where an administrator decides, unless the lists of the host are read (BR-007, UC-030 BR-003); on any other host such a page is indexed as a list. This holds for the subdomains of a directory host too. On both kinds of host, no page is taken as a product's own site. Discovery and the awesome-list collector read both lists at the start of each pass.

### BR-002: Plain Host Form

A host is kept in lower case, without a leading "www." and without a trailing dot. It has at most 253 characters and at least a name and a domain; each label has at most 63 characters: letters, digits and inner hyphens only. An entry also covers every subdomain of the host.

### BR-003: One List per Host

A host is in at most one list. To move it to the other kind, the administrator removes it first.

### BR-004: List Size

The two lists together hold at most 500 hosts.

### BR-005: Reason

The reason is optional. When given, it is one line of at most 500 characters without control or hidden characters.

### BR-006: Audit

Every attempt to add a host whose reason and name pass the checks, and every attempt to remove a host or to switch the reading of its lists with a valid reason, is recorded in the audit trail with the administrator, the host, the result and the reason.

### BR-007: Switch of a Directory Host

A directory host has a switch "read its lists". It is off when the host is added. Only an administrator turns it on or off, host by host, after checking the host. A writing host has no switch. What the Scout reads while the switch is on, and what stays when it is turned off, is in UC-030 (BR-003, BR-005). The switch covers the subdomains of the host too.
