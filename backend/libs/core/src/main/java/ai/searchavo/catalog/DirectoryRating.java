package ai.searchavo.catalog;

import java.time.Instant;
import java.util.Collection;
import java.util.Comparator;
import java.util.Map;
import java.util.Optional;
import java.util.function.BinaryOperator;
import java.util.stream.Collectors;

/**
 * UC-007 BR-011: one rating from 1 to 5 of a resource, made from the figures directories state,
 * with the number of directories it comes from. A stated figure and a formula, never a model.
 */
public record DirectoryRating(double score, int directories) {

  /** The rating a resource has before any vote: the middle of the scale. */
  public static final double PRIOR_MEAN = 3.0;

  /** How many votes the prior counts for. */
  public static final int PRIOR_VOTES = 20;

  /** What one page of a directory states for a resource. */
  public record Stated(String host, String page, StatedFigures figures, Instant readAt) {}

  /**
   * The Bayesian average of the stated figures, one per directory: {@code (C × m + Σ votes ×
   * average) / (C + Σ votes)}. Few votes stay near the prior, so 5.0 from 2 votes does not beat 4.6
   * from 800. Empty when no directory states figures: the resource is not rated yet.
   */
  public static Optional<DirectoryRating> of(Collection<Stated> stated) {
    Collection<Stated> perDirectory = latestPerDirectory(stated);
    if (perDirectory.isEmpty()) {
      return Optional.empty();
    }
    double weighted = PRIOR_VOTES * PRIOR_MEAN;
    long votes = PRIOR_VOTES;
    for (Stated s : perDirectory) {
      weighted += s.figures().votes() * s.figures().fromOneToFive();
      votes += s.figures().votes();
    }
    return Optional.of(new DirectoryRating(weighted / votes, perDirectory.size()));
  }

  /** The score as it is shown: one decimal. */
  public double shownScore() {
    return Math.round(score * 10) / 10.0;
  }

  // Two lists of one directory state the same item once: the figures read last count.
  private static Collection<Stated> latestPerDirectory(Collection<Stated> stated) {
    Comparator<Stated> newest =
        Comparator.comparing(Stated::readAt).thenComparing(s -> s.figures().votes());
    Map<String, Stated> perHost =
        stated.stream()
            .collect(Collectors.toMap(Stated::host, s -> s, BinaryOperator.maxBy(newest)));
    return perHost.values();
  }
}
