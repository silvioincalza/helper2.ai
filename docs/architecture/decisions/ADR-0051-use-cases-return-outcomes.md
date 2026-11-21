# 0051 — Use cases return outcomes

**Depends on:** [0002](ADR-0002-modules-modular-monolith-hexagonal-where-the-rules-live.md), [0003](ADR-0003-contract-first-rest.md), [0036](ADR-0036-identity-and-the-admin-host.md).

## Context

A use case is the business action ([0002](ADR-0002-modules-modular-monolith-hexagonal-where-the-rules-live.md) §7). It
can end in more than one way: the bookmark is saved, the address is not a web page, the account is full. The caller
must tell these apart, because each one is a different answer to the user.

The API answers an error as a problem ([0003](ADR-0003-contract-first-rest.md) §5): `ApiProblem` is a runtime exception
with a `ProblemType`, and `ApiExceptionHandler` writes it. A use case that throws `ApiProblem`, `NotFoundException`
or `BadRequestException` has three problems:

- A business rule names an HTTP status and a problem code. The rule knows the web, and a caller that is not a
  controller (a schedule, another use case, a test) gets a web error.
- The throw is also the rollback. A use case that writes first and checks after (the bookmark cap is counted after
  the insert, under the account's lock) keeps its data right only because an exception leaves the transaction. Which
  rows stay depends on which exception was thrown and on a `noRollbackFor` far from the rule.
- The sentence in the exception is not the answer. `ApiExceptionHandler` answers every `BadRequestException` with
  "Invalid request parameters.", so the text written next to the rule never reaches a client.

Some use cases already answer with a sealed type (`AddHomeExample.Outcome`, `EditCandidate.Outcome`,
`ManageKnownTargets.Saved`), and their controllers map it. This record makes that the rule for every use case.

## Decision

1. **A use case returns an outcome for every result its caller must tell apart.** The outcome is a sealed interface
   named `Outcome`, nested in the use case, with one record per case: `AddBookmark.Outcome` is `Added`, `InvalidUrl`
   or `OverLimit`; `MergeLocalBookmarks.Outcome` is `Merged`, `InvalidUrl` or `OverLimit`; `ChangePreferences.Outcome`
   is `Changed` or `NoSuchUser`; `CurateField.Outcome` is `Curated`, `UnknownField`, `ValueNotAllowed`,
   `InvalidDescription`, `NotADate`, `DateInTheFuture` or `NoSuchResource`. A use case with two actions that end in
   different ways has one type per action: `CurateField.set` answers `Outcome`, `CurateField.drop` answers
   `DropOutcome`. A use case with one result returns it plainly (`ListBookmarks`, `ListAuditEntries`,
   `ListReviewItems`, `ListEnrichmentQueue`). An outcome that a caller passes on as it is has a sealed part for it:
   `ResolveReview.Outcome` is `Resolved`, `NotOpen`, `NotFound` or a `NotAdded` (the candidate, the proposal or the
   draft that an accept could not add), and `DecideReviewItem` and `ResolveReviewItems` hand the `NotAdded` on.
2. **A record carries data, never a sentence and never a status.** `OverLimit(limit)` holds the cap, `Added(bookmark)`
   the saved row. The words for the user and the HTTP status are the web layer's.
3. **No use case and no domain class speaks HTTP.** A class of a `usecase` package or of a domain package does not
   depend on `ApiProblem`, on a subtype of it (`NotFoundException`, `BadRequestException`), on `ProblemType` or on
   `Problems`. A bug still throws, and is a 500: `ResolveReviewItem` throws `IllegalArgumentException` for a decision
   that is not ACCEPTED or REJECTED, which the contract lets no request send. `SignInAgain`, the problem of a session without an account
   ([0036](ADR-0036-identity-and-the-admin-host.md) §6), is in `identity.internal`, next to the adapter that
   throws it (`SignedInUser`).
4. **The controller maps the outcome with one exhaustive `switch`.** Every case is named, with no `default`, so a new
   case does not compile until the controller answers it. A refusal is thrown there as the `ApiProblem` it is, and
   `ApiExceptionHandler` stays the one writer of the problem response: `BookmarksController` throws
   `BadRequestException` for `InvalidUrl` and `OverLimit`, `PreferencesController` throws
   `ApiProblem(SIGN_IN_REQUIRED)` for `NoSuchUser`, `ReviewController` maps `CurateField.Outcome`,
   `CurateField.DropOutcome` and `ChangeRelationshipTarget.Outcome`. A refusal whose reason the user must read is an
   `ApiProblem` with its own sentence as `detail`: `ApiExceptionHandler` answers "Invalid request parameters." for
   every `BadRequestException`, whatever its text. A use case that calls another use case maps its
   outcome the same way, into its own: `ChangeRelationshipTarget` turns `AddCuratedResource.Outcome.InvalidUrl` into
   its `InvalidResourceUrl`. The sentences of an outcome that two endpoints answer are written once, in the `web`
   package: `ReviewProblems.notAdded` for the single and the bulk decision, `KnownTargetProblems.listIsFull` for the
   known targets, the review decision and the group decision, `CuratedFieldProblems.reason` for a curated field and
   for a confirmed value of an accepted candidate. The refusals of a value are a sealed part of the outcome,
   `CurateField.Outcome.ValueRefused` (`UnknownField`, `ValueNotAllowed`, `InvalidDescription`, `NotADate`,
   `DateInTheFuture`), and `ConfirmedValueRefused` carries the field and that refusal.
5. **A refusal after a write is a `Refused` record, and it runs in a unit of work.** `Refused` is a marker interface
   of the kernel (`ai.helper2.shared`). `TransactionalUnitOfWork.run` looks at the result of the work: when it is
   `Refused` and this unit opened the transaction, the transaction is rolled back and the result is returned. A unit
   that joined a transaction never marks it: the unit that opened it decides. An exception rolls the transaction back and is thrown again.
   `AddBookmark` and `MergeLocalBookmarks` check the address first, with no transaction, then lock the account, insert
   and count inside `UnitOfWork.run`; `OverLimit` is `Refused`, so nothing of a refused addition or merge is kept.
   `ChangeRelationshipTarget` runs in `UnitOfWork.run` too: it can add a resource (`AddCuratedResource`) and then find
   that the item cannot take the link, so `NotFound`, `NotOpen`, `NotARelationship`, `TitleTaken`, `UnknownTarget`
   and `SelfLink` are `Refused` and the added resource is undone. `ResolveReview` runs in `UnitOfWork.run`:
   `ConfirmedValueRefused` is `Refused`, so a candidate whose confirmed value the catalog refuses leaves no resource
   and stays open. Its other `NotAdded` cases are not `Refused`: nothing was written but the audit entry of the
   refused known target or example, and that entry stays. `DecideRelationshipItem` runs each item of a group in its
   own `UnitOfWork.run`, and `DecideRelationshipTargetGroup` is a loop over their outcomes with no transaction of its
   own: `Decided`, `Skipped` (nothing written, or only the `NOT_OPEN` entry of a rejection) or `LinkUndone`, which is
   `Refused`: the item was decided between the target change and the decision, so the target change is rolled back.
   No transaction names an exception it does not roll back for. A use case with no write to undo needs
   neither, and keeps the `@Transactional` subclass of its wiring: `CurateField` refuses before any write, and
   `ResolveReviewItem` keeps what it wrote (the audit line of a decision on an item that is no longer open).
   `UnitOfWorkConfiguration` makes the one `UnitOfWork` bean in both apps: the bookmarks are a use case of the public
   app.
6. **A bound the contract states is not checked again.** The contract gives `page` a minimum of 0 and `size` 1 to 100
   on `GET /api/v1/admin/audit`, the generated interface carries them as constraints, and Spring answers 400 before
   the controller runs. `ListAuditEntries` reads the page it is asked for. `GET /api/v1/admin/review-items` states
   `page`, `size` and the pattern and length of `category` the same way, so `ListReviewItems` checks none of them.
   The same holds for `size` on `GET /api/v1/admin/enrichment-queue` (`ListEnrichmentQueue`), for the 1 to 100 ids and
   the decision of a bulk decision (`ResolveReviewItems`) and for the 500 characters of a runtime setting's reason
   (`ChangeRuntimeSetting`). A rule the contract cannot state stays an outcome: a repeated id is `RepeatedId`, a
   reason with a control or format character is `InvalidReason`.
7. **The rule is a test, and it has no exception.** `ModularityRules.useCasesDoNotSpeakHttp` runs in the
   `ModularityTest` of each app ([0002](ADR-0002-modules-modular-monolith-hexagonal-where-the-rules-live.md) §8) on every
   class of a `usecase` package and of a domain package. There is no list of classes it skips, so no class can start
   to throw. `ModularityRules.aUseCaseThatCanRefuseAfterAWriteRunsInAUnitOfWork` runs next to it: a class that
   declares a `Refused` outcome depends on `UnitOfWork` (§5).
8. **The response does not depend on where the rule lives.** Status, problem `type`, `code`, `title` and `detail` of
   every identity, catalog and admin endpoint are what the contract states, whatever the use case returns; the API
   tests of the account, the bookmarks, the preferences, the audit trail, the review queue, the review decisions, the
   curated fields, the relationship targets and their groups, the known targets, the runtime settings and the
   enrichment queue pin them, with the rows and the audit entries a refusal leaves. Accepting a candidate whose
   confirmed value the catalog refuses is a 400 `invalid-request`, the problem `CurateField` gives for that value on
   its own endpoint: its `detail` names the field and has the same reason ("The catalog refuses the confirmed pricing.
   This value is not allowed for pricing. Edit the candidate, then accept again.").

### Code

- `backend/libs/kernel/src/main/kotlin/ai/helper2/shared/Refused.kt`, `UnitOfWork.kt` — the marker and the port.
- `backend/libs/platform/src/main/java/ai/helper2/platform/TransactionalUnitOfWork.java`,
  `UnitOfWorkConfiguration.java` — the rollback rule and the bean of both apps.
- `backend/libs/core/src/main/java/ai/helper2/identity/usecase/` (`AddBookmark`, `MergeLocalBookmarks`,
  `BookmarkRules`, `ChangePreferences`, `ListAuditEntries`) — the outcomes;
  `.../identity/config/IdentityUseCaseConfiguration.java` — the wiring; `.../identity/internal/SignInAgain.java`.
- `backend/libs/core/src/main/java/ai/helper2/catalog/usecase/` (`AddCuratedResource`, `ChangeRelationshipTarget`,
  `CurateField`, `ListReviewItems`, `ResolveReviewItem`) — the outcomes;
  `backend/apps/backoffice/src/main/java/ai/helper2/catalog/config/CatalogBackOfficeConfiguration.java` — the wiring.
- `backend/apps/public/src/main/java/ai/helper2/identity/web/BookmarksController.java`, `PreferencesController.java`,
  `backend/apps/backoffice/src/main/java/ai/helper2/identity/web/AuditController.java`,
  `backend/apps/backoffice/src/main/java/ai/helper2/admin/web/ReviewController.java` — the mapping.
- `backend/apps/backoffice/src/main/java/ai/helper2/admin/usecase/` (`ResolveReview`, `DecideReviewItem`,
  `ResolveReviewItems`, `DecideRelationshipItem`, `DecideRelationshipTargetGroup`, `ChangeRuntimeSetting`,
  `ResetRuntimeSetting`, `ListEnrichmentQueue`) — the outcomes; `CheckAwesomeLists.java` (`setAsAwesomeList`) reads
  the outcome of `CurateField`; `.../admin/config/AdminUseCaseConfiguration.java` — the wiring.
- `backend/apps/backoffice/src/main/java/ai/helper2/admin/web/ReviewProblems.java`, `CuratedFieldProblems.java`,
  `KnownTargetProblems.java`,
  `RelationshipTargetsController.java`, `KnownTargetsController.java`, `RuntimeSettingsController.java` — the mapping.
- `backend/libs/platform/src/testFixtures/java/ai/helper2/testing/architecture/ModularityRules.java` — the rule.
- Tests: `TransactionalUnitOfWorkTest` (rollback for a `Refused` result, a joined transaction is not marked),
  `BookmarkOutcomesTest`, `ChangePreferencesTest`, `PreferencesControllerTest`, `BookmarkCapIT` (the outcome and the
  rows after a refusal, two additions at once), `AccountApiIT`, `AdminAuditApiIT` (a page outside the bounds is a
  400), `CurateFieldTest`, `ChangeRelationshipTargetIT` (the outcome and the rows after a refused link),
  `CheckAwesomeListsTest`, `CuratedFieldApiIT`, `RelationshipTargetApiIT` and `ReviewItemsApiIT` (the problem of
  every refusal), `CandidateEditApiIT` (a confirmed value the catalog refuses, a candidate that cannot be read),
  `ReviewBulkApiIT` and `ReviewBulkResearchedApiIT` (the bounds, a repeated id, a batch that stops),
  `KnownTargetProposalApiIT`, `KnownTargetsApiIT`, `HomeExamplesApiIT` (the refusal and its audit entry),
  `RelationshipTargetGroupsApiIT` (the rows and the audit entries of a partly refused group),
  `RelationshipTargetGroupUndoApiIT` (a link undone, a rejection skipped), `AdminRuntimeSettingsApiIT`,
  `EnrichmentQueueApiIT`, `RuntimeSettingReasonTest`.

## Alternatives considered

- **One generic `Outcome<T, Refusal>` with a few kinds (invalid, not found, conflict, refused).** One type to learn
  and one mapping. The kinds are HTTP statuses under another name, and the contract has five conflict codes that one
  kind cannot tell apart. Rejected.
- **Domain exceptions, mapped in one handler.** The use cases would no longer name HTTP. The throw stays the
  rollback, a caller that wants to go on after a refusal still needs a `catch`, and the compiler does not tell a
  controller that a new case exists. Rejected.
- **`@Transactional` subclasses in `config` that look at the outcome.** No new marker in the kernel. The rule "this
  refusal undoes the write" would live in the wiring, far from the record that means it. Rejected.
- **ArchUnit's `FreezingArchRule`.** It stores the known violations and fails on a new one with no code of ours. The
  store is a generated file nobody reads, and the rule has no violation to store. Rejected.
- **A list of classes the rule skips, kept empty, with a test that it stays empty.** It would guard against a new
  exception. A list nobody may add to is dead code, and adding a name to it is as visible in a review as writing the
  list again. Rejected.
- **One transaction for a whole group or batch.** One commit, fewer round trips. One item that fails would undo the
  others, and a refused item could not keep its audit entry. Rejected.
- **Keep the exceptions where the response is the same.** No change for a `BadRequestException` whose text is never
  shown. The rule would then have exceptions that last, and the next use case would copy them. Rejected.

## Consequences

- A use case is tested by its outcome, with no web class on the test's path.
- A new case of an outcome breaks the compile of its controller until it has an answer.
- A refusal after a write is visible in the type: the record implements `Refused`.
- A controller has a few more lines: the `switch` and the sentences.
- Both apps have a `UnitOfWork` bean.

### Known limits

- A `Refused` result inside a transaction this unit joined does not roll back: the caller that opened the
  transaction must look at the outcome. `TransactionalUnitOfWorkTest` holds both halves of the rule.
- A refusal must be the top-level result of the unit. A `Refused` value wrapped in another result (an `Optional`, a
  record) is committed: `TransactionalUnitOfWorkTest` pins it. `ModularityRules` holds the pairing: a class that
  declares a `Refused` outcome depends on `UnitOfWork`.
- A confirmed value the catalog refuses is in a candidate only when the rules of `EditCandidate` and of `CurateField`
  differ. The 400 names the field and the reason; it does not repeat the value.
- A bulk decision stops at the first item an accept cannot add and answers its problem. The items before it stay
  decided, each in its own unit of work; the ones after it are not tried.
- `ResolveReview` joined to another unit does not undo a refused confirmed value: only `DecideRelationshipItem`
  joins it, with relationship candidates, and they add nothing.
- `CheckAwesomeLists` does not set a resource that is gone when its turn comes, and goes on with the next one.
- `Refused` undoes the whole unit. A use case that must keep one write and undo another needs two units.
- The rule reads classes. A constant of an HTTP type is copied by the compiler and leaves no dependency; `ProblemType`
  is an enum, so its values are not copied.
- `CurrentUser.id()` still throws `SignInAgain`, from the adapter: `KnownAccountOnly` answers it before any controller
  runs.

## Diagrams

[flow](../diagrams/flow-0051-use-cases-return-outcomes.svg) · [sequence](../diagrams/sequence-0051-use-cases-return-outcomes.svg)

## Backlog

- Features: [F1.11](https://github.com/silvioincalza/helper2.ai/issues/30) (use cases return an outcome, and the web
  layer maps it to a problem, [S1.11.22](https://github.com/silvioincalza/helper2.ai/issues/726)).
  The `admin` use cases and the transactions without `noRollbackFor`:
  [T1.11.2](https://github.com/silvioincalza/helper2.ai/issues/742).
- Open issues that change this decision: none.
