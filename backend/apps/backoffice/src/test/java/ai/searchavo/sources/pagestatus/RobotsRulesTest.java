package ai.searchavo.sources.pagestatus;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** UC-030 BR-014: robots.txt is honoured for the path a status request asks. */
class RobotsRulesTest {

  @Test
  void theGroupForEveryRobotDecidesWhenNoGroupNamesSearchavo() {
    RobotsRules rules =
        RobotsRules.parse(
            """
            User-agent: otherbot
            Disallow: /

            User-agent: *
            Disallow: /go/
            Allow: /go/public
            """);

    assertThat(rules.allows("/go/acme")).isFalse();
    assertThat(rules.allows("/go/public/acme")).isTrue();
    assertThat(rules.allows("/tool/acme")).isTrue();
  }

  @Test
  void theGroupThatNamesSearchavoReplacesTheGroupForEveryRobot() {
    RobotsRules rules =
        RobotsRules.parse(
            """
            User-agent: *
            Disallow:

            User-agent: Searchavo.ai-metasearch
            User-agent: thirdbot
            Disallow: /out
            """);

    assertThat(rules.allows("/out/acme")).isFalse();
    assertThat(rules.allows("/")).isTrue();
  }

  @Test
  void aStarIsAnyTextAndADollarIsTheEndOfThePath() {
    RobotsRules rules =
        RobotsRules.parse(
            """
            User-agent: * # every robot
            Disallow: /*.php$
            Disallow: /r/*/track
            """);

    assertThat(rules.allows("/index.php")).isFalse();
    assertThat(rules.allows("/index.php?x=1")).isTrue();
    assertThat(rules.allows("/r/acme/track?id=7")).isFalse();
    assertThat(rules.allows("/r/acme")).isTrue();
  }

  @Test
  void anAllowWinsAgainstADisallowOfTheSameLengthAndAnEmptyFileAllowsAll() {
    assertThat(RobotsRules.parse("User-agent: *\nDisallow: /go\nAllow: /go").allows("/go"))
        .isTrue();
    assertThat(RobotsRules.parse("").allows("/anything")).isTrue();
    assertThat(RobotsRules.DISALLOW_ALL.allows("/")).isFalse();
  }

  @Test
  void aPatternOfManyStarsIsMatchedWithoutBacktrackingForever() {
    String pattern = "/" + "*a".repeat(200);
    String path = "/" + "a".repeat(1_900) + "b";

    long started = System.nanoTime();
    RobotsRules.matches(pattern + "$", path);

    assertThat(System.nanoTime() - started).isLessThan(2_000_000_000L);
  }
}
