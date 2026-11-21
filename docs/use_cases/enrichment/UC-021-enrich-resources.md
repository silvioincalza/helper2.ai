# Use Case: Enrich Resources

## Overview

**Use Case ID:** UC-021  
**Use Case Name:** Enrich Resources  
**Primary Actor:** Scheduler  
**Secondary Actors:** Language Model, Embedding Service, Source Provider  
**Goal:** Give each catalog resource a short summary, typed fields, relationships and a search embedding, keeping only the facts that a quote from its sources states, so that visitors can filter and find it and every AI-written fact can be checked.  
**Trigger:** A Scout run reaches a resource that changed or the next resource of the enrichment backlog, or the embedding indexer's interval passes.  
**Status:** Reviewed  

**Requirements:** [FR-028, NFR-014, C-002, C-005](../../requirements.md)  
**Layers:** Service

## Preconditions

- The resource is in the catalog, indexed, observed or curated, and has at least one source text of 40 characters or more, or was accepted from the review queue.
- For the embeddings, an Embedding Service is configured.

## Main Success Scenario

1. Scheduler picks a resource to enrich: one that changed in the current Scout run, or the next resource of the enrichment backlog.
2. System loads the resource's source texts as numbered pieces of evidence, together with the facts and labels its sources state and the fields an administrator curated.
3. System checks that the source text or the model has changed since the resource's last judged enrichment.
4. System asks the Language Model for the fields, the relationships and up to three summary sentences that the resource's type keeps, each with the quotes from the evidence that state it.
5. System checks every proposed fact against the evidence it cites, against the facts the sources state, and against the rules of the resource's type.
6. System sees that facts or the summary are still missing and reads more text from the Source Provider: the repository's read-me or the model card, then the resource's official pages.
7. System asks the Language Model again with the added text, once after the read-me or model card and once more after the official pages when facts are still missing, checks each answer the same way, and keeps the answer with more accepted facts.
8. System records the enrichment run with the model, the prompt version, the outcome and every validation issue.
9. System replaces the resource's earlier AI fields, and its AI relationships that no person has decided, with the accepted fields, relationships and summary sentences, and links each to the evidence that states it.
10. System opens review items for relationships it could not verify, for fields that conflict with a source, and for the validation issues a person must see.
11. Scheduler starts the embedding indexer, which picks the next resources whose title or description have no current embedding.
12. System sends the title and the description of each resource to the Embedding Service and stores the vector it returns for the model in use.

## Alternative Flows

### A1: Nothing Changed

**Trigger:** The source text and the model are the same as at the last judged enrichment (step 3)  
**Flow:**

1. System does not call the Language Model and records nothing.
2. System gives the resource's place back to the run's enrichment budget.
3. Use case continues at step 11.

### A2: Resource No Longer in the Catalog

**Trigger:** The resource cannot be loaded (step 2)  
**Flow:**

1. System counts the attempt as rejected and records nothing.
2. Use case continues at step 11.

### A3: Language Model Unavailable

**Trigger:** The Language Model is not configured, does not answer, or today's enrichment budget is spent (step 4)  
**Flow:**

1. System records an enrichment run with the outcome "model unavailable" and the reason.
2. The resource stays in the enrichment backlog, and the Scout run stops enriching for the rest of the run.
3. Use case continues at step 11.

### A4: Model Call Fails

**Trigger:** The Language Model answers with an error or with an answer that cannot be read (step 4)  
**Flow:**

1. System records an enrichment run with the outcome "failed" and the reason.
2. The resource stays in the enrichment backlog until it reaches the maximum number of failed runs.
3. Use case continues at step 11.

### A5: Every Fact Fails the Checks

**Trigger:** The answer proposes nothing, or no proposed fact passes the checks (step 5)  
**Flow:**

1. System marks the answer as rejected.
2. Use case continues at step 6.

### A6: No Facts Missing

**Trigger:** The first answer is validated and no expected field or summary is missing (step 6)  
**Flow:**

1. System keeps the first answer.
2. Use case continues at step 8.

### A7: Extra Text Not Available

**Trigger:** The daily budget for added reading or for page credits is spent, the Source Provider does not answer, the read-me does not name the claimed package, the extra text is empty, or the Language Model fails in the added round (step 6)  
**Flow:**

1. System keeps the best answer it has so far.
2. Use case continues at step 8.

### A8: Rejected Answer

**Trigger:** The kept answer has no accepted fact (step 9)  
**Flow:**

1. System removes the resource's earlier AI fields and undecided AI relationships and keeps only the unverified or conflicting values of the answer; no fact of the answer counts as verified.
2. System opens a review item with the validation issues that a person must see.
3. Use case continues at step 11.

### A9: Embedding Text Refused

**Trigger:** The Embedding Service refuses the text of a resource (step 12)  
**Flow:**

1. System tries the resources of the batch one at a time and remembers each refused text for the model.
2. System leaves a refused resource out until its title or description changes.
3. Use case ends.

### A10: Embedding Service Rate Limited

**Trigger:** The Embedding Service answers that too many requests were sent (step 12)  
**Flow:**

1. System pauses the embedding indexer for 2 minutes.
2. Use case ends.

## Postconditions

### Success Postconditions

- The enrichment run is recorded with its outcome, model, prompt version and validation issues.
- Every stored AI field, relationship and summary sentence of the resource that cites a quote is linked to the evidence that holds it; only the AI estimates of BR-007 may stand without a quote.
- The resource's enrichment state is the outcome of its last run; a failed run shows as rejected.
- The resource has an embedding for the current model and its current title and description, unless the Embedding Service refused that text.

### Failure Postconditions

- An answer that fails the checks never adds a verified AI fact to the catalog.
- When the model is unavailable or the call fails, the resource's earlier AI facts stay unchanged.

## Business Rules

### BR-001: Quote Gate

Every field, relationship and summary sentence must cite at least one quote. A quote has 20 to 300 characters and must appear in the piece of evidence it cites; a fact with no valid quote is rejected.

### BR-002: Outcome of an Answer

An answer is validated when every proposed fact passes the checks and no relationship is left unverified, partial when some facts pass, and rejected when it proposes nothing or no fact passes.

### BR-003: Source Facts Win

A field whose value differs from the value a source states is kept as conflicting and goes to review. When only the vendor's own pages state the different value, the field is rejected.

### BR-004: Values That Must Be Stated

For pricing, the licence, open source, the platforms, the type and a use case, the quote must state the value itself; otherwise the value is kept as unverified and does not count as verified.

### BR-005: Fields per Type

A resource keeps the fields audience, tech level, pricing, open source, licence, use case and platforms. A library keeps no use case. Articles, news and papers keep only audience, tech level and use case, and only relationships to what they are about. A resource of type other may also receive a type. Courses and events may also say what they are about.

### BR-006: Limits of an Answer

An answer keeps at most 3 summary sentences of at most 300 characters each, at most 3 use cases, at most 3 relationships that only the vendor's own pages state, and at most 3 relationships that say what a resource is about. A summary sentence that cites text which may not support a claim, or that the claim check refuses, is dropped.

### BR-007: Judgement Fields

Audience and tech level are judgements of the model: they are stored as AI estimates and never count as verified.

### BR-008: Daily Budgets

The enrichment uses at most 500 model calls per UTC day by default, the added reading rounds included; of these, the added reading rounds use at most 400. The official pages use at most 20 page-reading credits per day. Administrators can change both call budgets at runtime.

### BR-009: Unchanged Input Is Not Asked Again

The fingerprint of an enrichment covers the source text, a fixed epoch and the model, not the prompt. When the last run that judged an answer has the same fingerprint, the model is not called again, and a rejected answer is not asked for again on the same text.

### BR-010: Retries

A resource whose last run failed is tried again in later runs until it has 3 failed runs; a resource whose run found the model unavailable waits in the backlog without a limit. A resource whose source text changed in a collector's pass is tried in that run whatever its count.

### BR-011: Model

By default the enrichment uses the lighter of the two configured NVIDIA Nemotron models, or the reasoning model when no lighter one is configured, with at most 8,192 output tokens.

### BR-012: Embeddings

The embedding text of a resource is its title and its description. The indexer runs every 5 seconds by default, 30 seconds after startup, one resource at a time, on one instance at a time. A resource is embedded again when its text changes. Demo resources are never embedded.

### BR-013: Enrichment History

An enrichment run is kept for 90 days by default; an older run is deleted only when a newer run of the same resource exists and no fact, relationship, summary sentence or review item points to it.

### BR-014: Model Calls per Resource

One enrichment makes at most three model calls: with the source records, then with the read-me or model card, then with the official pages. The answer of an added call is kept only when it has more accepted facts than the best answer so far.

### BR-015: Curated and Decided Values Stay

A value of the model never replaces a field an administrator curated (UC-026 BR-003). A relationship a person accepted or rejected, or whose target a person chose, stays as decided when a new run replaces the others.

### BR-016: Summary Shown

A resource shows the summary of its latest validated or partial run that kept one. A later run that keeps no summary sentence leaves that summary in place.

### BR-017: Order of the Backlog

The Scout takes from the backlog first the resources an administrator moved to the top (UC-022 BR-003), then the resources accepted from the review queue, then, for a quarter of the batch, the never-enriched resources that waited longest, then the other never-enriched resources and last the ones to try again, the most popular first.
