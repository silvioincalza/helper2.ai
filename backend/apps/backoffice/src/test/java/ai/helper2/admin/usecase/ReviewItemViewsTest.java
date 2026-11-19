package ai.helper2.admin.usecase;

import static org.assertj.core.api.Assertions.assertThat;

import ai.helper2.catalog.KnownTarget;
import ai.helper2.catalog.KnownTargetCandidates;
import ai.helper2.catalog.ReviewQueue;
import ai.helper2.examples.DraftQueue;
import ai.helper2.examples.ExampleDraft;
import ai.helper2.examples.WaitingDraft;
import ai.helper2.examples.usecase.ReadExampleDraft;
import ai.helper2.scout.usecase.ShownCandidate;
import ai.helper2.shared.AudiencePath;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

class ReviewItemViewsTest {

  static final String READABLE = "readable";

  static final ShownCandidate CANDIDATE =
      new ShownCandidate(
          "r1",
          "https://x.ai",
          "X",
          "PRODUCT",
          List.of("coding"),
          "An app.",
          List.of("discovery-coding"),
          List.of("title"));

  static final KnownTargetCandidates.Proposal PROPOSAL =
      new KnownTargetCandidates.Proposal(
          "Acme", KnownTarget.Kind.VENDOR, "OWNER", 6, List.of("acme/agent"), List.of(), false);

  static final WaitingDraft DRAFT =
      new WaitingDraft("Label", "a query", AudiencePath.BUILD_WITH_AI, "nemotron-test");

  final ReviewItemViews views =
      new ReviewItemViews(
          new ReadExampleDraft(new Drafts()),
          details -> READABLE.equals(details) ? CANDIDATE : null,
          new Proposals());

  private static ReviewQueue.Item item(ReviewQueue.Kind kind, @Nullable String details) {
    return new ReviewQueue.Item(
        UUID.randomUUID(),
        kind,
        ReviewQueue.Status.OPEN,
        null,
        null,
        "reason",
        details,
        Instant.parse("2026-11-18T03:00:00Z"),
        null,
        null,
        List.of());
  }

  @Test
  void aCandidateOfEitherKindHasTheCandidateShape() {
    for (ReviewQueue.Kind kind :
        List.of(ReviewQueue.Kind.RESOURCE_CANDIDATE, ReviewQueue.Kind.COURSE_CANDIDATE)) {
      assertThat(views.of(item(kind, READABLE)).details())
          .isEqualTo(
              new ReviewDetails.Candidate(
                  "r1",
                  "https://x.ai",
                  "X",
                  "PRODUCT",
                  List.of("coding"),
                  "An app.",
                  List.of("discovery-coding"),
                  List.of("title")));
    }
  }

  @Test
  void aKnownTargetCandidateHasTheProposalShape() {
    assertThat(views.of(item(ReviewQueue.Kind.KNOWN_TARGET_CANDIDATE, READABLE)).details())
        .isEqualTo(
            new ReviewDetails.KnownTargetProposal(
                "Acme",
                KnownTarget.Kind.VENDOR,
                "OWNER",
                6,
                List.of("acme/agent"),
                List.of(),
                false));
  }

  @Test
  void theKindsWithNoShapeOfTheirOwnShowTheirTextAndOnlyThat() {
    for (ReviewQueue.Kind kind :
        List.of(
            ReviewQueue.Kind.ENRICHMENT_VALIDATION,
            ReviewQueue.Kind.RELATIONSHIP_CANDIDATE,
            ReviewQueue.Kind.USER_REPORT)) {
      assertThat(views.of(item(kind, READABLE)).details())
          .isEqualTo(new ReviewDetails.Text(READABLE));
    }
  }

  @Test
  void aStoredValueThatDoesNotReadAsItsShapeIsShownAsItIsStored() {
    for (ReviewQueue.Kind kind :
        List.of(
            ReviewQueue.Kind.RESOURCE_CANDIDATE,
            ReviewQueue.Kind.COURSE_CANDIDATE,
            ReviewQueue.Kind.KNOWN_TARGET_CANDIDATE,
            ReviewQueue.Kind.EXAMPLE_CANDIDATE)) {
      ReviewItemView view = views.of(item(kind, "{\"unknown\":1}"));
      assertThat(view.details())
          .as(kind.name())
          .isEqualTo(new ReviewDetails.Text("{\"unknown\":1}"));
      assertThat(view.draft()).isNull();
    }
  }

  @Test
  void anItemThatHoldsNothingHasNoDetails() {
    for (ReviewQueue.Kind kind : ReviewQueue.Kind.values()) {
      assertThat(views.of(item(kind, null)).details()).as(kind.name()).isNull();
      assertThat(views.of(item(kind, "")).details()).as(kind.name()).isNull();
    }
  }

  @Test
  void aReadableDraftIsTheDraftOfTheItemAndNotItsDetails() {
    ReviewItemView view = views.of(item(ReviewQueue.Kind.EXAMPLE_CANDIDATE, READABLE));

    assertThat(view.draft()).isEqualTo(DRAFT);
    assertThat(view.details()).isNull();
  }

  @Test
  void theViewKeepsTheStatusAndTheRestOfTheItem() {
    ReviewQueue.Item decided =
        new ReviewQueue.Item(
            UUID.randomUUID(),
            ReviewQueue.Kind.USER_REPORT,
            ReviewQueue.Status.REJECTED,
            UUID.randomUUID(),
            "Open WebUI",
            "Wrong license",
            null,
            Instant.parse("2026-11-17T03:00:00Z"),
            Instant.parse("2026-11-18T03:00:00Z"),
            "42",
            List.of("coding"));

    assertThat(views.of(decided))
        .isEqualTo(
            new ReviewItemView(
                decided.id(),
                ReviewQueue.Kind.USER_REPORT,
                ReviewItemView.Status.REJECTED,
                decided.resourceId(),
                "Open WebUI",
                "Wrong license",
                null,
                null,
                decided.createdAt(),
                decided.resolvedAt(),
                "42",
                List.of("coding")));
  }

  static class Drafts implements DraftQueue {
    @Override
    public void open(ExampleDraft draft) {}

    @Override
    public Set<UUID> waitingResources() {
      return Set.of();
    }

    @Override
    public List<WaitingDraft> waiting() {
      return List.of();
    }

    @Override
    public @Nullable WaitingDraft read(@Nullable String details) {
      return READABLE.equals(details) ? DRAFT : null;
    }
  }

  static class Proposals implements KnownTargetCandidates {
    @Override
    public Proposed proposeOwners(Collection<String> sourceIds, int minResources, int perDay) {
      return new Proposed(0, 0);
    }

    @Override
    public Optional<Candidate> of(UUID reviewItemId) {
      return Optional.empty();
    }

    @Override
    public boolean change(UUID reviewItemId, KnownTarget.Kind kind, List<String> names) {
      return false;
    }

    @Override
    public Optional<Proposal> shown(@Nullable String details) {
      return READABLE.equals(details) ? Optional.of(PROPOSAL) : Optional.empty();
    }
  }
}
