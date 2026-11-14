package ai.helper2.catalog.usecase;

import ai.helper2.catalog.ReviewQueue;
import ai.helper2.platform.BadRequestException;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

public class ListReviewItems {

  private final ReviewQueue queue;

  public ListReviewItems(ReviewQueue queue) {
    this.queue = queue;
  }

  public static final int MAX_PAGE_SIZE = 100;

  private static final Pattern CATEGORY = Pattern.compile("[a-z0-9]+(-[a-z0-9]+)*");

  public ReviewQueue.Page list(
      ReviewQueue.Status status, ReviewQueue.Kind kind, int page, int size) {
    return list(status, kind, null, page, size);
  }

  public ReviewQueue.Page list(
      ReviewQueue.Status status,
      ReviewQueue.Kind kind,
      @Nullable String category,
      int page,
      int size) {
    if (page < 0 || size < 1 || size > MAX_PAGE_SIZE) {
      throw new BadRequestException(
          "page must be 0 or more and size between 1 and " + MAX_PAGE_SIZE);
    }
    if (category != null && (category.length() > 40 || !CATEGORY.matcher(category).matches())) {
      throw new BadRequestException("category must be lower-case words with hyphens");
    }
    return queue.list(status, kind, category, page, size);
  }
}
