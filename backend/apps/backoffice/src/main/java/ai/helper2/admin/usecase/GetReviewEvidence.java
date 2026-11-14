package ai.helper2.admin.usecase;

import ai.helper2.catalog.ReviewEvidence;
import ai.helper2.catalog.ReviewQueue;
import java.util.Optional;
import java.util.UUID;

public class GetReviewEvidence {
  public record View(ReviewQueue.Item item, ReviewEvidence.Evidence evidence) {}

  private final ReviewQueue queue;
  private final ReviewEvidence evidence;

  public GetReviewEvidence(ReviewQueue queue, ReviewEvidence evidence) {
    this.queue = queue;
    this.evidence = evidence;
  }

  public Optional<View> get(UUID itemId) {
    return queue.find(itemId).map(item -> new View(item, evidence.of(itemId)));
  }
}
