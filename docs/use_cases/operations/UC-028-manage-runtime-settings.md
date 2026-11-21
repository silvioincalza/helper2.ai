# Use Case: Manage Runtime Settings

## Overview

**Use Case ID:** UC-028  
**Use Case Name:** Manage Runtime Settings  
**Primary Actor:** Administrator  
**Goal:** See the configuration both applications run with, and change a daily limit or a switch at runtime without a new release, with every change recorded  
**Trigger:** The administrator opens the settings page of the back office  
**Status:** Reviewed  

**Requirements:** [FR-038, NFR-012](../../requirements.md)  
**Mockup:** [Screens and states](../../mockups/back-office.md)  
**API:** [getEffectiveConfig](../../../api/admin.yaml#getEffectiveConfig), [listRuntimeSettings](../../../api/admin.yaml#listRuntimeSettings), [changeRuntimeSetting](../../../api/admin.yaml#changeRuntimeSetting), [resetRuntimeSetting](../../../api/admin.yaml#resetRuntimeSetting), [changeRuntimeSwitch](../../../api/admin.yaml#changeRuntimeSwitch), [resetRuntimeSwitch](../../../api/admin.yaml#resetRuntimeSwitch)

## Preconditions

- The administrator is signed in on the back office with the administrator role (UC-011 BR-005).

## Main Success Scenario

1. Administrator opens the settings page of the back office.
2. System shows the effective configuration of the back office and of the public application, group by group, with each value, its unit and the variable that sets it, and the build and time at which the public application last published its settings.
3. System shows each runtime limit and switch with its default, its current value, whether it is changed from the default, whether the application uses it, its version and its last changes.
4. Administrator enters a new value for a limit, or turns a switch on or off, optionally with a reason.
5. System checks the reason and the value.
6. System stores the change as the next version of the setting and records the change in the administrator audit log.
7. System applies the change on the back office at once, and the public application applies it at its next refresh.
8. System shows the setting with its new value and its history.

## Alternative Flows

### A1: Reset to the Default

**Trigger:** The administrator asks to reset a limit or a switch to its default (step 4)  
**Flow:**

1. System checks the reason (A2).
2. System stores a change without a value as the next version of the setting, so that the default applies again, and records the reset in the administrator audit log.
3. Use case continues at step 7.

### A2: The Reason Contains Hidden Characters

**Trigger:** The reason contains a control or format character (step 5)  
**Flow:**

1. System refuses the change and says that the reason may not contain control or format characters.
2. Use case ends.

### A3: The Value Is Out of Range

**Trigger:** The value is above the maximum of the setting, or below 0 (step 5)  
**Flow:**

1. System refuses the change and says the allowed range.
2. System records an attempt above the maximum in the administrator audit log; an attempt below 0 is refused without an entry.
3. Use case ends.

### A4: Someone Changed the Setting First

**Trigger:** The setting has a newer version than the one the administrator changed or reset (step 6)  
**Flow:**

1. System refuses the change, records the conflict in the administrator audit log, and asks the administrator to load the setting again and repeat the change.
2. Use case ends.

### A5: Unknown Setting

**Trigger:** The administrator names a limit or a switch that does not exist (step 4)  
**Flow:**

1. System says that there is no such runtime setting.
2. Use case ends.

### A6: The Public Application Has Not Published Its Settings

**Trigger:** The public application has published no settings, or more than 500 (step 2)  
**Flow:**

1. System shows only the groups of the back office, without a publication time.
2. Use case continues at step 3.

## Postconditions

### Success Postconditions

- The change is stored as a new version of the setting, with who made it, when and why.
- The administrator audit log holds an entry for the change.
- Both applications apply the new value; after a reset they apply the default again.

### Failure Postconditions

- The value of the setting is unchanged.

## Business Rules

### BR-001: Runtime Settings

The runtime limits, each with its maximum, are: enrichments per Scout run (3,000), enrichment calls a day (3,000), gap rounds a day (2,000), guide model calls a day for all visitors (3,000), query translations a day (2,000), live web credits a day for all of helper2.ai (2,000), live web credits a day for the Scout's searches (3,000), page-read credits a day for product pages (3,000) and review research model calls a day (20,000). The runtime switch is Google ads (on or off). A day is a UTC day.

### BR-002: Allowed Range

A limit takes a whole number from 0 to its maximum; 0 turns the limited work off. A switch is on or off. A stored value above the maximum applies as the maximum.

### BR-003: Default Value

Without a change, a setting has the value configured for the application at start, through the variable shown with it. A reset brings that default back. The live web credits for all of helper2.ai, the live web credits for the Scout's searches and the page-read credits have their maximum as default when none is configured. An application does not start with a configured default outside the allowed range.

### BR-004: Change on the Version Seen

A change or a reset names the version of the setting it was made on. It is refused when the setting has changed since.

### BR-005: Reason

The reason is optional, at most 500 characters, and may not contain control or format characters; a reason that breaks one of these is refused. A blank reason is stored as no reason.

### BR-006: Every Attempt Is Audited

Every change and reset of an existing setting with a valid reason and a value of 0 or more is recorded in the administrator audit log under the action of changing a setting, with the value before, the value after, the reason and the outcome: changed, reset, out of range or conflict. The log is read in UC-013.

### BR-007: Change History

Every change is a new version that is never edited. The page shows the last 10 changes of each setting. Old changes are removed by UC-029 BR-004.

### BR-008: Refresh

Each application reads the runtime settings again every 20 seconds by default (`H2_RUNTIME_SETTINGS_REFRESH`, allowed from 5 to 60 seconds). The back office applies its own change right after it is stored.

### BR-009: Published Settings

The public application publishes its effective settings each time it starts, in place of the ones it published before. A group both applications have is shown once, with the values of the back office. Published settings are shown only when there are at most 500 of them. A value longer than 500 characters or with characters outside letters, digits, spaces and common punctuation is shown as "(hidden)".
