package ai.helper2.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class StatedFiguresTest {

  @Test
  void uc030Br010FiguresInsideTheirRangesAreKept() {
    assertThat(StatedFigures.checked(4.6, 5, 800)).contains(new StatedFigures(4.6, 5, 800));
    assertThat(StatedFigures.checked(9.1, 10, 1)).contains(new StatedFigures(9.1, 10, 1));
    assertThat(StatedFigures.checked(87, 100, 12_000)).contains(new StatedFigures(87, 100, 12_000));
  }

  @ParameterizedTest
  @CsvSource({
    "5.1, 5, 10", // above its scale
    "0, 5, 10", // no average
    "-1, 5, 10",
    "4.6, 7, 10", // a scale no directory states
    "4.6, 5, 0", // no vote
    "4.6, 5, -3",
    "4.6, 5, 100000001", // absurd
  })
  void uc030Br010AFigureOutsideItsRangeIsDropped(double average, int scale, long votes) {
    assertThat(StatedFigures.checked(average, scale, votes)).isEmpty();
  }

  @Test
  void uc030Br010AScaleOtherThanFiveIsConvertedAndOneIsTheLowest() {
    assertThat(new StatedFigures(9.1, 10, 5).fromOneToFive()).isEqualTo(4.55);
    assertThat(new StatedFigures(80, 100, 5).fromOneToFive()).isEqualTo(4.0);
    assertThat(new StatedFigures(0.5, 5, 5).fromOneToFive()).isEqualTo(1.0);
  }
}
