package ai.helper2.identity.usecase;

import ai.helper2.identity.internal.Bookmarks;
import ai.helper2.identity.internal.Bookmarks.Bookmark;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Right after sign-in, the bookmarks kept in the browser join the account's. Idempotent: a resource already saved
 * keeps its first savedAt. All or nothing: one invalid URL, or more than the cap in total, refuses the whole list.
 */
@Service
public class MergeLocalBookmarks {

    private final Bookmarks bookmarks;

    public MergeLocalBookmarks(Bookmarks bookmarks) {
        this.bookmarks = bookmarks;
    }

    /** Returns the account's full list after the merge, newest first. */
    @Transactional
    public List<Bookmark> merge(UUID userId, List<Bookmark> local) {
        local.forEach(b -> BookmarkRules.requireWebUrl(b.url()));
        bookmarks.addMissing(userId, local);
        BookmarkRules.requireWithinCap(bookmarks.count(userId));
        return bookmarks.list(userId);
    }
}
