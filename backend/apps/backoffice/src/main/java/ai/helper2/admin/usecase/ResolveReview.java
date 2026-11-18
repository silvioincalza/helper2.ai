package ai.helper2.admin.usecase;

import ai.helper2.catalog.KnownTargetCandidates;
import ai.helper2.catalog.ReviewQueue;
import ai.helper2.catalog.usecase.CurateField;
import ai.helper2.catalog.usecase.ManageKnownTargets;
import ai.helper2.catalog.usecase.ResolveReviewItem;
import ai.helper2.examples.usecase.AcceptExampleDraft;
import ai.helper2.scout.usecase.AcceptOutcome;
import ai.helper2.scout.usecase.IndexAcceptedCandidate;
import ai.helper2.scout.usecase.InvalidCandidate;
import ai.helper2.scout.usecase.ListedResource;
import ai.helper2.shared.Refused;
import ai.helper2.shared.UnitOfWork;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

public class ResolveReview {

  public sealed interface Outcome {
    record Resolved(ReviewQueue.Item item, @Nullable ListedResource alreadyListed)
        implements Outcome {}

    record NotOpen(ReviewQueue.Item item) implements Outcome {}

    record NotFound() implements Outcome {}

    sealed interface NotAdded extends Outcome {}

    record UnreadableCandidate() implements NotAdded {}

    record ConfirmedValueRefused(String field, CurateField.Outcome.ValueRefused why)
        implements NotAdded, Refused {}

    record UnreadableProposal() implements NotAdded {}

    record InvalidKnownTargetName() implements NotAdded {}

    record KnownTargetNameTaken(String name, String holder) implements NotAdded {}

    record KnownTargetListFull(int limit) implements NotAdded {}

    record UnreadableDraft() implements NotAdded {}

    record DuplicateExample() implements NotAdded {}

    record InvalidExample(List<String> problems) implements NotAdded {}
  }

  private sealed interface Addition {
    record Done(@Nullable ListedResource alreadyListed) implements Addition {}

    record Stopped(Outcome.NotAdded why) implements Addition {}
  }

  private static final Addition ADDED = new Addition.Done(null);

  private final ReviewQueue queue;
  private final ResolveReviewItem resolve;
  private final AcceptExampleDraft acceptDraft;
  private final IndexAcceptedCandidate indexCandidate;
  private final KnownTargetCandidates targetCandidates;
  private final ManageKnownTargets knownTargets;
  private final CurateField curate;
  private final UnitOfWork unitOfWork;

  public ResolveReview(
      ReviewQueue queue,
      ResolveReviewItem resolve,
      AcceptExampleDraft acceptDraft,
      IndexAcceptedCandidate indexCandidate,
      KnownTargetCandidates targetCandidates,
      ManageKnownTargets knownTargets,
      CurateField curate,
      UnitOfWork unitOfWork) {
    this.curate = curate;
    this.targetCandidates = targetCandidates;
    this.knownTargets = knownTargets;
    this.queue = queue;
    this.resolve = resolve;
    this.acceptDraft = acceptDraft;
    this.indexCandidate = indexCandidate;
    this.unitOfWork = unitOfWork;
  }

  public Outcome resolve(
      UUID id,
      ReviewQueue.Status decision,
      String adminId,
      @Nullable String note,
      @Nullable UUID batch) {
    return unitOfWork.run(() -> decide(id, decision, adminId, note, batch));
  }

  private Outcome decide(
      UUID id,
      ReviewQueue.Status decision,
      String adminId,
      @Nullable String note,
      @Nullable UUID batch) {
    if (decision == ReviewQueue.Status.ACCEPTED) {
      queue.lock(id);
    }
    Optional<ReviewQueue.Item> addition =
        queue
            .find(id)
            .filter(i -> i.status() == ReviewQueue.Status.OPEN)
            .filter(i -> decision == ReviewQueue.Status.ACCEPTED)
            .filter(ResolveReview::addsOnAccept);
    if (addition.isEmpty()) {
      return resolved(id, decision, adminId, note, batch, null);
    }
    return switch (addAccepted(addition.get(), adminId)) {
      case Addition.Stopped stopped -> stopped.why();
      case Addition.Done done ->
          resolvedAfterAdding(id, decision, adminId, note, batch, done.alreadyListed());
    };
  }

  private Outcome resolvedAfterAdding(
      UUID id,
      ReviewQueue.Status decision,
      String adminId,
      @Nullable String note,
      @Nullable UUID batch,
      @Nullable ListedResource listed) {
    Outcome outcome = resolved(id, decision, adminId, note, batch, listed);
    if (!(outcome instanceof Outcome.Resolved)) {
      throw new IllegalStateException("review item " + id + " was decided concurrently");
    }
    return outcome;
  }

  private Outcome resolved(
      UUID id,
      ReviewQueue.Status decision,
      String adminId,
      @Nullable String note,
      @Nullable UUID batch,
      @Nullable ListedResource listed) {
    return switch (resolve.resolve(id, decision, adminId, noteWith(note, listed), batch)) {
      case ResolveReviewItem.Outcome.Resolved r -> new Outcome.Resolved(r.item(), listed);
      case ResolveReviewItem.Outcome.NotOpen n -> new Outcome.NotOpen(n.item());
      case ResolveReviewItem.Outcome.NotFound f -> new Outcome.NotFound();
    };
  }

  private static @Nullable String noteWith(@Nullable String note, @Nullable ListedResource listed) {
    if (listed == null) {
      return note;
    }
    String already = "Already listed as " + listed.getTitle() + ".";
    return note == null || note.isBlank() ? already : note + " " + already;
  }

  private static boolean addsOnAccept(ReviewQueue.Item item) {
    return switch (item.kind()) {
      case EXAMPLE_CANDIDATE, RESOURCE_CANDIDATE, KNOWN_TARGET_CANDIDATE -> true;
      case COURSE_CANDIDATE -> structuredCourse(item);
      case ENRICHMENT_VALIDATION, RELATIONSHIP_CANDIDATE, USER_REPORT -> false;
    };
  }

  private static boolean structuredCourse(ReviewQueue.Item item) {
    return item.details() != null && item.details().strip().startsWith("{");
  }

  private Addition addAccepted(ReviewQueue.Item item, String adminId) {
    return switch (item.kind()) {
      case RESOURCE_CANDIDATE, COURSE_CANDIDATE -> indexCandidate(item, adminId);
      case KNOWN_TARGET_CANDIDATE -> addKnownTarget(item, adminId);
      case EXAMPLE_CANDIDATE -> addDrafted(item, adminId);
      case ENRICHMENT_VALIDATION, RELATIONSHIP_CANDIDATE, USER_REPORT ->
          throw new IllegalStateException("accepting a " + item.kind() + " adds nothing");
    };
  }

  private Addition indexCandidate(ReviewQueue.Item item, String adminId) {
    AcceptOutcome outcome;
    try {
      outcome = indexCandidate.accept(item.details());
    } catch (InvalidCandidate e) {
      return new Addition.Stopped(new Outcome.UnreadableCandidate());
    }
    if (outcome instanceof AcceptOutcome.Indexed indexed) {
      return applyConfirmed(indexed, adminId);
    }
    return new Addition.Done(
        outcome instanceof AcceptOutcome.AlreadyListed listed ? listed.getResource() : null);
  }

  private Addition applyConfirmed(AcceptOutcome.Indexed indexed, String adminId) {
    for (Map.Entry<String, String> confirmed : indexed.getConfirmed().entrySet()) {
      String field = confirmed.getKey();
      switch (confirm(indexed, field, confirmed.getValue(), adminId)) {
        case CurateField.Outcome.Curated curated -> {}
        case CurateField.Outcome.ValueRefused why -> {
          return new Addition.Stopped(new Outcome.ConfirmedValueRefused(field, why));
        }
        case CurateField.Outcome.NoSuchResource gone ->
            throw new IllegalStateException("the added resource is gone: " + indexed.getId());
      }
    }
    return ADDED;
  }

  private CurateField.Outcome confirm(
      AcceptOutcome.Indexed indexed, String field, String value, String adminId) {
    return switch (field) {
      case "description" ->
          curate.setDescription(indexed.getId(), value, indexed.getDescriptionByAi(), adminId);
      case "publishedAt" -> curate.setPublished(indexed.getId(), LocalDate.parse(value), adminId);
      default -> curate.set(indexed.getId(), field, value, adminId);
    };
  }

  private Addition addKnownTarget(ReviewQueue.Item item, String adminId) {
    Optional<KnownTargetCandidates.Candidate> proposal = targetCandidates.of(item.id());
    if (proposal.isEmpty()) {
      return new Addition.Stopped(new Outcome.UnreadableProposal());
    }
    KnownTargetCandidates.Candidate candidate = proposal.get();
    return switch (knownTargets.addDiscovered(
        adminId, candidate.kind(), candidate.name(), candidate.otherNames())) {
      case ManageKnownTargets.Saved.Done d -> ADDED;
      case ManageKnownTargets.Saved.Taken t ->
          new Addition.Stopped(new Outcome.KnownTargetNameTaken(t.name(), t.holder()));
      case ManageKnownTargets.Saved.Full f ->
          new Addition.Stopped(new Outcome.KnownTargetListFull(ManageKnownTargets.MAX_TARGETS));
      case ManageKnownTargets.Saved.Invalid i ->
          new Addition.Stopped(new Outcome.InvalidKnownTargetName());
      case ManageKnownTargets.Saved.NotFound n ->
          throw new IllegalStateException("adding a known target cannot miss one");
    };
  }

  private Addition addDrafted(ReviewQueue.Item item, String adminId) {
    return switch (acceptDraft.accept(adminId, item)) {
      case AcceptExampleDraft.Outcome.Added a -> ADDED;
      case AcceptExampleDraft.Outcome.Unreadable u ->
          new Addition.Stopped(new Outcome.UnreadableDraft());
      case AcceptExampleDraft.Outcome.Duplicate d ->
          new Addition.Stopped(new Outcome.DuplicateExample());
      case AcceptExampleDraft.Outcome.Invalid i ->
          new Addition.Stopped(new Outcome.InvalidExample(i.problems()));
    };
  }
}
