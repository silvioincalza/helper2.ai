package ai.helper2.identity.usecase;

import ai.helper2.identity.Bookmarks;
import ai.helper2.identity.Bookmarks.Bookmark;
import java.util.List;
import java.util.UUID;

public class MergeLocalBookmarks {

  private final Bookmarks bookmarks;

  public MergeLocalBookmarks(Bookmarks bookmarks) {
    this.bookmarks = bookmarks;
  }

  public List<Bookmark> merge(UUID userId, List<Bookmark> local) {
    local.forEach(b -> BookmarkRules.requireWebUrl(b.url()));
    bookmarks.lockForChange(userId);
    bookmarks.addMissing(userId, local);
    BookmarkRules.requireWithinCap(bookmarks.count(userId));
    return bookmarks.list(userId);
  }
}
