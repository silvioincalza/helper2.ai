package ai.searchavo.catalog;

import java.util.Optional;
import java.util.Set;

/**
 * UC-030 BR-010: the aggregated figures a directory states for one item: the average, its scale and
 * the number of votes. A page is untrusted input, so the figures exist only inside their ranges.
 */
public record StatedFigures(double average, int scale, int votes) {

  public static final Set<Integer> SCALES = Set.of(5, 10, 100);

  public static final int MOST_VOTES = 100_000_000;

  /** Empty when the scale is not one a directory uses or a value is outside its range. */
  public static Optional<StatedFigures> checked(double average, int scale, long votes) {
    boolean inRange =
        SCALES.contains(scale)
            && average > 0
            && average <= scale
            && votes >= 1
            && votes <= MOST_VOTES;
    return inRange
        ? Optional.of(new StatedFigures(Math.round(average * 100) / 100.0, scale, (int) votes))
        : Optional.empty();
  }

  /** The average on the scale from 1 to 5: another scale is converted, and 1 is the lowest. */
  public double fromOneToFive() {
    return Math.clamp(average * 5 / scale, 1.0, 5.0);
  }
}
