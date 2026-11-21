# Use Case: View Resource Details

## Overview

**Use Case ID:** UC-007  
**Use Case Name:** View Resource Details  
**Primary Actor:** Visitor  
**Goal:** See everything the catalog knows about one AI resource, and for every fact who states it and when, so the visitor can judge whether the resource fits.  
**Trigger:** The visitor opens the page of a resource from a result row, a home list, the News or Events page, a saved resource or a shared link.  
**Status:** Approved  

**Requirements:** [FR-010, NFR-005, NFR-010, C-005](../../requirements.md)  
**Mockup:** [Screens and states](../../mockups/uc-007-view-resource-details.md)  
**API:** [getResource](../../../api/public.yaml#getResource)

## Preconditions

- The visitor has the address of a resource page.

## Main Success Scenario

1. Visitor opens the page of a resource.
2. System finds the resource in the catalog together with the source records that list it.
3. System merges the facts the source records state into one card and applies the fields an administrator curated.
4. System gathers every stated fact with its origin, its verification and its evidence: the facts each source record states, and the facts an AI model read with the quotes that support them.
5. System gathers the relationships of the resource to other resources, vendors, services and tools, keeping verified relationships apart from unverified candidates, each with its evidence.
6. System gathers the AI summary of the resource, each claim with its citations.
7. System gathers the curated lists that name the resource, with the headings above its entry in each list, counts the lists on directory hosts that name it, and makes its rating from the figures directory hosts state (BR-011).
8. System shows the page: the title, type, host and dates, a link to open the resource on its host, the facts at a glance, the AI summary, the relationships, and, one click away, where the information comes from (each source with its mode and when it was read, every field with its origin, the evidence and the source records). Every AI-written value carries a visible label and a machine-readable mark.
9. Visitor follows the link to the original on its host.

## Alternative Flows

### A1: Address of a Merged Record

**Trigger:** The address names a source record that now belongs to another resource (step 2)  
**Flow:**

1. System finds the resource the record belongs to now.
2. Use case continues at step 3.

### A2: Resource Not in the Catalog

**Trigger:** No resource with this address exists, or it has no source record (step 2)  
**Flow:**

1. System says that the resource is not in the catalog and offers a link back to search.
2. Use case ends.

### A3: Resource on a Shadow Library

**Trigger:** The address of the resource is on a listed shadow library (step 3)  
**Flow:**

1. System says that the resource is not in the catalog, exactly as for an unknown resource.
2. Use case ends.

### A4: Sources Disagree

**Trigger:** Two sources state different values for the same field (step 4)  
**Flow:**

1. System marks the fact of each source as conflicting and shows both values with their sources.
2. Use case continues at step 5.

### A5: No AI Summary

**Trigger:** No accepted enrichment of the resource holds a summary (step 6)  
**Flow:**

1. System shows one line saying that there is no AI summary and why, from the enrichment status of the resource.
2. Use case continues at step 7.

### A6: Rate Limit Reached

**Trigger:** The visitor has made more requests than the general limit allows (step 1)  
**Flow:**

1. System refuses the request and says when the visitor can try again.
2. Use case ends.

## Postconditions

### Success Postconditions

- The visitor sees every displayed fact with the source that states it and the time it was read.
- The catalog is unchanged.

### Failure Postconditions

- The catalog is unchanged.

## Business Rules

### BR-001: Served from the Catalog Only

The page is built from the stored catalog only; opening it calls no external source and no AI model.

### BR-002: A Merged Record Leads to Its Resource

An address computed from the canonical address of a source record that was joined into another resource opens the page of that other resource; the most recently retrieved record decides.

### BR-003: Shadow Libraries Are Never Shown

A resource whose address or canonical address is on the list of shadow libraries an administrator keeps is treated as not in the catalog, here and in every list.

### BR-004: Curated Fields Win

A field an administrator curated (type, pricing, licence, open source, technical level, audience, description, language, publication date) is shown with its curated value instead of the value the sources state.

### BR-005: Disagreement Is Never Resolved Silently

When sources state different values for a field, each value is shown as conflicting with its source; the page never picks one without saying so.

### BR-006: Rejected Relationships Are Hidden

A relationship whose verification is rejected is never shown; verified relationships and unverified candidates are shown apart, ordered by kind and target name.

### BR-007: Summary from the Latest Accepted Enrichment

The AI summary comes from the latest enrichment of the resource that was validated or partially validated and holds claims; each claim lists the evidence that supports it and says whether the claim rests only on the vendor's own pages (self-declared).

### BR-008: Curated Lists Shown

The page names at most 10 curated lists that list the resource, the most popular list first, then by title, and counts the lists it does not name. A list on a directory host is never named and is not among them: the page gives only the number of such lists that name the resource.

### BR-009: AI-Written Values Are Labelled

Every value an AI model wrote (an extracted fact, a relationship found by a model, a summary claim, an AI-written description or event detail) carries a visible label and a machine-readable mark that names the fields and the models of the enrichments that wrote them.

### BR-010: General Rate Limit

A visitor may make at most 120 requests per minute to the public interface by default.

### BR-011: Rating from Directories

The page shows one rating from 1 to 5 for the resource, with one decimal, and next to it the number of directories it comes from; it never names the directories. The rating is made from the average and the number of votes each directory states (UC-030 BR-010): each average is put on the scale from 1 to 5, and the result is `(20 × 3.0 + Σ votes × average) / (20 + Σ votes)` over the directories, so a rating with few votes stays near 3. Two lists of one directory count once, with the figures read last. A resource for which no directory states figures is shown as "Not rated yet", with no figure. The rating is a stated figure and a formula, not AI-written text, and carries no AI label. The page offers to report a wrong rating (UC-009).
