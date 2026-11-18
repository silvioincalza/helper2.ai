package ai.helper2.research.usecase;

import ai.helper2.research.ResearchItems;
import ai.helper2.research.ResearchRun;
import ai.helper2.research.ResearchRuns;
import java.util.List;
import java.util.UUID;

public final class StartResearch {

  public static final int MAX_ITEMS = 20_000;

  public static final int MAX_MODEL_CALLS_PER_ITEM = RunResearch.MAX_TOOL_CALLS + 2;

  private final ResearchRuns runs;
  private final ResearchItems items;
  private final String model;

  public StartResearch(ResearchRuns runs, ResearchItems items, String model) {
    this.runs = runs;
    this.items = items;
    this.model = model;
  }

  public record Estimate(int items, long maxModelCalls) {}

  public List<ResearchRun> start(List<UUID> itemIds, String by) {
    if (itemIds.size() > MAX_ITEMS) {
      throw new IllegalArgumentException("at most " + MAX_ITEMS + " items at a time");
    }
    return runs.enqueue(
        itemIds.stream().distinct().filter(id -> items.subject(id).isPresent()).toList(),
        model,
        by);
  }

  public Estimate estimateAllOpen() {
    int n = items.openWithoutActiveRun(MAX_ITEMS).size();
    return new Estimate(n, (long) n * MAX_MODEL_CALLS_PER_ITEM);
  }

  public List<ResearchRun> startAllOpen(String by) {
    return runs.enqueue(items.openWithoutActiveRun(MAX_ITEMS), model, by);
  }
}
