# Use Case: Keep the Catalog About AI

## Overview

**Use Case ID:** UC-023  
**Use Case Name:** Keep the Catalog About AI  
**Primary Actor:** Administrator, Scheduler  
**Secondary Actors:** Source Provider  
**Goal:** Find and remove the resources that are not about AI, give the real awesome lists their type, and assign the categories of every resource again, so that the catalog holds only AI resources, typed and grouped by the current rules.  
**Trigger:** An administrator opens the AI check of the back office or starts the awesome-list check or the category backfill, or an instance of the back office starts.  
**Status:** Reviewed  

**Requirements:** [FR-030](../../requirements.md)  
**API:** [checkAiWords](../../../api/admin.yaml#checkAiWords), [removeResourceNotAboutAi](../../../api/admin.yaml#removeResourceNotAboutAi), [checkAwesomeLists](../../../api/admin.yaml#checkAwesomeLists), [backfillCategories](../../../api/admin.yaml#backfillCategories)

## Preconditions

- For the checks and the removal, the administrator is signed in to the back office with the ADMIN role.

## Main Success Scenario

1. Administrator opens the AI check.
2. System reads the next resources of the catalog, the most popular first, and checks each one's title, description and tags against the word rule for AI.
3. System lists the resources that fail the rule, marks those that wait in the enrichment queue, and says how many it checked.
4. Administrator reads the list, moves to the next page if needed, and chooses a resource that is not about AI.
5. Administrator gives the reason and asks to remove the resource.
6. System records the removal in the audit trail with the resource's address and the reason, records a rejected decision for the address, marks the resource as removed, and removes the resource with everything that belongs to it, all in one step.
7. Administrator starts the awesome-list check.
8. System reads, from the Source Provider, the read-me of each GitHub repository in the catalog whose name or topics mark it as a possible awesome list.
9. System gives the type "awesome list" to each repository whose read-me reads as a list, records each change in the audit trail, and shows how many repositories it checked, typed, found not to be a list, kept as curated, and could not read.
10. Administrator starts the category backfill.
11. System answers at once and assigns the categories of every resource again in the background, in batches, on one instance.

## Alternative Flows

### A1: Unreadable Page Position

**Trigger:** The page position sent with the request is not one this check gave (step 4)  
**Flow:**

1. System tells the administrator that the position is not from this check.
2. Use case continues at step 1.

### A2: Removal Without a Valid Reason

**Trigger:** The reason is empty or longer than 500 characters (step 5)  
**Flow:**

1. System tells the administrator that a reason of 1 to 500 characters is required.
2. Nothing is removed.
3. Use case continues at step 5.

### A3: Resource Not Found

**Trigger:** The resource to remove is no longer in the catalog (step 6)  
**Flow:**

1. System tells the administrator that the resource is not found.
2. Use case continues at step 4.

### A4: Type Curated by an Administrator

**Trigger:** An administrator curated the type of a possible awesome list (step 8)  
**Flow:**

1. System does not read the repository and keeps its type.
2. Use case continues at step 8 with the next repository.

### A5: Read-me Not Available

**Trigger:** The Source Provider does not give the repository's read-me (step 8)  
**Flow:**

1. System counts the repository as unavailable and changes nothing.
2. Use case continues at step 8 with the next repository.

### A6: Backfill Already Running

**Trigger:** Another instance is already running the category backfill (step 11)  
**Flow:**

1. System does not start a second backfill.
2. Use case ends.

### A7: Backfill at Startup

**Trigger:** An instance of the back office starts instead of an administrator action (step 1)  
**Flow:**

1. Scheduler asks for the category backfill when the backfill at startup is switched on.
2. System compares the category definitions and the schema with those of the last completed backfill.
3. When they changed, System assigns the categories of every resource again on one instance; otherwise it does nothing.
4. Use case ends.

## Postconditions

### Success Postconditions

- A removed resource and everything that belongs to it are gone, its address carries a rejected decision, and the audit trail holds the removal with its reason.
- Every repository the check read that passes the awesome-list rule has the curated type "awesome list".
- Every resource carries the categories of the current category definitions.

### Failure Postconditions

- The AI check itself never changes or deletes a resource.
- No resource is removed without a reason, and a removal either completes in full or changes nothing.

## Business Rules

### BR-001: Word Rule for AI

A resource is about AI when its title, description or tags pass the fixed word rule for AI that every collector also applies. The check reads indexed and observed resources by popularity, then by identity. It checks only the resources whose every source record comes from a general source (GitHub, npm, PyPI, Hacker News, the live web and confs.tech): a source that holds only AI resources says that a resource is about AI, whatever its words.

### BR-002: Check Page Size

The AI check reads 500 resources per page by default and at most 2,000.

### BR-003: Removal Reason

A removal needs a reason of 1 to 500 characters.

### BR-004: Removed Address Stays Out

The catalog drops every later result for a removed resource, whatever the source, and the review queue proposes no candidate for an address that carries a rejected decision, so a removed resource is not added again. An administrator who adds the address by hand brings the resource back. Saved items of users keep their own title and address.

### BR-005: What a Removal Takes With It

Removing a resource also removes its source records, labels, embedding, enrichment runs, review items, and the links from it; a link to it from another resource stays and loses its target.

### BR-006: Awesome-List Rule

A GitHub repository is an awesome list when its name starts with "awesome" or "best-of-" or it carries the topic "awesome-list", its read-me has at least 20 list items that carry a link, and these items are at least half of the read-me's text lines.

### BR-007: Awesome-List Check Scope

One check reads at most 2,000 repositories, the most popular first. A repository already typed as an awesome list is not read again, and a type an administrator curated is never changed.

### BR-008: Category Backfill

The backfill is idempotent, works in batches of 500 resources, and runs on one instance at a time. An administrator's start is recorded in the audit trail.

### BR-009: Backfill at Startup

The backfill at startup is on by default and runs only when the category definitions or the database schema changed since the last completed backfill.
