package ai.searchavo.identity.usecase;

import ai.searchavo.identity.Bookmarks;
import ai.searchavo.identity.Bookmarks.Bookmark;
import ai.searchavo.shared.Refused;
import ai.searchavo.shared.UnitOfWork;
import java.util.List;
import java.util.UUID;

public class MergeLocalBookmarks {

  public sealed interface Outcome {
    record Merged(List<Bookmark> bookmarks) implements Outcome {}

    record InvalidUrl() implements Outcome {}

    record OverLimit(int limit) implements Outcome, Refused {}
  }

  private final Bookmarks bookmarks;
  private final UnitOfWork unitOfWork;

  public MergeLocalBookmarks(Bookmarks bookmarks, UnitOfWork unitOfWork) {
    this.bookmarks = bookmarks;
    this.unitOfWork = unitOfWork;
  }

  public Outcome merge(UUID userId, List<Bookmark> local) {
    if (!local.stream().map(Bookmark::url).allMatch(BookmarkRules::isWebUrl)) {
      return new Outcome.InvalidUrl();
    }
    return unitOfWork.run(() -> mergeWithinTheCap(userId, local));
  }

  private Outcome mergeWithinTheCap(UUID userId, List<Bookmark> local) {
    bookmarks.lockForChange(userId);
    bookmarks.addMissing(userId, local);
    if (BookmarkRules.isOverTheCap(bookmarks.count(userId))) {
      return new Outcome.OverLimit(BookmarkRules.MAX_PER_USER);
    }
    return new Outcome.Merged(bookmarks.list(userId));
  }
}
