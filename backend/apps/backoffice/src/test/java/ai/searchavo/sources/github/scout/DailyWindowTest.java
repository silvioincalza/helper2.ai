package ai.searchavo.sources.github.scout;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class DailyWindowTest {

  static final Instant DAY = Instant.parse("2026-11-19T03:00:00Z");

  final List<Integer> items = IntStream.range(0, 250).boxed().toList();

  @Test
  void aListWithinTheCapIsReadWhole() {
    assertThat(DailyWindow.of(items.subList(0, 80), 100, DAY)).hasSize(80);
  }

  @Test
  void noCapReadsNothing() {
    assertThat(DailyWindow.of(items, 0, DAY)).isEmpty();
  }

  @Test
  void theSameDayReadsTheSameWindowWhateverTheHour() {
    assertThat(DailyWindow.of(items, 100, DAY))
        .isEqualTo(DailyWindow.of(items, 100, DAY.plus(Duration.ofHours(20))));
  }

  @Test
  void eachNextDayReadsTheNextWindowSoEveryItemIsReadAfterEnoughDays() {
    List<Integer> today = DailyWindow.of(items, 100, DAY);
    List<Integer> tomorrow = DailyWindow.of(items, 100, DAY.plus(Duration.ofDays(1)));

    assertThat(today).hasSize(100).doesNotContainAnyElementsOf(tomorrow);
    Set<Integer> read = new HashSet<>();
    for (int d = 0; d < 3; d++) {
      read.addAll(DailyWindow.of(items, 100, DAY.plus(Duration.ofDays(d))));
    }
    assertThat(read).containsExactlyInAnyOrderElementsOf(items);
  }
}
