package ai.helper2.admin.usecase;

import ai.helper2.catalog.KnownTargetCandidates;
import ai.helper2.catalog.ReviewQueue;
import ai.helper2.examples.WaitingDraft;
import ai.helper2.examples.usecase.ReadExampleDraft;
import ai.helper2.scout.usecase.ShownCandidate;
import ai.helper2.scout.usecase.ShownCandidates;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

public class ReviewItemViews {

  private final ReadExampleDraft drafts;
  private final ShownCandidates candidates;
  private final KnownTargetCandidates proposals;

  public ReviewItemViews(
      ReadExampleDraft drafts, ShownCandidates candidates, KnownTargetCandidates proposals) {
    this.drafts = drafts;
    this.candidates = candidates;
    this.proposals = proposals;
  }

  public ReviewItemView of(ReviewQueue.Item item) {
    WaitingDraft draft = drafts.in(item).orElse(null);
    return new ReviewItemView(
        item.id(),
        item.kind(),
        ReviewItemView.Status.of(item.status()),
        item.resourceId(),
        item.resourceTitle(),
        item.reason(),
        draft == null ? details(item) : null,
        draft,
        item.createdAt(),
        item.resolvedAt(),
        item.resolvedBy(),
        item.categories());
  }

  private @Nullable ReviewDetails details(ReviewQueue.Item item) {
    return shaped(item).orElseGet(() -> text(item.details()));
  }

  private Optional<ReviewDetails> shaped(ReviewQueue.Item item) {
    return switch (item.kind()) {
      case RESOURCE_CANDIDATE, COURSE_CANDIDATE ->
          Optional.ofNullable(candidates.shown(item.details())).map(ReviewItemViews::candidate);
      case KNOWN_TARGET_CANDIDATE -> proposals.shown(item.details()).map(ReviewItemViews::proposal);
      case ENRICHMENT_VALIDATION, RELATIONSHIP_CANDIDATE, USER_REPORT, EXAMPLE_CANDIDATE ->
          Optional.empty();
    };
  }

  private static ReviewDetails.@Nullable Text text(@Nullable String stored) {
    return stored == null || stored.isEmpty() ? null : new ReviewDetails.Text(stored);
  }

  private static ReviewDetails candidate(ShownCandidate c) {
    return new ReviewDetails.Candidate(
        c.getRevision(),
        c.getUrl(),
        c.getTitle(),
        c.getType(),
        c.getTags(),
        c.getSnippet(),
        c.getFoundBy(),
        c.getEdited());
  }

  private static ReviewDetails proposal(KnownTargetCandidates.Proposal p) {
    return new ReviewDetails.KnownTargetProposal(
        p.name(), p.kind(), p.rule(), p.count(), p.examples(), p.otherNames(), p.edited());
  }
}
