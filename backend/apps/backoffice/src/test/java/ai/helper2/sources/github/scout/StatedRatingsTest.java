package ai.helper2.sources.github.scout;

import static org.assertj.core.api.Assertions.assertThat;

import ai.helper2.catalog.StatedFigures;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class StatedRatingsTest {

  @ParameterizedTest
  @CsvSource(
      delimiter = '|',
      value = {
        "Cursor - AI code editor. 4.6/5 (1,234 reviews) | 4.6 | 5 | 1234",
        "Rated 4.6 out of 5 based on 800 reviews | 4.6 | 5 | 800",
        "⭐ 4.8 out of 5 stars · 2,310 ratings | 4.8 | 5 | 2310",
        "Score: 9.1/10 from 45 votes | 9.1 | 10 | 45",
        "1.2k reviews · 4.5/5 | 4.5 | 5 | 1200",
        "12K ratings, 4.7 / 5 | 4.7 | 5 | 12000",
        "**4.6**/5 (**800** reviews) | 4.6 | 5 | 800",
        "4,6/5 (800 Bewertungen, 800 reviews) | 4.6 | 5 | 800",
        "87/100 based on 500 user reviews | 87 | 100 | 500",
        "4.0/5 - 1 review | 4.0 | 5 | 1",
        "4.3/5 (1.250 ratings) | 4.3 | 5 | 1250",
        "4.9 out of 5 (3 000 verified reviews) | 4.9 | 5 | 3000",
      })
  void uc030Br010TheAverageItsScaleAndTheNumberOfVotesAreReadWhenAllThreeAreStated(
      String text, double average, int scale, int votes) {
    assertThat(StatedRatings.in(text)).contains(new StatedFigures(average, scale, votes));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "Cursor - AI code editor.",
        "4.6 (800)", // no scale
        "4.6 stars (800 reviews)", // no scale
        "4.6/5", // no number of votes
        "Rated 4.6 out of 5", // no number of votes
        "1,234 upvotes", // a count of upvotes is not a rating
        "4.6/5 · 120 upvotes",
        "4.6/5 · 120 likes",
        "24/7 support, 300 reviews", // 7 is no scale
        "Step 3/5: read 300 reviews", // a whole number out of 5 is not an average
        "4/5 (12 reviews)", // the average has no decimal
        "6.0/5 (10 reviews)", // above its scale
        "0.0/5 (10 reviews)",
        "4.6/5 (0 reviews)",
        "4.6/50 (10 reviews)",
        "4.6/5 (99999999999999999999999 reviews)", // absurd, and no number a count can hold
        "14.6/5 (10 reviews)",
        "$4.99/5 users, 20 reviews",
        "4.6/5 and a lot of other words that go on for a while before 800 reviews",
        "4.6/5 (800 reviews). Alternative: 4.1/5 (90 reviews)", // two statements: which one?
        "4.6/5 (800 reviews). Alternative: 4.6/5 (90 reviews)", // the same average, two counts
        "1.2k reviews · 4.5/5 · 300 ratings", // one average between two counts
        "",
      })
  void uc030Br010ATextThatDoesNotStateAllThreeOrStatesTwoRatingsGivesNothing(String text) {
    assertThat(StatedRatings.in(text)).isEmpty();
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "4.6/5 (800 reviews) and again 4.6 out of 5 from 800 reviews",
        "4.6/5\n800 reviews",
      })
  void uc030Br010TheSameFiguresStatedTwiceOrOnTheNextLineAreOneRating(String text) {
    assertThat(StatedRatings.in(text)).contains(new StatedFigures(4.6, 5, 800));
  }
}
