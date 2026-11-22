package ai.helper2.sources.github.scout;

import static org.assertj.core.api.Assertions.assertThat;

import ai.helper2.scout.GoneWebPageRule;
import ai.helper2.scout.InMemoryListSeedStore;
import ai.helper2.scout.ListKind;
import ai.helper2.scout.ListOrigin;
import ai.helper2.scout.ListReading;
import ai.helper2.scout.ListSeed;
import ai.helper2.scout.PageStatus;
import ai.helper2.shared.CanonicalUrl;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/** UC-030 BR-012: a web page list whose site says the page is gone. */
class GoneWebPagesTest {

  static final String PAGE = "https://www.acme.dev/blog/Awesome-LLM";
  static final CanonicalUrl LIST = CanonicalUrl.of(PAGE);
  static final Instant DAY_0 = Instant.parse("2026-11-20T03:07:00Z");
  static final PageStatus GONE = new PageStatus.Answered(404, null);

  final InMemoryListSeedStore seeds = new InMemoryListSeedStore();
  final SimpleMeterRegistry meters = new SimpleMeterRegistry();
  final List<URI> asked = new ArrayList<>();
  final List<CanonicalUrl> forgotten = new ArrayList<>();
  PageStatus answer = GONE;
  boolean theCatalogHeldSomething = true;
  Instant now = DAY_0;

  GoneWebPages rule(int checksInARow) {
    return new GoneWebPages(
        address -> {
          asked.add(address);
          return answer;
        },
        seeds,
        (list, at) -> {
          forgotten.add(list);
          return theCatalogHeldSomething;
        },
        seeds::forgotten,
        checksInARow,
        meters);
  }

  void added(ListOrigin origin) {
    seeds.add(new ListSeed(ListKind.WEB_PAGE, PAGE, "42", DAY_0, null, null, 0, origin));
    seeds.readSucceeded(PAGE, new ListReading(DAY_0, List.of(), List.of(), List.of(), 0, 0));
  }

  ListSeed page() {
    return seeds.all().getFirst();
  }

  boolean readFailed(GoneWebPages rule) {
    return rule.readFailed(page(), LIST, now);
  }

  void hours(int hours) {
    now = now.plus(Duration.ofHours(hours));
  }

  double count(String name, String... tags) {
    var counter = meters.find(name).tags(tags).counter();
    return counter == null ? 0 : counter.count();
  }

  @Test
  void aPageItsSiteSaysIsGoneOnThreeChecksInARowOverADayNoLongerNamesItsResources() {
    added(ListOrigin.ADMINISTRATOR);
    GoneWebPages rule = rule(3);

    assertThat(readFailed(rule)).isFalse();
    hours(12);
    assertThat(readFailed(rule)).isFalse();
    assertThat(forgotten).isEmpty();
    hours(12);

    assertThat(readFailed(rule)).isTrue();
    assertThat(forgotten).containsExactly(LIST);
    assertThat(asked).containsOnly(URI.create(PAGE));
    assertThat(count(GoneWebPageRule.FORGOTTEN, "kind", "web-page")).isEqualTo(1);
    assertThat(count(GoneWebPages.CHECKS, "outcome", "gone")).isEqualTo(3);
  }

  @Test
  void aPageThatAnswers410IsGoneAsOneThatAnswers404() {
    added(ListOrigin.ADMINISTRATOR);
    answer = new PageStatus.Answered(410, null);
    GoneWebPages rule = rule(2);

    readFailed(rule);
    hours(24);

    assertThat(readFailed(rule)).isTrue();
  }

  @Test
  void checksInsideOneDayRemoveNothingHoweverManyTheyAre() {
    added(ListOrigin.ADMINISTRATOR);
    GoneWebPages rule = rule(3);

    for (int check = 0; check < 6; check++) {
      assertThat(readFailed(rule)).isFalse();
      hours(4);
    }

    assertThat(forgotten).isEmpty();
    assertThat(seeds.lastReads()).containsKey(PAGE);
    assertThat(page().getGoneChecks()).isEqualTo(6);

    assertThat(readFailed(rule)).isTrue();
  }

  @Test
  void fewerChecksThanTheSetNumberRemoveNothingAlsoOverManyDays() {
    added(ListOrigin.ADMINISTRATOR);
    GoneWebPages rule = rule(3);

    readFailed(rule);
    hours(24 * 7);

    assertThat(readFailed(rule)).isFalse();
    assertThat(forgotten).isEmpty();
  }

  @Test
  void theNumberOfChecksIsTheOneOfTheConfiguration() {
    added(ListOrigin.ADMINISTRATOR);
    GoneWebPages rule = rule(5);

    for (int check = 0; check < 4; check++) {
      assertThat(readFailed(rule)).isFalse();
      hours(24);
    }

    assertThat(readFailed(rule)).isTrue();
  }

  @ParameterizedTest
  @ValueSource(ints = {400, 401, 403, 405, 429, 500, 502, 503})
  void anyOtherStatusRemovesNothingAndDoesNotCount(int status) {
    added(ListOrigin.ADMINISTRATOR);
    GoneWebPages rule = rule(3);
    readFailed(rule);
    hours(24);
    readFailed(rule);
    hours(24);
    answer = new PageStatus.Answered(status, null);

    assertThat(readFailed(rule)).isFalse();

    assertThat(page().getGoneChecks()).isEqualTo(2);
    assertThat(forgotten).isEmpty();
    assertThat(count(GoneWebPages.CHECKS, "outcome", "other-status")).isEqualTo(1);
  }

  @ParameterizedTest
  @EnumSource(PageStatus.Reason.class)
  void aRequestThatWasNotMadeOrGaveNoAnswerRemovesNothingAndDoesNotCount(PageStatus.Reason reason) {
    added(ListOrigin.ADMINISTRATOR);
    GoneWebPages rule = rule(3);
    readFailed(rule);
    hours(24);
    readFailed(rule);
    hours(24);
    answer = new PageStatus.Refused(reason);

    assertThat(readFailed(rule)).isFalse();

    assertThat(page().getGoneChecks()).isEqualTo(2);
    assertThat(forgotten).isEmpty();
    assertThat(count(GoneWebPages.CHECKS, "outcome", reason.getTag())).isEqualTo(1);

    answer = GONE;
    assertThat(readFailed(rule)).isTrue();
  }

  static Stream<PageStatus> answersThatSayThePageIsThere() {
    return Stream.of(
        new PageStatus.Answered(200, null),
        new PageStatus.Answered(204, null),
        new PageStatus.Answered(301, "https://www.acme.dev/blog/moved"),
        new PageStatus.Answered(302, "https://elsewhere.example/"),
        new PageStatus.Answered(308, null));
  }

  @ParameterizedTest
  @MethodSource("answersThatSayThePageIsThere")
  void aPageThatAnswersOrRedirectsStartsTheCountAgainAndARedirectIsNotFollowed(PageStatus there) {
    added(ListOrigin.ADMINISTRATOR);
    GoneWebPages rule = rule(3);
    readFailed(rule);
    hours(24);
    readFailed(rule);
    hours(24);
    answer = there;

    assertThat(readFailed(rule)).isFalse();

    assertThat(page().getGoneChecks()).isZero();
    assertThat(page().getGoneSince()).isNull();
    assertThat(asked).containsOnly(URI.create(PAGE));

    answer = GONE;
    hours(24);
    assertThat(readFailed(rule)).isFalse();
    assertThat(page().getGoneSince()).isEqualTo(now);
    assertThat(forgotten).isEmpty();
  }

  @Test
  void aGonePageAnAdministratorAddedStaysAListToReadWithoutItsLastRead() {
    added(ListOrigin.ADMINISTRATOR);
    GoneWebPages rule = rule(1);
    readFailed(rule);
    hours(24);

    readFailed(rule);

    assertThat(seeds.all()).extracting(ListSeed::getAddress).containsExactly(PAGE);
    assertThat(seeds.lastReads()).isEmpty();
  }

  @Test
  void aGoneListDiscoveryAddedIsNoLongerAListToRead() {
    added(ListOrigin.DISCOVERY);
    GoneWebPages rule = rule(1);
    readFailed(rule);
    hours(24);

    assertThat(readFailed(rule)).isTrue();

    assertThat(seeds.all()).isEmpty();
    assertThat(seeds.lastReads()).isEmpty();
  }

  @Test
  void aGoneListThatNamedNothingIsNotCountedAsForgotten() {
    added(ListOrigin.DISCOVERY);
    theCatalogHeldSomething = false;
    GoneWebPages rule = rule(1);
    readFailed(rule);
    hours(24);

    assertThat(readFailed(rule)).isTrue();

    assertThat(count(GoneWebPageRule.FORGOTTEN, "kind", "web-page")).isZero();
    assertThat(seeds.all()).isEmpty();
  }

  @Test
  void aListRemovedFromTheListsToReadBetweenTheReadAndTheCheckIsLeftAlone() {
    added(ListOrigin.ADMINISTRATOR);
    ListSeed page = page();
    seeds.remove(PAGE);

    assertThat(rule(1).readFailed(page, LIST, now)).isFalse();
    assertThat(forgotten).isEmpty();
  }
}
