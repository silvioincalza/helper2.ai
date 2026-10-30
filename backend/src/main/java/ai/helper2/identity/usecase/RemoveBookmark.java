package ai.helper2.identity.usecase;

import ai.helper2.identity.internal.Bookmarks;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** A signed-in user removes one saved resource. Removing one that is not saved changes nothing. */
@Service
public class RemoveBookmark {

    private final Bookmarks bookmarks;

    public RemoveBookmark(Bookmarks bookmarks) {
        this.bookmarks = bookmarks;
    }

    public void remove(UUID userId, UUID resourceId) {
        bookmarks.remove(userId, resourceId);
    }
}
