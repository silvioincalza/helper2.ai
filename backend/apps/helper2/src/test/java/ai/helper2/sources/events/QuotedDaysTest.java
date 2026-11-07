package ai.helper2.sources.events;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class QuotedDaysTest {

  @ParameterizedTest(name = "{0}")
  @CsvSource(
      delimiter = '|',
      value = {
        "Join us on 12 March 2027 in Berlin for a day of agents | 2027-03-12 | 2027-03-12",
        "The hackathon runs 12–14 March 2027, online and in Zurich | 2027-03-12 | 2027-03-14",
        "The hackathon runs 12-14 March 2027, online and in Zurich | 2027-03-12 | 2027-03-14",
        "Summit dates: March 12–14, 2027 at the Moscone Center | 2027-03-12 | 2027-03-14",
        "Summit dates: Mar 12 - 14, 2027 at the Moscone Center | 2027-03-12 | 2027-03-14",
        "Workshop week from 30 March to 2 April 2027, in person | 2027-03-30 | 2027-04-02",
        "Workshop week: March 30 – April 2, 2027, in person | 2027-03-30 | 2027-04-02",
        "Conference: 30 December 2026 – 2 January 2027 in Lisbon | 2026-12-30 | 2027-01-02",
        "Thursday, 12th March 2027, doors open at 18:00 in Paris | 2027-03-12 | 2027-03-12",
        "Thursday, March 12th, 2027 · 6:00 PM, online meetup | 2027-03-12 | 2027-03-12",
        "Event date 2027-03-12, starting at 9:00 CET, register now | 2027-03-12 | 2027-03-12",
        "From 2027-03-12 to 2027-03-14 the AI builders meet in Rome | 2027-03-12 | 2027-03-14",
        "The meetup is on 12 March 2027 at the ETH main building | 2027-03-12 | 2027-03-12",
      })
  void reads_the_days_a_quote_states(String quote, String start, String end) {
    assertThat(QuotedDays.find(quote))
        .contains(new DayRange(LocalDate.parse(start), LocalDate.parse(end)));
  }

  @ParameterizedTest(name = "{0}")
  @ValueSource(
      strings = {
        // No year: the model would have to guess it, so the quote proves nothing.
        "The hackathon runs 12–14 March, online and in Zurich",
        // Two different days: which one is the event?
        "Early bird ends 1 February 2027; the summit is on 12 March 2027",
        // Not a day.
        "Spring 2027, exact dates to be announced soon",
        "Q1 2027 in Berlin, sign up for updates",
        // A day that does not exist.
        "Join us on 30 February 2027 in Berlin for agents",
        // Numeric day-month order is ambiguous (12/03 or 03/12).
        "Join us on 12/03/2027 in Berlin for a day of agents",
        // An end before its start.
        "Workshop week from 2 April to 30 March 2027, in person",
      })
  void finds_no_days_in_a_quote_that_does_not_state_one_clear_range(String quote) {
    assertThat(QuotedDays.find(quote)).isEmpty();
  }

  @ParameterizedTest(name = "{0}")
  @CsvSource(
      delimiter = '|',
      value = {
        "12–14 March 2027 | 12-14 march 2027",
        "  Join us   on  “AI Day” | join us on \"ai day\"",
        "Mar 12 — 14, 2027 | mar 12 - 14, 2027",
        "ＡＩ Summit | ai summit",
      })
  void normalises_page_text_so_a_quote_matches_whatever_dash_space_or_quote_the_page_used(
      String raw, String normalised) {
    assertThat(PageText.normalize(raw)).isEqualTo(normalised);
  }
}
