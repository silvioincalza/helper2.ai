package ai.helper2.identity.usecase;

import ai.helper2.identity.Bookmarks;
import ai.helper2.identity.Bookmarks.Bookmark;
import java.util.List;
import java.util.UUID;

public class ListBookmarks {

  private final Bookmarks bookmarks;

  public ListBookmarks(Bookmarks bookmarks) {
    this.bookmarks = bookmarks;
  }

  public List<Bookmark> list(UUID userId) {
    return bookmarks.list(userId);
  }
}
