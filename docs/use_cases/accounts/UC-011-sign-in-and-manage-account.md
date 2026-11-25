# Use Case: Sign In and Manage Account

## Overview

**Use Case ID:** UC-011  
**Use Case Name:** Sign In and Manage Account  
**Primary Actor:** Visitor, Signed-in User  
**Secondary Actors:** Identity Provider  
**Goal:** Sign in with GitHub so that saved resources and settings follow the person across devices, see the allowances left today, change the account's preference, and delete the account at any time  
**Trigger:** The visitor chooses to sign in, or the signed-in user opens the account page  
**Status:** Approved  

**Requirements:** [FR-016, FR-018, FR-019](../../requirements.md)  
**Mockup:** [Screens and states](../../mockups/uc-011-sign-in-and-manage-account.md)  
**API:** [getMe](../../../api/public.yaml#getMe), [changePreferences](../../../api/public.yaml#changePreferences), [deleteMyAccount](../../../api/public.yaml#deleteMyAccount)

## Preconditions

_None — anyone may start the sign-in; a site without an identity provider is covered in A1._

## Main Success Scenario

1. Visitor chooses to sign in and picks one of the offered identity providers.
2. System sends the visitor to the identity provider.
3. Identity Provider confirms who the visitor is.
4. System creates the account at the first sign-in, or records the display name and the time of this sign-in, and returns the visitor signed in to the page they were on (BR-010).
5. Signed-in User opens the account page.
6. System shows who is signed in, the AI answers, AI setups, web searches and guide searches left today, each only when its limit applies, with the time they reset, and the home page motion preference.
7. Signed-in User turns the home page motion on or off.
8. System stores the preference and shows it.
9. Signed-in User asks to delete the account and confirms.
10. System signs the user out, deletes the account with all its saved resources, and shows the site signed out.

## Alternative Flows

### A1: No Identity Provider Is Configured

**Trigger:** The site has no identity provider (step 1)  
**Flow:**

1. System offers no sign-in, and the sign-in page says that sign-in is not offered here.
2. Use case ends.

### A2: Sign-In Fails

**Trigger:** The identity provider refuses the sign-in or is not available (step 3)  
**Flow:**

1. System returns the visitor to the site with the message that sign-in did not work and a link to try again.
2. The visitor goes on using the site without an account.
3. Use case ends.

### A3: Not Signed In

**Trigger:** A visitor who is not signed in opens the account page (step 5)  
**Flow:**

1. System says that the visitor is not signed in and that there is no account to delete, with a link to the privacy page and, when an identity provider is configured, a link to sign in.
2. Use case ends.

### A4: The Account No Longer Exists

**Trigger:** The person is still signed in but the account was deleted when they open the account page, change the preference, delete the account or use their saved resources (step 5)  
**Flow:**

1. System ends the sign-in, shows the site signed out, and asks the person to sign in again, with a link to sign in.
2. Use case ends.

### A5: The User Cancels the Deletion

**Trigger:** The signed-in user cancels the confirmation (step 9)  
**Flow:**

1. System closes the confirmation and keeps the account.
2. Use case continues at step 6.

### A6: The Deletion Fails

**Trigger:** The account cannot be deleted (step 10)  
**Flow:**

1. System says that the deletion did not work and to try again later; the account stays, and the sign-in has already ended (BR-007).
2. Use case ends.

### A7: Sign Out

**Trigger:** The signed-in user chooses to sign out (step 6)  
**Flow:**

1. System ends the sign-in and returns the person to the site without an account; search keeps working.
2. Use case ends.

### A8: The Preference Cannot Be Stored

**Trigger:** The preference cannot be stored (step 8)  
**Flow:**

1. System says that the preference was not saved and to try again later; the stored preference is unchanged.
2. Use case continues at step 6.

## Postconditions

### Success Postconditions

- The account exists with the provider, the user's identifier at the provider, the display name and the time of the last sign-in.
- A changed preference is stored on the account.
- After a deletion, neither the account nor its saved resources exist.

### Failure Postconditions

- A failed sign-in creates no account.
- A failed or cancelled deletion leaves the account and its saved resources unchanged.

## Business Rules

### BR-001: Sign-In Is Optional

Search, the AI guide and saving resources in the browser work without an account. An account only keeps saved resources and the preference across devices.

### BR-002: One Account per Provider Identity

An account is identified by the identity provider and the user's identifier at that provider; signing in again with the same identity uses the same account. The display name is the name the provider gives, or the user name when there is no name; each sign-in records it again.

### BR-003: Data Kept on the Account

The account keeps only the provider, the identifier at the provider, the display name, the time of the last sign-in and the home page motion preference. searchavo.ai keeps nothing else from the person's profile at the provider, and no access to the person's account at the provider after sign-in.

### BR-004: Home Page Motion

The home page motion (the radar and the moving lists) is on by default for a new account.

### BR-005: Administrator Role

Every signed-in person has the user role. Only the back office grants the administrator role, and only to an identity named in the administrator list (`SA_ADMINS`), by provider and identifier, or by a verified email address for providers that give one.

### BR-006: Daily Allowances

The allowances left today are counted per address, the same signed in or not, and reset at the next midnight UTC. Reading them uses none of them. The back office shows no allowances.

### BR-007: Deletion Removes Everything

Deleting an account removes it at once together with its preference and all its saved resources. The sign-in is ended first, and when it cannot be ended nothing is deleted. User reports stay: a report is never linked to an account. The administrator audit entries and the runtime setting changes that name an administrator by the identifier at the provider stay for their retention periods (UC-029 BR-003 and BR-004).

### BR-008: Inactive Accounts

An account that has not signed in for a long time is deleted automatically (UC-029 BR-002).

### BR-009: Sign-In Lifetime

A sign-in ends when the person signs out, or 30 minutes after their last request. The public site and the back office keep separate sign-ins.

### BR-010: Page After Sign-In

After the sign-in the visitor returns to the page they were on, or to the home page when they opened the sign-in page directly. Only a page of searchavo.ai is accepted as the page to return to.
