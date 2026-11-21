# Use Case: Curate Resource Fields

## Overview

**Use Case ID:** UC-026  
**Use Case Name:** Curate Resource Fields  
**Primary Actor:** Administrator  
**Goal:** Fix a resource's field once and for all, by setting the resource's own value over every source and every enrichment run, or by dropping what the AI said about it.  
**Trigger:** The Administrator sees a wrong value next to a field in the evidence of an enrichment check.  
**Status:** Draft  

**Requirements:** [FR-033](../../requirements.md)  
**API:** [setResourceField](../../../api/admin.yaml#setResourceField), [dropResourceField](../../../api/admin.yaml#dropResourceField)

## Preconditions

- The Administrator is signed in to the back office with the ADMIN role, over the VPN.
- The Administrator is looking at the evidence of a review item about a resource (UC-024).

## Main Success Scenario

1. The Administrator chooses "Set the value" next to a field of the resource.
2. The system offers the values the field takes: a list of values, a licence identifier, a date, or a text box for a description.
3. The Administrator enters the value and saves it.
4. The system checks that the field can be set and that the value is one the field takes.
5. The system writes the value to the resource, marks the field as curated and rejects the AI's values of that field.
6. The system records in the audit trail the field, the value before and the value after.
7. The system shows the evidence again, with the field "Curated by Helper2".

## Alternative Flows

### A1: Drop the AI value

**Trigger:** The Administrator chooses "Drop the AI value" next to a field instead of setting one (step 1)  
**Flow:**

1. The system rejects every value of that field that the AI extracted, read as self-declared or estimated; a value a source states or an administrator set stays.
2. The system records in the audit trail the field and how many values were dropped.
3. The page then shows the source's value, or "Not stated".
4. Use case continues at step 7.

### A2: Field is not written by the AI

**Trigger:** The Administrator drops the language, the published date or the description (step 1)  
**Flow:**

1. The system refuses and names the field the AI does not write.
2. Use case ends.

### A3: Value is not allowed

**Trigger:** The field cannot be set, or the value is not one the field takes (step 4)  
**Flow:**

1. The system refuses and names the field and the reason, never the value, under the field.
2. Use case continues at step 3.

### A4: Resource does not exist

**Trigger:** The resource was removed in the meantime (step 5)  
**Flow:**

1. The system changes nothing and says that the resource was not found.
2. Use case ends.

## Postconditions

### Success Postconditions

- The resource holds the value in its own field, the field is listed as curated, and the AI's values of that field are rejected.
- After a drop, the AI's values of the field are rejected and the values of sources and administrators are unchanged.
- The audit trail holds the change, with the value before and after, each cut at 100 characters.

### Failure Postconditions

- A refused value changes nothing on the resource and writes no audit entry.

## Business Rules

### BR-001: Fields an administrator sets

An administrator sets the type, pricing, licence, open source, tech level, audience, language, published date and description of a resource; the use case and the platforms are not set by hand.

### BR-002: Values a field takes

The type is any resource type but OTHER; the audience is any audience but UNKNOWN; pricing and tech level are values of their own lists; the licence is an SPDX identifier of 2 to 40 characters; open source is true or false; the language is one the catalog takes; the published date is a day written as YYYY-MM-DD and not in the future; the description is 1 to 4,000 characters and is stored as written by the administrator.

### BR-003: A curated value wins everywhere and stays

No record of a source and no enrichment run changes a curated value. The resource page, the resource card and the search results show it before any source value or conflict, and filters and ranking read it.

### BR-004: Fields the AI writes

The AI writes the type, pricing, licence, open source, tech level, audience, use case and platforms; only those can be dropped. A dropped estimate is not shown again.

### BR-005: Audit of curation

Setting a value records the field, the value before (marked "(curated)" when a person had set it, "(none)" when it was empty) and the value after, each cut at 100 characters. Dropping records the field and the number of values dropped.
