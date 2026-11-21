# Use Case: Decide Relationship Targets by Name

## Overview

**Use Case ID:** UC-025  
**Use Case Name:** Decide Relationship Targets by Name  
**Primary Actor:** Administrator  
**Goal:** Answer once what a target name means (a resource of the catalog, a vendor, a service, a tool, or no real target) and so decide every open relationship candidate that names it.  
**Trigger:** The Administrator opens the review queue for relationship candidates.  
**Status:** Approved  

**Requirements:** [FR-032](../../requirements.md)  
**Mockup:** [Screens and states](../../mockups/back-office.md)  
**API:** [listRelationshipTargetGroups](../../../api/admin.yaml#listRelationshipTargetGroups), [decideRelationshipTargetGroup](../../../api/admin.yaml#decideRelationshipTargetGroup)

## Preconditions

- The Administrator is signed in to the back office with the ADMIN role, over the VPN.
- Open relationship candidates exist: relationships whose quotes passed the check but whose target is not a known resource.

## Main Success Scenario

1. The Administrator chooses the kind "Relationship candidate" in the review queue.
2. The system shows the open relationship candidates grouped by target name, the largest group first, each with the name most of them write, the number of open items, the kinds of relationship and up to three resources that name it.
3. The Administrator chooses a group and answers what the name is: a resource of the catalog, a vendor, a service, a tool, or not a real target, with an optional note.
4. The system takes the open items of the group, the oldest first.
5. The system decides each item on its own: for a catalog resource it points the relationship at that resource and accepts the item; for a vendor, a service or a tool it adds the name as a known target of that kind and closes the item as superseded with the relationship verified; for not a real target it rejects the item and its relationship.
6. The system records the decision of each item in the audit trail, with the identifier of the batch; a link also records the change of the target, and a new known target its addition.
7. The system says how many items were decided, how many were skipped and how many of the name are still open, and shows the next group.

## Alternative Flows

### A1: Decide each item on its own

**Trigger:** The Administrator wants to look at the items one by one (step 3)  
**Flow:**

1. The Administrator switches the queue to each item.
2. The system shows the relationship candidates one by one, as UC-024 decides them.
3. Use case ends.

### A2: No open item has this name

**Trigger:** Every item of the group was decided in the meantime (step 4)  
**Flow:**

1. The system decides nothing and says that no open relationship candidate has this target.
2. Use case ends.

### A3: Chosen resource does not exist

**Trigger:** The Administrator links the group to a resource the catalog does not hold (step 4)  
**Flow:**

1. The system decides nothing and says that no resource has this identifier.
2. Use case ends.

### A4: Name cannot become a known target

**Trigger:** The name is not valid for a known target, or the list of known targets is full (step 5)  
**Flow:**

1. The system decides nothing and says why.
2. Use case ends.

### A5: Item is skipped

**Trigger:** An item was decided in the meantime, its resource already has a relationship to the chosen target, or its resource is the chosen target itself (step 5)  
**Flow:**

1. The system leaves the item as it is and counts it as skipped.
2. Use case continues at step 5.

### A6: Link is undone

**Trigger:** An item was decided by someone else between the change of its target and its acceptance (step 5)  
**Flow:**

1. The system rolls back the target change of that item and counts it as skipped.
2. Use case continues at step 5.

## Postconditions

### Success Postconditions

- Each decided item of the group is accepted, rejected or superseded, with the Administrator and the time.
- For a catalog resource, each decided relationship points at that resource, chosen by a person, and is verified.
- For a vendor, a service or a tool, the name is a known target of that kind and each decided relationship is verified with that kind.
- For not a real target, each decided relationship is rejected.
- The audit trail holds the decision of each decided item, with the batch.

### Failure Postconditions

- A skipped item stays exactly as it was, its target included.
- When nothing is decided, no item, relationship or known target changes.

## Business Rules

### BR-001: Grouping key

Items are grouped by the key of their target name: lower case, spaces folded, a trailing "'s" dropped, the same key known targets use, so "PyTorch" and "pytorch" are one group.

### BR-002: One answer for the group

The answer is one of: link to a catalog resource, vendor, service, tool, or reject. A catalog resource is required for a link and not allowed for the other answers.

### BR-003: Each item on its own

Each item is decided in its own unit of work and holds its lock from the target change to the decision, so a link changes the target and accepts the item together or not at all. The group has no transaction of its own.

### BR-004: At most 1,000 items per decision

One decision takes at most 1,000 open items of the name, the oldest first; the answer says how many are left.

### BR-005: A name already known

When the name is already a known target, the vendor, service or tool answer still closes the items of the group that it now matches.

### BR-006: Paging of the groups

A page holds 1 to 100 groups, 50 by default. A group spans every discovery category.

### BR-007: Notes

A decision note follows the note rule of single review decisions (UC-024 BR-016). It is kept for a link and a reject; a vendor, service or tool answer writes its own fixed note.
