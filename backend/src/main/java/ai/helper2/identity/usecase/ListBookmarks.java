package ai.helper2.identity.usecase;

import ai.helper2.identity.internal.Bookmarks;
import ai.helper2.identity.internal.Bookmarks.Bookmark;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** A signed-in user's saved resources, newest first. */
@Service
public class ListBookmarks {

    private final Bookmarks bookmarks;

    public ListBookmarks(Bookmarks bookmarks) {
        this.bookmarks = bookmarks;
    }

    public List<Bookmark> list(UUID userId) {
        return bookmarks.list(userId);
    }
}
