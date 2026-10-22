package ai.helper2.catalog.usecase;

import ai.helper2.catalog.ReviewQueue;
import java.util.List;
import org.springframework.stereotype.Service;

/** The admin page's review items, newest first; all statuses when [status] is null. */
@Service
public class ListReviewItems {

    private final ReviewQueue queue;

    public ListReviewItems(ReviewQueue queue) {
        this.queue = queue;
    }

    public List<ReviewQueue.Item> list(ReviewQueue.Status status) {
        return queue.list(status);
    }
}
