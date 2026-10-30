package ai.helper2.identity.usecase;

import ai.helper2.platform.BadRequestException;
import java.net.URI;

/** What a bookmark must be, shared by AddBookmark and MergeLocalBookmarks. */
final class BookmarkRules {

    /** Bookmarks one account may keep; a request past it is refused, nothing is silently dropped. */
    static final int MAX_PER_USER = 500;

    static final int MAX_URL_LENGTH = 2000;

    private BookmarkRules() {
    }

    /** A bookmark links to a web page: http or https, at most {@link #MAX_URL_LENGTH} characters. */
    static void requireWebUrl(URI url) {
        String scheme = url.getScheme();
        if (url.toString().length() > MAX_URL_LENGTH || scheme == null || !(scheme.equals("https") || scheme.equals("http"))) {
            throw new BadRequestException("invalid bookmark url");
        }
    }

    static void requireWithinCap(long total) {
        if (total > MAX_PER_USER) {
            throw new BadRequestException("At most " + MAX_PER_USER + " bookmarks per account.");
        }
    }
}
