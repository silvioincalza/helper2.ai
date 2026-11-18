package ai.helper2.admin.usecase;

import ai.helper2.catalog.ReviewEvidence;
import ai.helper2.catalog.ReviewQueue;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

public class GetReviewEvidence {
  public record View(
      ReviewItemView item,
      ReviewEvidence.@Nullable Run run,
      List<String> validationErrors,
      List<ReviewEvidence.Field> fields,
      ReviewEvidence.@Nullable Relationship relationship,
      boolean aiWritten) {}

  private final ReviewQueue queue;
  private final ReviewEvidence evidence;
  private final ReviewItemViews views;

  public GetReviewEvidence(ReviewQueue queue, ReviewEvidence evidence, ReviewItemViews views) {
    this.queue = queue;
    this.evidence = evidence;
    this.views = views;
  }

  public Optional<View> get(UUID itemId) {
    return queue.find(itemId).map(item -> view(item, evidence.of(itemId)));
  }

  private View view(ReviewQueue.Item item, ReviewEvidence.Evidence e) {
    return new View(
        views.of(item), e.run(), e.validationErrors(), e.fields(), e.relationship(), e.aiWritten());
  }
}
