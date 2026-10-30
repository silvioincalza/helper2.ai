package ai.helper2.identity.usecase;

import ai.helper2.identity.internal.Bookmarks;
import ai.helper2.identity.internal.Bookmarks.Bookmark;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** A signed-in user saves one resource. Saving it again changes nothing (the first savedAt stays). */
@Service
public class AddBookmark {

    private final Bookmarks bookmarks;

    public AddBookmark(Bookmarks bookmarks) {
        this.bookmarks = bookmarks;
    }

    /** Returns the saved bookmark; 400 when the URL is not a web page or the account already holds the most. */
    @Transactional
    public Bookmark add(UUID userId, Bookmark bookmark) {
        BookmarkRules.requireWebUrl(bookmark.url());
        bookmarks.addMissing(userId, List.of(bookmark));
        BookmarkRules.requireWithinCap(bookmarks.count(userId));
        return bookmarks.list(userId).stream().filter(b -> b.resourceId().equals(bookmark.resourceId())).findFirst().orElseThrow();
    }
}
