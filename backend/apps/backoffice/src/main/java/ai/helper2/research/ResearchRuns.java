package ai.helper2.research;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public interface ResearchRuns {

  List<ResearchRun> enqueue(Collection<UUID> itemIds, String model, String by);

  Set<UUID> activeItems();

  Optional<ResearchRun> claimNext(Instant now);

  void step(ResearchStep step);

  void done(UUID runId, ResearchResult result, int promptTokens, int completionTokens, Instant at);

  void failed(UUID runId, String error, int promptTokens, int completionTokens, Instant at);

  boolean cancel(UUID runId, Instant at);

  boolean isCancelled(UUID runId);

  int failStale(Instant before, Instant at);

  Optional<ResearchRun> find(UUID runId);

  List<ResearchRun> latest(Collection<UUID> itemIds);

  Set<UUID> itemsWhere(ResearchFilter filter);

  List<ResearchStep> steps(UUID runId, int afterSeq);

  int purge(Instant before);
}
