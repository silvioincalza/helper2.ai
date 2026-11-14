package ai.helper2.identity.usecase;

import ai.helper2.identity.Bookmarks;
import ai.helper2.identity.Bookmarks.Bookmark;
import java.util.List;
import java.util.UUID;

public class AddBookmark {

  private final Bookmarks bookmarks;

  public AddBookmark(Bookmarks bookmarks) {
    this.bookmarks = bookmarks;
  }

  public Bookmark add(UUID userId, Bookmark bookmark) {
    BookmarkRules.requireWebUrl(bookmark.url());
    bookmarks.lockForChange(userId);
    bookmarks.addMissing(userId, List.of(bookmark));
    BookmarkRules.requireWithinCap(bookmarks.count(userId));
    return bookmarks.list(userId).stream()
        .filter(b -> b.resourceId().equals(bookmark.resourceId()))
        .findFirst()
        .orElseThrow();
  }
}
