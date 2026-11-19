package ai.helper2.scout.internal;

import ai.helper2.catalog.ReviewQueue;
import ai.helper2.scout.Candidate;
import ai.helper2.scout.CandidateReview;
import ai.helper2.scout.Proposal;
import ai.helper2.scout.usecase.InvalidCandidate;
import ai.helper2.shared.ResourceType;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

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
  public Proposal propose(String sourceId, List<Candidate> candidates) {
    return propose(sourceId, candidates, null);
  }

  @Override
  public Proposal propose(
      String sourceId, List<Candidate> candidates, @Nullable Integer newPerDay) {
    int left = newPerDay == null ? Integer.MAX_VALUE : remainingToday(sourceId, newPerDay);
    int opened = 0;
    int heldBack = 0;
    for (Candidate c : candidates) {
      if (opened < left) {
        if (open(sourceId, c)) {
          opened++;
        }
      } else if (c.getType() != ResourceType.COURSE) {
        if (isNew(c)) {
          heldBack++;
        }
        mergeIntoOpenItem(sourceId, c);
      }
    }
    if (opened > 0 || heldBack > 0) {
      log.info(
          "{}: {} of {} candidate pages sent to review, {} new ones held back by the daily cap",
          sourceId,
          opened,
          candidates.size(),
          heldBack);
    }
    return new Proposal(opened, heldBack);
  }

  private boolean isNew(Candidate c) {
    return !queue.candidateKnown(ReviewQueue.Kind.RESOURCE_CANDIDATE, c.getUrl().getValue());
  }

  private int remainingToday(String sourceId, int newPerDay) {
    int opened =
        queue.candidatesOpenedToday(ReviewQueue.Kind.RESOURCE_CANDIDATE, sourceId)
            + queue.candidatesOpenedToday(ReviewQueue.Kind.COURSE_CANDIDATE, sourceId);
    return Math.max(0, newPerDay - opened);
  }

  private boolean open(String sourceId, Candidate c) {
    String url = c.getUrl().getValue();
    if (c.getType() == ResourceType.COURSE) {
      return queue.proposeCandidate(
          ReviewQueue.Kind.COURSE_CANDIDATE,
          url,
          capped(courseReason(sourceId, c.getTitle())),
          json.write(sourceId, c));
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

  private void mergeIntoOpenItem(String sourceId, Candidate c) {
    queue
        .openCandidate(ReviewQueue.Kind.RESOURCE_CANDIDATE, c.getUrl().getValue())
        .ifPresent(item -> {
          String merged = json.merge(item.details(), sourceId, c);
          if (merged == null) {
            return;
          }
          try {
            if (!queue.updateCandidate(
                item.id(), item.details(), capped(json.reason(merged)), merged)) {
              log.info("{}: candidate {} changed meanwhile, not merged", sourceId, item.id());
            }
          } catch (InvalidCandidate e) {
            log.warn("{}: candidate {} not merged: {}", sourceId, item.id(), e.getMessage());
          }
        });
  }

  static String resourceReason(String sourceId, Candidate c) {
    String category = c.getPlatform() == null ? "" : " (" + c.getPlatform() + ")";
    return "New " + c.getType().name() + category + " found by " + sourceId + ": " + c.getTitle();
  }

  static String courseReason(String sourceId, String title) {
    return "New course or certification page found by " + sourceId + ": " + title;
  }

  private static String capped(String reason) {
    return reason.length() > MAX_REASON ? reason.substring(0, MAX_REASON) : reason;
  }
}
