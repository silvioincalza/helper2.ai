package ai.helper2.identity.usecase;

import ai.helper2.identity.Bookmarks;
import ai.helper2.identity.Bookmarks.Bookmark;
import ai.helper2.shared.Refused;
import ai.helper2.shared.UnitOfWork;
import java.util.List;
import java.util.UUID;

public class AddBookmark {

  public sealed interface Outcome {
    record Added(Bookmark bookmark) implements Outcome {}

    record InvalidUrl() implements Outcome {}

    record OverLimit(int limit) implements Outcome, Refused {}
  }

  private final Bookmarks bookmarks;
  private final UnitOfWork unitOfWork;

  public AddBookmark(Bookmarks bookmarks, UnitOfWork unitOfWork) {
    this.bookmarks = bookmarks;
    this.unitOfWork = unitOfWork;
  }

  public Outcome add(UUID userId, Bookmark bookmark) {
    if (!BookmarkRules.isWebUrl(bookmark.url())) {
      return new Outcome.InvalidUrl();
    }
    return unitOfWork.run(() -> addWithinTheCap(userId, bookmark));
  }

  private Outcome addWithinTheCap(UUID userId, Bookmark bookmark) {
    bookmarks.lockForChange(userId);
    bookmarks.addMissing(userId, List.of(bookmark));
    if (BookmarkRules.isOverTheCap(bookmarks.count(userId))) {
      return new Outcome.OverLimit(BookmarkRules.MAX_PER_USER);
    }
    return new Outcome.Added(saved(userId, bookmark.resourceId()));
  }

  private Bookmark saved(UUID userId, UUID resourceId) {
    return bookmarks.list(userId).stream()
        .filter(b -> b.resourceId().equals(resourceId))
        .findFirst()
        .orElseThrow();
  }
}
