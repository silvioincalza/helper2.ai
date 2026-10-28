package ai.helper2.scout.internal;

import ai.helper2.catalog.ReviewQueue;
import ai.helper2.scout.Candidate;
import ai.helper2.scout.CandidateReview;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** Collector proposals become COURSE_CANDIDATE review items: an admin decides them, nothing reaches the catalog. */
@Component
class ReviewQueueCandidateReview implements CandidateReview {

    private static final Logger log = LoggerFactory.getLogger(ReviewQueueCandidateReview.class);
    private static final int MAX_SNIPPET = 1000;

    private final ReviewQueue queue;

    ReviewQueueCandidateReview(ReviewQueue queue) {
        this.queue = queue;
    }

    @Override
    public int propose(String sourceId, List<Candidate> candidates) {
        int opened = 0;
        for (Candidate c : candidates) {
            String url = c.getUrl().getValue();
            String reason = "New course or certification page found by " + sourceId + ": " + c.getTitle();
            if (queue.proposeCandidate(ReviewQueue.Kind.COURSE_CANDIDATE, url, reason.length() > 300 ? reason.substring(0, 300) : reason,
                    details(c))) {
                opened++;
            }
        }
        if (opened > 0) {
            log.info("{}: {} of {} candidate pages sent to review", sourceId, opened, candidates.size());
        }
        return opened;
    }

    /** What an admin needs to decide: the page, its platform and the search snippet, one per line. */
    static String details(Candidate c) {
        StringBuilder b = new StringBuilder("URL: ").append(c.getUrl().getValue());
        if (c.getPlatform() != null) {
            b.append("\nPlatform: ").append(c.getPlatform());
        }
        if (c.getSnippet() != null && !c.getSnippet().isBlank()) {
            String s = c.getSnippet().strip();
            b.append("\nSnippet: ").append(s.length() > MAX_SNIPPET ? s.substring(0, MAX_SNIPPET - 1) + "…" : s);
        }
        return b.toString();
    }
}
