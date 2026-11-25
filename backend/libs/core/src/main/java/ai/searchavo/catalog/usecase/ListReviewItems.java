package ai.searchavo.catalog.usecase;

import ai.searchavo.catalog.ReviewQueue;
import org.jspecify.annotations.Nullable;

public class ListReviewItems {

  private final ReviewQueue queue;

  public ListReviewItems(ReviewQueue queue) {
    this.queue = queue;
  }

  public ReviewQueue.Page list(
      ReviewQueue.Status status,
      ReviewQueue.Kind kind,
      @Nullable String category,
      ReviewQueue.@Nullable Only only,
      int page,
      int size) {
    return queue.list(status, kind, category, only, page, size);
  }
}
