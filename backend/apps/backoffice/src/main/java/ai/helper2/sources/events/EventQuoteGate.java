package ai.helper2.sources.events;

import ai.helper2.shared.EventFormat;
import ai.helper2.shared.EventKind;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Decides what a model's reading of an event page may publish (ADR 0021). The model only points to
 * a quote; code checks that the quote is on the page, reads the days from it and compares them with
 * the model's. A page with more than one event, a quote without a year, last year's edition, an
 * event further than six months ahead or longer than a month: nothing is published. Kind, format
 * and place are kept only when their own quote is on the page and names them.
 */
public class EventQuoteGate {

  public static final String NOT_ONE_EVENT = "the page is not about one event";
  public static final String QUOTE_LENGTH = "the date quote is not 20 to 300 characters";
  public static final String QUOTE_NOT_ON_PAGE = "the date quote is not on the page";
  public static final String NO_DAYS_IN_QUOTE = "the quote states no one range of days with a year";
  public static final String DAYS_DIFFER = "the model's days differ from the quote's";
  public static final String NOT_AHEAD = "the event does not start in the next six months";
  public static final String TOO_LONG = "the event lasts longer than a month";

  static final int MIN_QUOTE = 20;
  static final int MAX_QUOTE = 300;
  static final int AHEAD_MONTHS = 6;
  static final int MAX_SPAN_DAYS = 31;

  /** Days the gate let through, with the quote that states them. */
  public record CheckedEvent(
      LocalDate start,
      LocalDate end,
      EventKind kind,
      EventFormat format,
      String location,
      String dateQuote) {}

  /** The event, or why nothing of the reading is published. */
  public record Verdict(Optional<CheckedEvent> event, String reason) {
    static Verdict rejected(String reason) {
      return new Verdict(Optional.empty(), reason);
    }
  }

  /** The words that name each kind; whole words only. */
  private static final Map<EventKind, List<String>> KIND_WORDS =
      Map.of(
          EventKind.CONFERENCE,
          List.of("conference", "summit", "congress", "symposium", "expo", "forum"),
          EventKind.HACKATHON,
          List.of("hackathon", "buildathon", "hack day"),
          EventKind.MEETUP,
          List.of("meetup", "meet-up", "meet up"),
          EventKind.TALK,
          List.of("talk", "webinar", "lecture", "fireside chat", "keynote"),
          EventKind.WORKSHOP,
          List.of("workshop", "bootcamp", "masterclass", "hands-on session"));

  private static final Map<EventFormat, List<String>> FORMAT_WORDS =
      Map.of(
          EventFormat.ONLINE,
          List.of("online", "virtual", "livestream", "live stream", "remote"),
          EventFormat.IN_PERSON,
          List.of("in person", "in-person", "on-site", "onsite", "on site"),
          EventFormat.HYBRID,
          List.of("hybrid"));

  public Verdict check(String page, EventReading reading, LocalDate today) {
    if (reading.eventsOnPage() != 1) {
      return Verdict.rejected(NOT_ONE_EVENT);
    }
    String quote = reading.dateQuote();
    if (quote == null || quote.length() < MIN_QUOTE || quote.length() > MAX_QUOTE) {
      return Verdict.rejected(QUOTE_LENGTH);
    }
    if (!PageText.contains(page, quote)) {
      return Verdict.rejected(QUOTE_NOT_ON_PAGE);
    }
    Optional<DayRange> stated = QuotedDays.find(quote);
    if (stated.isEmpty()) {
      return Verdict.rejected(NO_DAYS_IN_QUOTE);
    }
    DayRange days = stated.get();
    if (!days.start().equals(reading.start())
        || (reading.end() != null && !days.end().equals(reading.end()))) {
      return Verdict.rejected(DAYS_DIFFER);
    }
    if (days.start().isBefore(today) || days.start().isAfter(today.plusMonths(AHEAD_MONTHS))) {
      return Verdict.rejected(NOT_AHEAD);
    }
    if (ChronoUnit.DAYS.between(days.start(), days.end()) > MAX_SPAN_DAYS) {
      return Verdict.rejected(TOO_LONG);
    }
    return new Verdict(
        Optional.of(
            new CheckedEvent(
                days.start(),
                days.end(),
                named(page, reading.kind(), reading.kindQuote(), KIND_WORDS),
                named(page, reading.format(), reading.formatQuote(), FORMAT_WORDS),
                place(page, reading.location(), reading.locationQuote()),
                quote)),
        null);
  }

  /** [value] when its quote is on the page and holds one of its words; null otherwise. */
  private static <T> T named(String page, T value, String quote, Map<T, List<String>> words) {
    if (value == null || !onPage(page, quote)) {
      return null;
    }
    String text = PageText.normalize(quote);
    return words.get(value).stream().anyMatch(w -> wholeWord(text, w)) ? value : null;
  }

  /** The place when its quote is on the page and names it as written. */
  private static String place(String page, String location, String quote) {
    if (location == null || location.isBlank() || !onPage(page, quote)) {
      return null;
    }
    return PageText.contains(quote, location) ? location.strip() : null;
  }

  private static boolean onPage(String page, String quote) {
    return quote != null && !quote.isBlank() && PageText.contains(page, quote);
  }

  private static boolean wholeWord(String text, String word) {
    return Pattern.compile("(?<![\\p{L}\\p{N}])" + Pattern.quote(word) + "(?![\\p{L}\\p{N}])")
        .matcher(text)
        .find();
  }
}
