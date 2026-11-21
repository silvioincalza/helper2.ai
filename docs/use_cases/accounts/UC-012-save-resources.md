# Use Case: Save Resources

## Overview

**Use Case ID:** UC-012  
**Use Case Name:** Save Resources  
**Primary Actor:** Visitor, Signed-in User  
**Goal:** Keep a list of resources to come back to, in the browser without an account and on the account across devices once signed in  
**Trigger:** The visitor saves a resource from its result row or its resource page  
**Status:** Approved  

**Requirements:** [FR-015, FR-017](../../requirements.md)  
**Mockup:** [Screens and states](../../mockups/uc-012-save-resources.md)  
**API:** [listBookmarks](../../../api/public.yaml#listBookmarks), [addBookmark](../../../api/public.yaml#addBookmark), [deleteBookmark](../../../api/public.yaml#deleteBookmark), [mergeLocalBookmarks](../../../api/public.yaml#mergeLocalBookmarks)

## Preconditions

- The resource is shown on a result row or on its resource page.

## Main Success Scenario

1. Visitor saves a resource.
2. System keeps the saved resource in this browser and shows it on the Saved page, with the note that it is saved in this browser only.
3. Visitor signs in (UC-011).
4. System moves the resources saved in the browser into the account, keeps the resources the account already has, and removes them from the browser.
5. Signed-in User saves another resource or removes a saved one.
6. System stores the change on the account.
7. Signed-in User opens the Saved page on any device.
8. System shows the resources saved on the account, the most recently saved first, each with its type, its host and the day it was saved, and lets the user narrow the list to one type.

## Alternative Flows

### A1: The Browser Cannot Store

**Trigger:** The browser does not allow the site to keep data, for example in a private window (step 2)  
**Flow:**

1. System keeps the saved resource for this visit only and says that the browser blocked storage, so saved resources are lost when the visitor leaves the page.
2. Use case ends.

### A2: The Move into the Account Fails

**Trigger:** The resources saved in the browser cannot be moved into the account (step 4)  
**Flow:**

1. System keeps the resources in the browser, shows them, and offers to try again.
2. Signed-in User chooses to try again.
3. Use case continues at step 4.

### A3: A Web Address Is Not Valid

**Trigger:** A saved resource has an address that is not a web address, in a move or in a single save (step 4)  
**Flow:**

1. System refuses the whole request and stores nothing.
2. System says that the list could not be synchronised with the account; in a move the resources stay in the browser (A2), in a single save the resource leaves the list.
3. Use case ends.

### A4: The Account Is Full

**Trigger:** The account would hold more than 500 saved resources after a move or a single save (step 4)  
**Flow:**

1. System refuses the whole request and stores nothing.
2. System says that the list could not be synchronised with the account; in a move the resources stay in the browser (A2), in a single save the resource leaves the list.
3. Use case ends.

### A5: Saving or Removing on the Account Fails

**Trigger:** The change cannot be stored on the account (step 6)  
**Flow:**

1. System puts the list back as it was before the change and says that the change did not work.
2. Use case ends.

### A6: Nothing Is Saved

**Trigger:** The list of saved resources is empty (step 8)  
**Flow:**

1. System says that nothing is saved yet and how to save a resource, with a link back to search.
2. Use case ends.

## Postconditions

### Success Postconditions

- Without an account, the saved resources are kept in this browser.
- With an account, the saved resources are stored on the account with the time each was first saved, and the browser holds none of them anymore.

### Failure Postconditions

- A refused request leaves the saved resources of the account unchanged.
- Resources saved in the browser are not lost when they cannot be moved into the account.

## Business Rules

### BR-001: Saving Without an Account

A visitor who is not signed in saves resources in the browser only; they are not sent to helper2.ai until the visitor signs in.

### BR-002: At Most 500 Saved Resources

An account keeps at most 500 saved resources. A save or a move that would go above the limit is refused as a whole; a move in several batches (BR-006) is refused batch by batch.

### BR-003: Web Address

The address of a saved resource is a web address starting with http or https and is at most 2,000 characters long.

### BR-004: What Is Saved

A saved resource keeps the resource identifier, its title (at most 300 characters), its address, its type when known, and the time it was saved. It keeps its own copy of title and address, so it stays on the list even when the catalog changes or no longer holds the resource.

### BR-005: Saving Twice Changes Nothing

Saving a resource that is already saved changes nothing: the time it was first saved stays. Moving the same browser list twice gives the same result.

### BR-006: Moving in Batches

The browser moves at most 200 saved resources into the account at a time and removes each batch from the browser once the account has it.

### BR-007: Order

The Saved page shows the resources saved on the account the most recently saved first.

### BR-008: Saved Resources Follow the Account

The saved resources of an account are deleted with the account (UC-011 BR-007).
