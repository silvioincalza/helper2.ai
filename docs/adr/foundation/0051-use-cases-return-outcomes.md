# 0051 — Use cases return outcomes

**Depends on:** [0002](0002-modules-modular-monolith-hexagonal-where-the-rules-live.md), [0003](0003-contract-first-rest.md), [0036](../identity/0036-identity-and-the-admin-host.md).

## Context

A use case is the business action ([0002](0002-modules-modular-monolith-hexagonal-where-the-rules-live.md) §7). It
can end in more than one way: the bookmark is saved, the address is not a web page, the account is full. The caller
must tell these apart, because each one is a different answer to the user.

The API answers an error as a problem ([0003](0003-contract-first-rest.md) §5): `ApiProblem` is a runtime exception
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
   is `Changed` or `NoSuchUser`. A use case with one result returns it plainly (`ListBookmarks`, `ListAuditEntries`).
2. **A record carries data, never a sentence and never a status.** `OverLimit(limit)` holds the cap, `Added(bookmark)`
   the saved row. The words for the user and the HTTP status are the web layer's.
3. **No use case and no domain class speaks HTTP.** A class of a `usecase` package or of a domain package does not
   depend on `ApiProblem`, on a subtype of it (`NotFoundException`, `BadRequestException`), on `ProblemType` or on
   `Problems`. A bug still throws, and is a 500. `SignInAgain`, the problem of a session without an account
   ([0036](../identity/0036-identity-and-the-admin-host.md) §6), is in `identity.internal`, next to the adapter that
   throws it (`SignedInUser`).
4. **The controller maps the outcome with one exhaustive `switch`.** Every case is named, with no `default`, so a new
   case does not compile until the controller answers it. A refusal is thrown there as the `ApiProblem` it is, and
   `ApiExceptionHandler` stays the one writer of the problem response: `BookmarksController` throws
   `BadRequestException` for `InvalidUrl` and `OverLimit`, `PreferencesController` throws
   `ApiProblem(SIGN_IN_REQUIRED)` for `NoSuchUser`.
5. **A refusal after a write is a `Refused` record, and it runs in a unit of work.** `Refused` is a marker interface
   of the kernel (`ai.helper2.shared`). `TransactionalUnitOfWork.run` looks at the result of the work: when it is
   `Refused` and this unit opened the transaction, the transaction is rolled back and the result is returned. A unit
   that joined a transaction never marks it: the unit that opened it decides. An exception rolls the transaction back and is thrown again.
   `AddBookmark` and `MergeLocalBookmarks` check the address first, with no transaction, then lock the account, insert
   and count inside `UnitOfWork.run`; `OverLimit` is `Refused`, so nothing of a refused addition or merge is kept.
   `UnitOfWorkConfiguration` makes the one `UnitOfWork` bean in both apps: the bookmarks are a use case of the public
   app.
6. **A bound the contract states is not checked again.** The contract gives `page` a minimum of 0 and `size` 1 to 100
   on `GET /api/v1/admin/audit`, the generated interface carries them as constraints, and Spring answers 400 before
   the controller runs. `ListAuditEntries` reads the page it is asked for.
7. **The rule is a test, and its list only gets shorter.** `ModularityRules.useCasesDoNotSpeakHttp` runs in the
   `ModularityTest` of each app ([0002](0002-modules-modular-monolith-hexagonal-where-the-rules-live.md) §8). It
   skips the classes named in `STILL_THROWING`, with their nested classes: five of `catalog.usecase`
   (`AddCuratedResource`, `ChangeRelationshipTarget`, `CurateField`, `ListReviewItems`, `ResolveReviewItem`) and seven
   of `admin.usecase` (`ChangeRuntimeSetting`, `DecideRelationshipItem`, `DecideRelationshipTargetGroup`,
   `KnownTargetAnswer`, `ListEnrichmentQueue`, `ResolveReview`, `ResolveReviewItems`). A second test,
   `everyClassInStillThrowingStillSpeaksHttp`, fails when a named class no longer depends on one of the HTTP types:
   the name must then leave the list. A named class that an app does not have (the public app has no `admin`) is not
   checked there. No class outside the list can start to throw.
8. **The response does not depend on where the rule lives.** Status, problem `type`, `code`, `title` and `detail` of
   every identity endpoint are the same with an outcome as with a throw; the API tests of the account, the bookmarks,
   the preferences and the audit trail pin them.

### Code

- `backend/libs/kernel/src/main/kotlin/ai/helper2/shared/Refused.kt`, `UnitOfWork.kt` — the marker and the port.
- `backend/libs/platform/src/main/java/ai/helper2/platform/TransactionalUnitOfWork.java`,
  `UnitOfWorkConfiguration.java` — the rollback rule and the bean of both apps.
- `backend/libs/core/src/main/java/ai/helper2/identity/usecase/` (`AddBookmark`, `MergeLocalBookmarks`,
  `BookmarkRules`, `ChangePreferences`, `ListAuditEntries`) — the outcomes;
  `.../identity/config/IdentityUseCaseConfiguration.java` — the wiring; `.../identity/internal/SignInAgain.java`.
- `backend/apps/public/src/main/java/ai/helper2/identity/web/BookmarksController.java`, `PreferencesController.java`,
  `backend/apps/backoffice/src/main/java/ai/helper2/identity/web/AuditController.java` — the mapping.
- `backend/libs/platform/src/testFixtures/java/ai/helper2/testing/architecture/ModularityRules.java` — the rule and
  `STILL_THROWING`.
- Tests: `TransactionalUnitOfWorkTest` (rollback for a `Refused` result, a joined transaction is not marked),
  `BookmarkOutcomesTest`, `ChangePreferencesTest`, `PreferencesControllerTest`, `BookmarkCapIT` (the outcome and the
  rows after a refusal, two additions at once), `AccountApiIT`, `AdminAuditApiIT` (a page outside the bounds is a
  400).

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
  store is a generated file nobody reads; the named list shows the work that is left, and its second test asks for
  each removal. Rejected.
- **Keep the exceptions where the response is the same.** No change for a `BadRequestException` whose text is never
  shown. The rule would then have exceptions that last, and the next use case would copy them. Rejected.

## Consequences

- A use case is tested by its outcome, with no web class on the test's path.
- A new case of an outcome breaks the compile of its controller until it has an answer.
- A refusal after a write is visible in the type: the record implements `Refused`.
- A controller has a few more lines: the `switch` and the sentences.
- Both apps have a `UnitOfWork` bean.

### Known limits

- Twelve classes are in `STILL_THROWING`. Until they are converted, two wirings of the back office keep
  `@Transactional(noRollbackFor = ApiProblem.class)` (`AdminUseCaseConfiguration`).
- A `Refused` result inside a transaction this unit joined does not roll back: the caller that opened the
  transaction must look at the outcome. `TransactionalUnitOfWorkTest` holds both halves of the rule.
- `Refused` undoes the whole unit. A use case that must keep one write and undo another needs two units.
- The second test does not see a name of a class no app has: a renamed class leaves a dead name in the list. The
  back office has every class of the list, so the name fails there only when the class exists and is clean.
- The rule reads classes. A constant of an HTTP type is copied by the compiler and leaves no dependency; `ProblemType`
  is an enum, so its values are not copied.
- `CurrentUser.id()` still throws `SignInAgain`, from the adapter: `KnownAccountOnly` answers it before any controller
  runs.

## Diagrams

[flow](../../diagrams/flow-0051-use-cases-return-outcomes.svg) · [sequence](../../diagrams/sequence-0051-use-cases-return-outcomes.svg)

## Backlog

- Features: [F1.11](https://github.com/silvioincalza/helper2.ai/issues/30) (use cases return an outcome, and the web
  layer maps it to a problem, [S1.11.22](https://github.com/silvioincalza/helper2.ai/issues/726)).
- Open issues that change this decision:
  - [#740](https://github.com/silvioincalza/helper2.ai/issues/740) T1.11.1 The `catalog` use cases return outcomes
  - [#742](https://github.com/silvioincalza/helper2.ai/issues/742) T1.11.2 The `admin` use cases return outcomes, and no transaction needs `noRollbackFor`
