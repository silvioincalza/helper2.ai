package ai.helper2.research.usecase;

import ai.helper2.research.ResearchItems;
import ai.helper2.research.ResearchRun;
import ai.helper2.research.ResearchRuns;
import java.util.List;
import java.util.UUID;

public final class StartResearch {

  public static final int MAX_ITEM_IDS = 500;

  public static final int MAX_ALL_OPEN = 20_000;

  public static final int MAX_MODEL_CALLS_PER_ITEM = RunResearch.MAX_TOOL_CALLS + 2;

  private final ResearchRuns runs;
  private final ResearchItems items;
  private final String model;

  public StartResearch(ResearchRuns runs, ResearchItems items, String model) {
    this.runs = runs;
    this.items = items;
    this.model = model;
  }

  public record Estimate(int items, long maxModelCalls, int alreadyResearched) {}

  public List<ResearchRun> start(List<UUID> itemIds, String by) {
    if (itemIds.size() > MAX_ITEM_IDS) {
      throw new IllegalArgumentException("at most " + MAX_ITEM_IDS + " items at a time");
    }
    return runs.enqueue(
        itemIds.stream().distinct().filter(id -> items.subject(id).isPresent()).toList(),
        model,
        by);
  }

  public Estimate estimateAllOpen() {
    int n = items.openNotResearched(MAX_ALL_OPEN).size();
    return new Estimate(n, (long) n * MAX_MODEL_CALLS_PER_ITEM, items.openResearchedDone());
  }

  public List<ResearchRun> startAllOpen(String by) {
    return runs.enqueue(items.openNotResearched(MAX_ALL_OPEN), model, by);
  }
}
