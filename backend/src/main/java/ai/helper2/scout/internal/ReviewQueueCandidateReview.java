package ai.helper2.scout.internal;

import ai.helper2.catalog.ReviewQueue;
import ai.helper2.scout.Candidate;
import ai.helper2.scout.CandidateReview;
import ai.helper2.scout.usecase.AcceptedCandidate;
import ai.helper2.scout.usecase.InvalidCandidate;
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
    boolean opened =
        queue.proposeCandidate(
            ReviewQueue.Kind.RESOURCE_CANDIDATE,
            url,
            capped(resourceReason(sourceId, c)),
            json.write(sourceId, c));
    if (!opened) {
      mergeIntoOpenItem(sourceId, c);
    }
    return opened;
  }

  /**
   * Another category found a page that already waits OPEN (ADR 0051): one item for the page, with
   * the categories joined, the most specific type, and this collector among its finders. A decided
   * page, or one this collector found before, stays as it is.
   */
  private void mergeIntoOpenItem(String sourceId, Candidate c) {
    queue
        .openCandidate(ReviewQueue.Kind.RESOURCE_CANDIDATE, c.getUrl().getValue())
        .ifPresent(item -> {
          String merged = json.merge(item.details(), sourceId, c);
          if (merged == null) {
            return;
          }
          try {
            queue.updateCandidate(item.id(), capped(mergedReason(merged)), merged);
          } catch (InvalidCandidate e) {
            // A stored item that is not a whole candidate stays as it is, for the admin to decide.
            log.warn("{}: candidate {} not merged: {}", sourceId, item.id(), e.getMessage());
          }
        });
  }

  /** The reason line of a merged candidate: its type, tags and every collector that found it. */
  private String mergedReason(String details) {
    AcceptedCandidate c = json.read(details);
    String tags = c.getTags().isEmpty() ? "" : " (" + String.join(", ", c.getTags()) + ")";
    return "New "
        + c.getType().name()
        + tags
        + " found by "
        + String.join(", ", json.finders(details))
        + ": "
        + c.getTitle();
  }

  /** "New PRODUCT (local-ai, self-hosted) found by discovery-local-ai: Open WebUI". */
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
