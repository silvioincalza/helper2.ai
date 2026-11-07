package ai.helper2.sources.events;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The days a quote states, read by code (ADR 0067): the model only points to the quote. A quote
 * proves an event's days only when it names one clear range with its year. Numeric dates other than
 * ISO (12/03/2027) are not read: their day and month order is unclear.
 */
public final class QuotedDays {

  private QuotedDays() {}

  private static final String M =
      "(january|february|march|april|may|june|july|august|september|october|november|december"
          + "|jan|feb|mar|apr|jun|jul|aug|sept|sep|oct|nov|dec)\\.?";
  private static final String D = "(\\d{1,2})";
  private static final String Y = "(\\d{4})";
  private static final String TO = "\\s*(?:-|to|until|till|through)\\s*";
  private static final String ISO = "(\\d{4})-(\\d{2})-(\\d{2})";

  private record Form(Pattern pattern, Function<Matcher, DayRange> read) {
    Form(String regex, Function<Matcher, DayRange> read) {
      this(Pattern.compile("\\b" + regex + "\\b"), read);
    }
  }

  /** Most specific first: a range is read before the single days inside it. */
  private static final List<Form> FORMS =
      List.of(
          new Form(ISO + TO + ISO, m -> range(iso(m, 1), iso(m, 4))),
          new Form(
              D + " " + M + ",? " + Y + TO + D + " " + M + ",? " + Y,
              m -> range(day(m, 3, 2, 1), day(m, 6, 5, 4))),
          new Form(D + " " + M + TO + D + " " + M + ",? " + Y, m -> crossing(m, 1, 2, 3, 4, 5)),
          new Form(D + TO + D + " " + M + ",? " + Y, m -> range(day(m, 4, 3, 1), day(m, 4, 3, 2))),
          new Form(
              M + " " + D + ",? " + Y + TO + M + " " + D + ",? " + Y,
              m -> range(day(m, 3, 1, 2), day(m, 6, 4, 5))),
          new Form(M + " " + D + TO + M + " " + D + ",? " + Y, m -> crossingMonthFirst(m)),
          new Form(M + " " + D + TO + D + ",? " + Y, m -> range(day(m, 4, 1, 2), day(m, 4, 1, 3))),
          new Form(D + " " + M + ",? " + Y, m -> single(day(m, 3, 2, 1))),
          new Form(M + " " + D + ",? " + Y, m -> single(day(m, 3, 1, 2))),
          new Form(ISO, m -> single(iso(m, 1))));

  private static final Map<String, Integer> MONTHS =
      Map.ofEntries(
          Map.entry("jan", 1),
          Map.entry("feb", 2),
          Map.entry("mar", 3),
          Map.entry("apr", 4),
          Map.entry("may", 5),
          Map.entry("jun", 6),
          Map.entry("jul", 7),
          Map.entry("aug", 8),
          Map.entry("sep", 9),
          Map.entry("oct", 10),
          Map.entry("nov", 11),
          Map.entry("dec", 12));

  /** The one range [quote] states; empty when it states none, several, or an impossible one. */
  public static Optional<DayRange> find(String quote) {
    String text = cleaned(quote);
    Set<DayRange> found = new LinkedHashSet<>();
    for (Form form : FORMS) {
      Matcher m = form.pattern().matcher(text);
      StringBuilder rest = new StringBuilder(text);
      while (m.find()) {
        DayRange r;
        try {
          r = form.read().apply(m);
        } catch (DateTimeException | IllegalArgumentException e) {
          return Optional.empty();
        }
        if (r == null) {
          return Optional.empty();
        }
        found.add(r);
        rest.replace(m.start(), m.end(), " ".repeat(m.end() - m.start()));
      }
      text = rest.toString();
    }
    return found.size() == 1 ? Optional.of(found.iterator().next()) : Optional.empty();
  }

  /** Lower case, plain dashes and spaces, no ordinals ("12th") and no weekday names. */
  private static String cleaned(String quote) {
    return PageText.normalize(quote)
        .replaceAll("\\b(\\d{1,2})(st|nd|rd|th)\\b", "$1")
        .replaceAll(
            "\\b(monday|tuesday|wednesday|thursday|friday|saturday|sunday"
                + "|mon|tue|tues|wed|thu|thur|thurs|fri|sat|sun)\\b,?",
            " ")
        .replaceAll(" +", " ");
  }

  private static DayRange single(LocalDate day) {
    return new DayRange(day, day);
  }

  /** An end before the start is no range. */
  private static DayRange range(LocalDate start, LocalDate end) {
    return end.isBefore(start) ? null : new DayRange(start, end);
  }

  /**
   * "30 December to 2 January 2027": the start falls in the year before only when that gives a
   * short span; "2 April to 30 March 2027" is no range.
   */
  private static DayRange crossing(Matcher m, int d1, int m1, int d2, int m2, int y) {
    return across(
        year(m.group(y)),
        month(m.group(m1)),
        Integer.parseInt(m.group(d1)),
        month(m.group(m2)),
        Integer.parseInt(m.group(d2)));
  }

  /** "March 30 – April 2, 2027", the month-first form of [crossing]. */
  private static DayRange crossingMonthFirst(Matcher m) {
    return across(
        year(m.group(5)),
        month(m.group(1)),
        Integer.parseInt(m.group(2)),
        month(m.group(3)),
        Integer.parseInt(m.group(4)));
  }

  /** The longest span a range across New Year may have to count as one. */
  private static final int MAX_NEW_YEAR_SPAN_DAYS = 62;

  private static DayRange across(int year, int startMonth, int startDay, int endMonth, int endDay) {
    LocalDate end = LocalDate.of(year, endMonth, endDay);
    LocalDate start = LocalDate.of(year, startMonth, startDay);
    if (!start.isAfter(end)) {
      return new DayRange(start, end);
    }
    LocalDate before = start.minusYears(1);
    return before.plusDays(MAX_NEW_YEAR_SPAN_DAYS).isBefore(end) ? null : new DayRange(before, end);
  }

  private static LocalDate day(Matcher m, int y, int mo, int d) {
    return LocalDate.of(year(m.group(y)), month(m.group(mo)), Integer.parseInt(m.group(d)));
  }

  private static LocalDate iso(Matcher m, int first) {
    return LocalDate.of(
        year(m.group(first)),
        Integer.parseInt(m.group(first + 1)),
        Integer.parseInt(m.group(first + 2)));
  }

  private static int month(String name) {
    return MONTHS.get(name.substring(0, 3));
  }

  /** A year a page about an upcoming event can state. */
  private static int year(String text) {
    int y = Integer.parseInt(text);
    if (y < 1995 || y > 2100) {
      throw new IllegalArgumentException("year " + y);
    }
    return y;
  }
}
