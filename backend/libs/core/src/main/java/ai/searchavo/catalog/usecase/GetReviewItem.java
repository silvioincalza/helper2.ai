package ai.searchavo.catalog.usecase;

import ai.searchavo.catalog.ReviewQueue;
import java.util.Optional;
import java.util.UUID;

public class GetReviewItem {

  private final ReviewQueue queue;

  public GetReviewItem(ReviewQueue queue) {
    this.queue = queue;
  }

  public Optional<ReviewQueue.Item> get(UUID id) {
    return queue.find(id);
  }
}
