package ai.helper2.scout.internal;

import ai.helper2.catalog.ReviewQueue;
import ai.helper2.scout.Candidate;
import ai.helper2.scout.CandidateReview;
import ai.helper2.shared.ResourceType;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Collector proposals become review items: an admin decides them, nothing reaches the catalog
 * before. A COURSE page is a COURSE_CANDIDATE (ADR 0032 §8) with readable details. Any other type
 * is a RESOURCE_CANDIDATE (ADR 0051) whose details are the candidate as JSON ({@link
 * ResourceCandidateJson}), so accepting it can index it.
 */
@Component
class ReviewQueueCandidateReview implements CandidateReview {

  private static final Logger log = LoggerFactory.getLogger(ReviewQueueCandidateReview.class);
  private static final int MAX_SNIPPET = 1000;
  private static final int MAX_REASON = 300;

  private final ReviewQueue queue;
  private final ResourceCandidateJson json;

  ReviewQueueCandidateReview(ReviewQueue queue, ResourceCandidateJson json) {
    this.queue = queue;
    this.json = json;
  }

  @Override
  public int propose(String sourceId, List<Candidate> candidates) {
    int opened = 0;
    for (Candidate c : candidates) {
      if (open(sourceId, c)) {
        opened++;
      }
    }
    if (opened > 0) {
      log.info("{}: {} of {} candidate pages sent to review", sourceId, opened, candidates.size());
    }
    return opened;
  }

  private boolean open(String sourceId, Candidate c) {
    String url = c.getUrl().getValue();
    if (c.getType() == ResourceType.COURSE) {
      return queue.proposeCandidate(
          ReviewQueue.Kind.COURSE_CANDIDATE,
          url,
          capped("New course or certification page found by " + sourceId + ": " + c.getTitle()),
          details(c));
    }
    return queue.proposeCandidate(
        ReviewQueue.Kind.RESOURCE_CANDIDATE,
        url,
        capped(resourceReason(sourceId, c)),
        json.write(sourceId, c));
  }

  /** "New PRODUCT (local-ai, self-hosted) found by tavily-discovery: Open WebUI". */
  static String resourceReason(String sourceId, Candidate c) {
    String category = c.getPlatform() == null ? "" : " (" + c.getPlatform() + ")";
    return "New " + c.getType().name() + category + " found by " + sourceId + ": " + c.getTitle();
  }

  /** What an admin needs to decide: the page, its platform and the search snippet, one per line. */
  static String details(Candidate c) {
    StringBuilder b = new StringBuilder("URL: ").append(c.getUrl().getValue());
    if (c.getPlatform() != null) {
      b.append("\nPlatform: ").append(c.getPlatform());
    }
    String snippet = snippet(c);
    if (snippet != null) {
      b.append("\nSnippet: ").append(snippet);
    }
    return b.toString();
  }

  private static String snippet(Candidate c) {
    if (c.getSnippet() == null || c.getSnippet().isBlank()) {
      return null;
    }
    String s = c.getSnippet().strip();
    return s.length() > MAX_SNIPPET ? s.substring(0, MAX_SNIPPET - 1) + "…" : s;
  }

  private static String capped(String reason) {
    return reason.length() > MAX_REASON ? reason.substring(0, MAX_REASON) : reason;
  }
}
