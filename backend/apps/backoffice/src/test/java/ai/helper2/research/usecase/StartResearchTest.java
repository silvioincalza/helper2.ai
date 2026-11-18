package ai.helper2.research.usecase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.helper2.research.ResearchItems;
import ai.helper2.research.ResearchSubject;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class StartResearchTest {

  final UUID itemId = UUID.randomUUID();
  final RunResearchTest outer = new RunResearchTest();
  final RunResearchTest.Runs runs = outer.runs;

  final ResearchItems items =
      new ResearchItems() {
        @Override
        public Optional<ResearchSubject> subject(UUID id) {
          return Optional.of(outer.subject);
        }

        @Override
        public int openResearchedDone() {
          return 3;
        }

        @Override
        public List<UUID> openNotResearched(int limit) {
          return List.of(itemId);
        }
      };

  @Test
  void whileResearchIsOffNoRunIsQueued() {
    StartResearch off = new StartResearch(runs, items, "m", false);

    assertThatThrownBy(() -> off.start(List.of(itemId), "admin"))
        .isInstanceOf(StartResearch.ResearchOff.class);
    assertThatThrownBy(() -> off.startAllOpen("admin"))
        .isInstanceOf(StartResearch.ResearchOff.class);
  }

  @Test
  void theEstimateCountsTheNewItemsTheirCallsAndTheOnesAlreadyResearched() {
    StartResearch.Estimate e = new StartResearch(runs, items, "m", true).estimateAllOpen();

    assertThat(e).isEqualTo(new StartResearch.Estimate(1, RunResearch.MAX_MODEL_CALLS, 3));
  }

  @Test
  void moreIdsThanOneRequestTakesAreRefused() {
    List<UUID> many =
        java.util.stream.Stream.generate(UUID::randomUUID)
            .limit(StartResearch.MAX_ITEM_IDS + 1)
            .toList();

    assertThatThrownBy(() -> new StartResearch(runs, items, "m", true).start(many, "admin"))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
