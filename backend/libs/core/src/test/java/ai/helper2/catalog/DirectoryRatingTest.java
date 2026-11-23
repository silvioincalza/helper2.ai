package ai.helper2.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class DirectoryRatingTest {

  static final Instant READ = Instant.parse("2026-11-21T10:00:00Z");

  static DirectoryRating.Stated stated(String host, double average, int scale, int votes) {
    return stated(host, average, scale, votes, READ);
  }

  static DirectoryRating.Stated stated(
      String host, double average, int scale, int votes, Instant readAt) {
    return new DirectoryRating.Stated(
        host,
        "https://" + host + "/best-ai-tools",
        StatedFigures.checked(average, scale, votes).orElseThrow(),
        readAt);
  }

  static double scoreOf(DirectoryRating.Stated... stated) {
    return DirectoryRating.of(List.of(stated)).orElseThrow().score();
  }

  @Test
  void uc007Br011AResourceNoDirectoryRatesIsNotRatedYet() {
    assertThat(DirectoryRating.of(List.of())).isEmpty();
  }

  @Test
  void uc007Br011FiveStarsFromTwoVotesDoNotBeatFourPointSixFromEightHundred() {
    double fewVotes = scoreOf(stated("a.example", 5.0, 5, 2));
    double manyVotes = scoreOf(stated("a.example", 4.6, 5, 800));

    assertThat(fewVotes).isCloseTo(3.18, within(0.01));
    assertThat(manyVotes).isCloseTo(4.56, within(0.01));
    assertThat(manyVotes).isGreaterThan(fewVotes);
  }

  @Test
  void uc007Br011SeveralDirectoriesGiveOneRatingWeighedByTheirVotes() {
    DirectoryRating rating =
        DirectoryRating.of(
                List.of(stated("a.example", 4.0, 5, 100), stated("b.example", 5.0, 5, 300)))
            .orElseThrow();

    assertThat(rating.directories()).isEqualTo(2);
    assertThat(rating.score()).isCloseTo((20 * 3.0 + 100 * 4.0 + 300 * 5.0) / 420, within(1e-9));
    assertThat(rating.shownScore()).isEqualTo(4.7);
  }

  @Test
  void uc007Br011AScaleOtherThanFiveIsConverted() {
    assertThat(scoreOf(stated("a.example", 9.2, 10, 1000)))
        .isCloseTo(scoreOf(stated("a.example", 4.6, 5, 1000)), within(1e-9));
    assertThat(scoreOf(stated("a.example", 92, 100, 1000)))
        .isCloseTo(scoreOf(stated("a.example", 4.6, 5, 1000)), within(1e-9));
  }

  @Test
  void uc007Br011TheRatingStaysBetweenOneAndFive() {
    assertThat(scoreOf(stated("a.example", 0.2, 5, 1_000_000))).isBetween(1.0, 1.01);
    assertThat(scoreOf(stated("a.example", 5, 5, 1_000_000))).isBetween(4.99, 5.0);
  }

  @Test
  void uc007Br011TwoListsOfOneDirectoryCountOnceWithTheFiguresReadLast() {
    DirectoryRating rating =
        DirectoryRating.of(
                List.of(
                    stated("a.example", 4.0, 5, 100, READ.minusSeconds(3600)),
                    stated("a.example", 4.2, 5, 120, READ)))
            .orElseThrow();

    assertThat(rating.directories()).isEqualTo(1);
    assertThat(rating.score()).isCloseTo((20 * 3.0 + 120 * 4.2) / 140, within(1e-9));
  }
}
