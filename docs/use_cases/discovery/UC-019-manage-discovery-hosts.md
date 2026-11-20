# Use Case: Manage Discovery Hosts

## Overview

**Use Case ID:** UC-019  
**Use Case Name:** Manage Discovery Hosts  
**Primary Actor:** Administrator  
**Goal:** Tell discovery by name which sites write about many products and which sites only list products, so that its web searches turn the right pages into candidates and skip the directories.  
**Trigger:** An administrator opens the discovery hosts of the back office to look at them, add a host or remove one.  
**Status:** Draft  

**Requirements:** [FR-026](../../requirements.md)  
**API:** [listDiscoveryHosts](../../../api/admin.yaml#listDiscoveryHosts), [addDiscoveryHost](../../../api/admin.yaml#addDiscoveryHost), [removeDiscoveryHost](../../../api/admin.yaml#removeDiscoveryHost)

## Preconditions

- Administrator is signed in to the back office with the ADMIN role.

## Main Success Scenario

1. Administrator opens the discovery hosts.
2. System shows every host by kind and name, with who added it, when, and why.
3. Administrator enters a host name, chooses whether it is a writing host or a directory host, and may give a reason.
4. System checks the reason and the host name and brings the host to its plain form.
5. System checks that the host is in no list yet and that the lists have room.
6. System adds the host to the chosen list, records the change in the audit trail, and shows the new entry.
7. Administrator chooses a host to remove and may give a reason.
8. System removes the host from its list and records the change in the audit trail with the list the host was in.

## Alternative Flows

### A1: Reason Not One Plain Line

**Trigger:** The reason holds a control or hidden character or is longer than 500 characters (step 4 or step 8)  
**Flow:**

1. System tells the administrator that the reason must be one plain line of at most 500 characters.
2. Nothing changes.
3. Use case continues at step 3.

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

**Trigger:** The host to remove is in no list (step 8)  
**Flow:**

1. System tells the administrator that the host is in no list.
2. System records the attempt in the audit trail.
3. Use case ends.

## Postconditions

### Success Postconditions

- The added host is in exactly one list, with the administrator, the time and the reason; the removed host is in no list.
- The next discovery run reads the changed lists.
- The audit trail holds the change.

### Failure Postconditions

- The lists are unchanged when the reason, the host name or the room in the lists is refused.

## Business Rules

### BR-001: Kinds of Hosts

A writing host publishes articles about many products: a post on it becomes an article candidate. A directory host lists products: a page on it is never kept. Discovery reads both lists at the start of each run.

### BR-002: Plain Host Form

A host is kept in lower case, without a leading "www." and without a trailing dot. It has at most 253 characters and at least a name and a domain; each label has letters, digits and inner hyphens only.

### BR-003: One List per Host

A host is in at most one list. To move it to the other kind, the administrator removes it first.

### BR-004: List Size

The two lists together hold at most 500 hosts.

### BR-005: Reason

The reason is optional. When given, it is one line of at most 500 characters without control or hidden characters.

### BR-006: Audit

Every attempt to add a host whose reason and name pass the checks, and every attempt to remove a host with a valid reason, is recorded in the audit trail with the administrator, the host, the result and the reason.
