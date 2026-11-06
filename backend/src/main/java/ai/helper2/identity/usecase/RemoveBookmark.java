package ai.helper2.identity.usecase;

import ai.helper2.identity.Bookmarks;
import java.util.UUID;

/** A signed-in user removes one saved resource. Removing one that is not saved changes nothing. */
public class RemoveBookmark {

  private final Bookmarks bookmarks;

  public RemoveBookmark(Bookmarks bookmarks) {
    this.bookmarks = bookmarks;
  }

  public void remove(UUID userId, UUID resourceId) {
    bookmarks.remove(userId, resourceId);
  }
}
