package ai.searchavo.sources.github.scout;

import ai.searchavo.catalog.StatedFigures;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * UC-030 BR-010: reads the aggregated rating a directory prints in the text of a page: the average,
 * its scale and the number of votes. Code only, no model. A text that does not state all three, or
 * states two different ratings, gives nothing: a figure is never guessed.
 */
final class StatedRatings {

  /** "4.6/5", "4.6 out of 5", "9.1/10", "87/100": the average with its scale. */
  private static final Pattern AVERAGE =
      Pattern.compile(
          "(?<![\\d.,/$€£])(\\d{1,3}(?:[.,]\\d{1,2})?)\\s*(?:/|out of)\\s*(5|10|100)(?![\\d.,/])",
          Pattern.CASE_INSENSITIVE);

  /** "1,234 reviews", "1.2k ratings", "45 votes": never upvotes or likes. */
  private static final Pattern VOTES =
      Pattern.compile(
          "(?<![\\d.,/])(\\d{1,3}(?:[., ]\\d{3}){1,2}|\\d{1,4}(?:[.,]\\d)?\\s?k|\\d{1,9})(?!\\d)\\+?\\s*"
              + "(?:(?:user|customer|verified)\\s+)?(?:reviews?|ratings?|votes?)\\b",
          Pattern.CASE_INSENSITIVE);

  private static final Pattern MARKUP = Pattern.compile("[*_`\\\\]");

  private static final Pattern SPACES = Pattern.compile("[\\s\\u00A0\\u2009\\u202F]+");

  /** How far the number of votes may stand from the average: they are stated together. */
  static final int MAX_GAP = 40;

  private record Found(int start, int end, String first, String second) {}

  private StatedRatings() {}

  static Optional<StatedFigures> in(String text) {
    String plain = SPACES.matcher(MARKUP.matcher(text).replaceAll("")).replaceAll(" ");
    List<Found> votes = found(VOTES, plain);
    Set<StatedFigures> stated = new LinkedHashSet<>();
    for (Found average : found(AVERAGE, plain)) {
      Set<Long> counts = new LinkedHashSet<>();
      votes.stream()
          .filter(count -> gap(average, count) <= MAX_GAP)
          .forEach(count -> counts.add(count(count.first())));
      // An average with two different counts next to it is two statements: none is chosen.
      if (counts.size() > 1) {
        return Optional.empty();
      }
      counts.forEach(count -> figures(average, count).ifPresent(stated::add));
    }
    return stated.size() == 1 ? Optional.of(stated.iterator().next()) : Optional.empty();
  }

  private static List<Found> found(Pattern pattern, String text) {
    List<Found> found = new ArrayList<>();
    Matcher m = pattern.matcher(text);
    while (m.find()) {
      found.add(new Found(m.start(), m.end(), m.group(1), m.groupCount() > 1 ? m.group(2) : ""));
    }
    return found;
  }

  private static int gap(Found a, Found b) {
    return a.end() <= b.start() ? b.start() - a.end() : Math.max(0, a.start() - b.end());
  }

  private static Optional<StatedFigures> figures(Found average, long votes) {
    int scale = Integer.parseInt(average.second());
    String stated = average.first().replace(',', '.');
    // A whole number out of 5 or 10 is a step or a page ("3/5") far more often than an average.
    if (scale != 100 && !stated.contains(".")) {
      return Optional.empty();
    }
    return StatedFigures.checked(Double.parseDouble(stated), scale, votes);
  }

  private static long count(String text) {
    String digits = text.strip().toLowerCase(java.util.Locale.ROOT);
    if (digits.endsWith("k")) {
      String thousands = digits.substring(0, digits.length() - 1).strip().replace(',', '.');
      return Math.round(Double.parseDouble(thousands) * 1000);
    }
    return Long.parseLong(digits.replaceAll("[., ]", ""));
  }
}
