package ai.helper2.sources.github.scout;

import static org.assertj.core.api.Assertions.assertThat;

import ai.helper2.platform.quota.QuotaCounter;
import ai.helper2.platform.quota.QuotaKeys;
import ai.helper2.scout.DiscoveryHost;
import ai.helper2.scout.HostKind;
import ai.helper2.scout.HostsNeverRead;
import ai.helper2.scout.InMemoryDiscoveryHostStore;
import ai.helper2.scout.ListReading.Followed;
import ai.helper2.scout.PageStatus;
import ai.helper2.scout.PageStatus.Reason;
import ai.helper2.scout.SeedDiscoveryHosts;
import ai.helper2.shared.CanonicalUrl;
import ai.helper2.sources.SourceFixtures;
import ai.helper2.sources.classifier.ItemPageJudge;
import ai.helper2.sources.evidence.PublicSuffixDomains;
import ai.helper2.sources.liveweb.scout.ScoutLiveWebCredits;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** UC-030 BR-013: a link of a directory list is followed, at most 3 steps, to the item's page. */
class ListLinkFollowerTest {

  static final String HOST = "futurepedia.io";
  static final CanonicalUrl LIST = CanonicalUrl.of("https://futurepedia.io/best-ai-coding-tools");
  static final String ITEM_PAGE = "https://futurepedia.io/tool/cursor";
  static final String OUT = "https://futurepedia.io/out/cursor";

  final SimpleMeterRegistry meters = new SimpleMeterRegistry();
  final WebPageListsTest.Days clock = new WebPageListsTest.Days();
  final WebPageListsTest.Credits counts = new WebPageListsTest.Credits();
  final QuotaKeys keys = new QuotaKeys("", clock);
  final WebPageListsTest.FakePages pages = new WebPageListsTest.FakePages();
  final InMemoryDiscoveryHostStore hosts = SeedDiscoveryHosts.store();
  final Map<String, PageStatus> statuses = new HashMap<>();
  final List<String> statusAsked = new ArrayList<>();
  final List<String> judged = new ArrayList<>();
  ItemPageJudge.Answer modelSays = ItemPageJudge.Answer.NONE;
  RuntimeException modelFails;
  HostsNeverRead neverRead = HostsNeverRead.NONE;
  int stepsPerDay = 100;
  long scoutCredits = 1000;

  @BeforeEach
  void aDirectoryWhoseListsAreRead() {
    hosts.remove(HOST);
    hosts.add(
        new DiscoveryHost(HOST, HostKind.DIRECTORY, "42", WebPageListsTest.DAY_0, null, true));
  }

  ListLinkFollower follower() {
    return new ListLinkFollower(
        List.of("wikipedia.org"),
        SourceFixtures.SHADOW_LIBRARIES,
        new PublicSuffixDomains(),
        pages,
        this::status,
        (name, pageText) -> {
          judged.add(name + " | " + pageText);
          if (modelFails != null) {
            throw modelFails;
          }
          return modelSays;
        },
        new ScoutLiveWebCredits(counts, keys, () -> scoutCredits, meters),
        counts,
        keys,
        neverRead,
        new ItemPageReads(),
        stepsPerDay,
        meters);
  }

  private PageStatus status(URI address) {
    statusAsked.add(address.toString());
    return statuses.getOrDefault(address.toString(), new PageStatus.Answered(200, null));
  }

  void redirects(String from, String to) {
    statuses.put(from, new PageStatus.Answered(302, to));
  }

  ListLinkFollower.Result followed(String... entries) {
    return followed(List.of(), true, entries);
  }

  ListLinkFollower.Result followed(List<Followed> known, boolean mayTry, String... entries) {
    AwesomeListParser.Parsed parsed =
        AwesomeListParser.parse(
            "# Best AI coding tools\n## Editors\n" + String.join("\n", entries), "");
    return follower()
        .follow(
            LIST,
            parsed,
            known,
            mayTry,
            hosts.lists(),
            ReadmeBudget.startingNow(clock, Duration.ofMinutes(5), 1_000_000));
  }

  static List<String> linksOf(ListLinkFollower.Result result) {
    return result.parsed().links().stream().map(AwesomeListParser.Link::url).toList();
  }

  double count(String name, String... tags) {
    var counter = meters.find(name).tags(tags).counter();
    return counter == null ? 0 : counter.count();
  }

  long stepsOfTheDay() {
    return counts.used(new QuotaCounter.Counter(keys.global(), ListLinkFollower.STEPS_FIELD));
  }

  @Test
  void theDirectorysPageOfTheItemLeadsToTheItemsOwnSiteWhoseAddressCarriesTheName() {
    pages.markdown.put(
        ITEM_PAGE,
        """
        # Cursor
        [Home](https://futurepedia.io) [Alternatives](https://futurepedia.io/tool/windsurf)
        [Share](https://twitter.com/intent/tweet?url=https://futurepedia.io/tool/cursor)
        The AI code editor. [cursor.com](https://cursor.com/?ref=futurepedia)
        """);

    ListLinkFollower.Result result = followed("- [Cursor](" + ITEM_PAGE + ") - AI code editor.");

    assertThat(result.followed())
        .containsExactly(new Followed(ITEM_PAGE, "https://cursor.com", 1, "code"));
    assertThat(result.changed()).isTrue();
    assertThat(linksOf(result)).containsExactly(ITEM_PAGE, "https://cursor.com");
    AwesomeListParser.Link item = result.parsed().links().get(1);
    assertThat(item.text()).isEqualTo("Cursor");
    assertThat(item.entryText()).isEqualTo(result.parsed().links().get(0).entryText());
    assertThat(pages.asked).containsExactly(ITEM_PAGE);
    assertThat(statusAsked).isEmpty();
    assertThat(stepsOfTheDay()).isEqualTo(1);
    assertThat(count(ListLinkFollower.LINKS, "host", HOST)).isEqualTo(1);
    assertThat(count(ListLinkFollower.STEPS, "host", HOST, "kind", "item-page")).isEqualTo(1);
    assertThat(count(ListLinkFollower.DECIDED, "host", HOST, "by", "code", "answer", "yes"))
        .isEqualTo(1);
    assertThat(count("helper2.live-web.credits", "feature", ListLinkFollower.FEATURE)).isEqualTo(1);
  }

  @Test
  void theVisitLinkOfTheItemPageThatRedirectsIsFollowedWithAStatusRequest() {
    pages.markdown.put(ITEM_PAGE, "# Cursor\n[Visit website](/out/cursor)\n");
    redirects(OUT, "https://cursor.com/");

    ListLinkFollower.Result result = followed("- [Cursor](" + ITEM_PAGE + ") - AI code editor.");

    assertThat(result.followed())
        .containsExactly(new Followed(ITEM_PAGE, "https://cursor.com", 2, "code"));
    assertThat(statusAsked).containsExactly(OUT);
    assertThat(count(ListLinkFollower.REQUESTS, "host", HOST, "outcome", "3xx")).isEqualTo(1);
    assertThat(count(ListLinkFollower.STEPS, "host", HOST, "kind", "status")).isEqualTo(1);
  }

  @Test
  void aPageWhoseAddressDoesNotCarryTheNameIsReadAndItsTitleDecides() {
    redirects(OUT, "https://anysphere.co/editor");
    pages.markdown.put(
        "https://anysphere.co/editor", "Skip to content\n# Cursor - the AI editor\n");

    ListLinkFollower.Result result = followed("- [Cursor](" + OUT + ") - AI code editor.");

    assertThat(result.followed())
        .containsExactly(new Followed(OUT, "https://anysphere.co/editor", 2, "code"));
    assertThat(judged).isEmpty();
    assertThat(count(ListLinkFollower.STEPS, "host", HOST, "kind", "own-page")).isEqualTo(1);
  }

  @Test
  void theModelIsAskedOnlyWhereCodeCannotDecideAndItsYesGivesThePage() {
    redirects(OUT, "https://anysphere.co/editor");
    pages.markdown.put(
        "https://anysphere.co/editor", "# The AI code editor\nBuilt to make you fast.");
    modelSays = ItemPageJudge.Answer.YES;

    ListLinkFollower.Result result = followed("- [Cursor](" + OUT + ") - AI code editor.");

    assertThat(result.followed())
        .containsExactly(new Followed(OUT, "https://anysphere.co/editor", 2, "model"));
    assertThat(judged).containsExactly("Cursor | # The AI code editor\nBuilt to make you fast.");
    assertThat(count(ListLinkFollower.DECIDED, "host", HOST, "by", "model", "answer", "yes"))
        .isEqualTo(1);
  }

  @ParameterizedTest
  @ValueSource(strings = {"NO", "NONE"})
  void aNoOrNoAnswerOfTheModelGivesNothing(String answer) {
    redirects(OUT, "https://anysphere.co/editor");
    pages.markdown.put("https://anysphere.co/editor", "# The AI code editor\n");
    modelSays = ItemPageJudge.Answer.valueOf(answer);

    ListLinkFollower.Result result = followed("- [Cursor](" + OUT + ") - AI code editor.");

    assertThat(result.followed()).containsExactly(new Followed(OUT, null, 2, null));
    assertThat(linksOf(result)).containsExactly(OUT);
    assertThat(
            count(
                ListLinkFollower.DECIDED,
                "host",
                HOST,
                "by",
                "model",
                "answer",
                answer.toLowerCase()))
        .isEqualTo(1);
    assertThat(
            count(
                ListLinkFollower.NOTHING, "host", HOST, "reason", "model-" + answer.toLowerCase()))
        .isEqualTo(1);
  }

  @Test
  void aModelThatCannotBeAskedOrFailsGivesNothing() {
    redirects(OUT, "https://anysphere.co/editor");
    pages.markdown.put("https://anysphere.co/editor", "# The AI code editor\n");
    modelFails = new IllegalStateException("daily budget used up");

    ListLinkFollower.Result result = followed("- [Cursor](" + OUT + ") - AI code editor.");

    assertThat(result.followed()).containsExactly(new Followed(OUT, null, 2, null));
    assertThat(count(ListLinkFollower.NOTHING, "host", HOST, "reason", "model-none")).isEqualTo(1);
  }

  @Test
  void afterThreeStepsWithoutThePageOfTheItemTheEntryGivesNothing() {
    redirects(OUT, "https://t.co/a1");
    redirects("https://t.co/a1", "https://bit.ly/b2");
    redirects("https://bit.ly/b2", "https://tinyurl.com/c3");
    redirects("https://tinyurl.com/c3", "https://cursor.com");

    ListLinkFollower.Result result = followed("- [Cursor](" + OUT + ") - AI code editor.");

    assertThat(result.followed()).containsExactly(new Followed(OUT, null, 3, null));
    assertThat(statusAsked).containsExactly(OUT, "https://t.co/a1", "https://bit.ly/b2");
    assertThat(count(ListLinkFollower.NOTHING, "host", HOST, "reason", "steps-spent")).isEqualTo(1);
  }

  @Test
  void theThirdStepCanStillBeTheReadOfThePageReached() {
    pages.markdown.put(ITEM_PAGE, "# Cursor\n[Visit website](/out/cursor)\n");
    redirects(OUT, "https://anysphere.co/editor");
    pages.markdown.put("https://anysphere.co/editor", "# Cursor\n");

    ListLinkFollower.Result result = followed("- [Cursor](" + ITEM_PAGE + ") - AI code editor.");

    assertThat(result.followed())
        .containsExactly(new Followed(ITEM_PAGE, "https://anysphere.co/editor", 3, "code"));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "http://cursor.com/",
        "https://10.0.0.7/",
        "https://169.254.169.254/latest/meta-data/",
        "https://localhost/admin",
        "https://cursor.internal/",
        "https://user:secret@cursor.com/",
        "https://cursor.com:8443/",
        "https://xn--crsor-0ra.com/",
        "ftp://cursor.com/file",
        "javascript:alert(1)",
      })
  void everyAddressARedirectNamesPassesTheHostCheckAgainBeforeAnyRequest(String location) {
    redirects(OUT, location);

    ListLinkFollower.Result result = followed("- [Cursor](" + OUT + ") - AI code editor.");

    assertThat(result.followed()).containsExactly(new Followed(OUT, null, 1, null));
    assertThat(statusAsked).containsExactly(OUT);
    assertThat(pages.asked).isEmpty();
  }

  @Test
  void aRedirectToAPageOfADirectoryHostOrToAShadowLibraryGivesNothing() {
    redirects(OUT, "https://producthunt.com/products/cursor");
    redirects("https://futurepedia.io/go/zed", "https://z-lib.org/zed");

    ListLinkFollower.Result result =
        followed(
            "- [Cursor](" + OUT + ") - AI code editor.",
            "- [Zed](https://futurepedia.io/go/zed) - Editor.");

    assertThat(result.followed())
        .containsExactly(
            new Followed(OUT, null, 1, null),
            new Followed("https://futurepedia.io/go/zed", null, 1, null));
    assertThat(count(ListLinkFollower.NOTHING, "host", HOST, "reason", "directory-host"))
        .isEqualTo(1);
    assertThat(count(ListLinkFollower.NOTHING, "host", HOST, "reason", "shadow-library"))
        .isEqualTo(1);
  }

  @Test
  void aStatusRequestThatIsRefusedOrIsNoRedirectGivesNothingAndIsCountedByItsOutcome() {
    statuses.put(OUT, new PageStatus.Refused(Reason.ROBOTS));
    statuses.put("https://futurepedia.io/go/zed", new PageStatus.Answered(404, null));

    ListLinkFollower.Result result =
        followed(
            "- [Cursor](" + OUT + ") - AI code editor.",
            "- [Zed](https://futurepedia.io/go/zed) - Editor.");

    assertThat(result.followed()).extracting(Followed::getItem).containsOnlyNulls();
    assertThat(count(ListLinkFollower.REQUESTS, "host", HOST, "outcome", "robots")).isEqualTo(1);
    assertThat(count(ListLinkFollower.REQUESTS, "host", HOST, "outcome", "4xx")).isEqualTo(1);
  }

  @ParameterizedTest
  @ValueSource(strings = {"DAILY_CAP", "NOT_ASKED"})
  void aStatusRequestACapOrTheOpenCircuitRefusedLeavesTheLinkWaitingAndCountsNoStep(String reason) {
    statuses.put(OUT, new PageStatus.Refused(Reason.valueOf(reason)));

    ListLinkFollower.Result result =
        followed(
            "- [Cursor](" + OUT + ") - AI code editor.",
            "- [Zed](https://futurepedia.io/out/zed) - Editor.");

    assertThat(result.followed()).isEmpty();
    assertThat(result.changed()).isFalse();
    assertThat(statusAsked).containsExactly(OUT);
    assertThat(stepsOfTheDay()).isZero();
    assertThat(count(ListLinkFollower.WAITS, "reason", "status-" + Reason.valueOf(reason).getTag()))
        .isEqualTo(1);
  }

  @Test
  void theCapOfOneHostLeavesItsLinksWaitingAndTheLinksToOtherHostsAreFollowed() {
    statuses.put(OUT, new PageStatus.Refused(Reason.HOST_CAP));
    redirects("https://t.co/zed", "https://zed.dev");

    ListLinkFollower.Result result =
        followed(
            "- [Cursor](" + OUT + ") - AI code editor.", "- [Zed](https://t.co/zed) - Editor.");

    assertThat(result.followed())
        .containsExactly(new Followed("https://t.co/zed", "https://zed.dev", 1, "code"));
    assertThat(count(ListLinkFollower.WAITS, "reason", ListLinkFollower.HOST_CAP_WAIT))
        .isEqualTo(1);
  }

  @Test
  void aRepositoryReachedBecomesAnEntryOfTheListForItsSourceToConfirm() {
    pages.markdown.put(
        ITEM_PAGE, "# Ragflow\n[GitHub](https://github.com/infiniflow/ragflow/tree/main)\n");

    ListLinkFollower.Result result = followed("- [Ragflow](" + ITEM_PAGE + ") - RAG engine.");

    assertThat(result.followed())
        .containsExactly(
            new Followed(ITEM_PAGE, "https://github.com/infiniflow/ragflow", 1, "code"));
    assertThat(result.parsed().entries())
        .extracting(AwesomeListParser.Entry::fullName)
        .containsExactly("infiniflow/ragflow");
    assertThat(pages.asked).containsExactly(ITEM_PAGE);
  }

  @Test
  void anEntryThatLinksItsItemDirectlyAndALinkWhoseWordsNameNothingAreNotFollowed() {
    ListLinkFollower.Result result =
        followed(
            "- [Cursor](https://cursor.com) - AI code editor. [Details](" + ITEM_PAGE + ")",
            "- Zed - an editor. [Website](https://futurepedia.io/out/zed)",
            "- [infiniflow/ragflow](https://github.com/infiniflow/ragflow) [Ragflow]("
                + "https://futurepedia.io/tool/ragflow)");

    assertThat(result.followed()).isEmpty();
    assertThat(result.changed()).isFalse();
    assertThat(pages.asked).isEmpty();
    assertThat(statusAsked).isEmpty();
  }

  @Test
  void aLinkToAnotherSiteThatIsNoRedirectIsNotFollowed() {
    ListLinkFollower.Result result =
        followed("- [Cursor](https://producthunt.com/products/cursor) - AI code editor.");

    assertThat(result.followed()).isEmpty();
  }

  @Test
  void whatFollowingGaveIsUsedAgainAndTheLinkIsNotTriedAgain() {
    List<Followed> known =
        List.of(
            new Followed(ITEM_PAGE, "https://cursor.com", 1, "code"),
            new Followed("https://futurepedia.io/tool/zed", null, 3, null));

    ListLinkFollower.Result result =
        followed(
            known,
            true,
            "- [Cursor](" + ITEM_PAGE + ") - AI code editor.",
            "- [Zed](https://futurepedia.io/tool/zed) - Editor.");

    assertThat(result.changed()).isFalse();
    assertThat(result.followed()).isEqualTo(known);
    assertThat(linksOf(result)).contains("https://cursor.com");
    assertThat(pages.asked).isEmpty();
  }

  @Test
  void withTheReadingTurnedOffNothingIsRequestedAndWhatIsKnownStays() {
    List<Followed> known = List.of(new Followed(ITEM_PAGE, "https://cursor.com", 1, "code"));

    ListLinkFollower.Result result =
        followed(
            known,
            false,
            "- [Cursor](" + ITEM_PAGE + ") - AI code editor.",
            "- [Zed](https://futurepedia.io/tool/zed) - Editor.");

    assertThat(result.followed()).isEqualTo(known);
    assertThat(linksOf(result)).contains("https://cursor.com");
    assertThat(pages.asked).isEmpty();
    assertThat(statusAsked).isEmpty();
  }

  @Test
  void pastTheStepsOfTheDayALinkWaitsAndIsNotRecordedAsTried() {
    stepsPerDay = 1;
    pages.markdown.put(ITEM_PAGE, "# Cursor\n[cursor.com](https://cursor.com)\n");
    pages.markdown.put("https://futurepedia.io/tool/zed", "# Zed\n[zed.dev](https://zed.dev)\n");

    ListLinkFollower.Result result =
        followed(
            "- [Cursor](" + ITEM_PAGE + ") - AI code editor.",
            "- [Zed](https://futurepedia.io/tool/zed) - Editor.");

    assertThat(result.followed())
        .containsExactly(new Followed(ITEM_PAGE, "https://cursor.com", 1, "code"));
    assertThat(pages.asked).containsExactly(ITEM_PAGE);
    assertThat(count(ListLinkFollower.WAITS, "reason", "steps-daily-cap")).isEqualTo(1);
  }

  @Test
  void aReadWaitsWhenTheCreditsOfTheScoutAreSpentOrThePageReaderIsOff() {
    scoutCredits = 0;
    assertThat(followed("- [Cursor](" + ITEM_PAGE + ") - AI code editor.").followed()).isEmpty();
    assertThat(count(ListLinkFollower.WAITS, "reason", "credit-cap")).isEqualTo(1);

    scoutCredits = 1000;
    pages.available = false;
    assertThat(followed("- [Cursor](" + ITEM_PAGE + ") - AI code editor.").followed()).isEmpty();
    assertThat(count(ListLinkFollower.WAITS, "reason", "page-reader-off")).isEqualTo(1);
    assertThat(pages.asked).isEmpty();
  }

  @Test
  void aPageThatAnswersFromAnotherHostOrWithNoTextGivesNothing() {
    pages.markdown.put(ITEM_PAGE, "# Cursor\n[cursor.com](https://cursor.com)\n");
    pages.answersFrom = "https://elsewhere.dev/";

    ListLinkFollower.Result result = followed("- [Cursor](" + ITEM_PAGE + ") - AI code editor.");

    assertThat(result.followed()).containsExactly(new Followed(ITEM_PAGE, null, 1, null));
    assertThat(count(ListLinkFollower.NOTHING, "host", HOST, "reason", "item-page-unreadable"))
        .isEqualTo(1);
  }

  @Test
  void aPageOnAHostThatIsNeverReadIsNotReadForItsTitle() {
    neverRead = new HostsNeverRead(Map.of("coursera.org", "its terms forbid it"));
    redirects(OUT, "https://www.coursera.org/learn/machine-learning");

    ListLinkFollower.Result result = followed("- [Cursor](" + OUT + ") - AI code editor.");

    assertThat(result.followed()).containsExactly(new Followed(OUT, null, 1, null));
    assertThat(pages.asked).isEmpty();
    assertThat(judged).isEmpty();
  }

  @Test
  void anItemPageWithNoLinkToTheItemGivesNothingAndASecondPageOfTheDirectoryIsNotRead() {
    pages.markdown.put(
        ITEM_PAGE,
        "# Cursor\n[Visit website](https://futurepedia.io/tool/cursor-pro)\n[Blog](https://x.dev)\n");

    ListLinkFollower.Result result = followed("- [Cursor](" + ITEM_PAGE + ") - AI code editor.");

    assertThat(result.followed()).containsExactly(new Followed(ITEM_PAGE, null, 1, null));
    assertThat(pages.asked).containsExactly(ITEM_PAGE);
  }
}
