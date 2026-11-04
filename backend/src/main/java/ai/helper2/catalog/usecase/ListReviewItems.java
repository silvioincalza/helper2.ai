package ai.helper2.catalog.usecase;

import ai.helper2.catalog.ReviewQueue;
import ai.helper2.platform.BadRequestException;
import org.springframework.stereotype.Service;

/**
 * One page of the admin page's review items, newest first; all statuses or kinds when [status] or [kind] is null.
 */
@Service
public class ListReviewItems {

    /**
     * The largest page the back office may ask for.
     */
    public static final int MAX_PAGE_SIZE = 100;
    private final ReviewQueue queue;

    public ListReviewItems(ReviewQueue queue) {
        this.queue = queue;
    }

    public ReviewQueue.Page list(ReviewQueue.Status status, ReviewQueue.Kind kind, int page, int size) {
        if (page < 0 || size < 1 || size > MAX_PAGE_SIZE) {
            throw new BadRequestException("page must be 0 or more and size between 1 and " + MAX_PAGE_SIZE);
        }
        return queue.list(status, kind, page, size);
    }
}
