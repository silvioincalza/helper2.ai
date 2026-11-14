package ai.helper2.catalog;

import java.util.UUID;

public interface ReviewDecisions {
  boolean decide(UUID itemId, ReviewQueue.Status decision, String by, String note);
}
