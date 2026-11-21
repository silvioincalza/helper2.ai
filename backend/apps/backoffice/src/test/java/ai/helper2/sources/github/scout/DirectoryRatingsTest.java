package ai.helper2.sources.github.scout;

import static org.assertj.core.api.Assertions.assertThat;

import ai.helper2.catalog.DirectoryRatingIndex;
import ai.helper2.catalog.DirectoryRatingIndex.ItemPage;
import ai.helper2.catalog.DirectoryRatingIndex.ListRatings;
import ai.helper2.catalog.DirectoryRatingIndex.Reading;
import ai.helper2.catalog.StatedFigures;
import ai.helper2.platform.quota.QuotaKeys;
import ai.helper2.platform.resilience.Resilience;
import ai.helper2.scout.CollectedBatch;
import ai.helper2.scout.DiscoveryHost;
import ai.helper2.scout.HostKind;
import ai.helper2.scout.HostsNeverRead;
import ai.helper2.scout.InMemoryDiscoveryHostStore;
import ai.helper2.scout.InMemoryListSeedStore;
import ai.helper2.scout.ListKind;
import ai.helper2.scout.ListOrigin;
import ai.helper2.scout.ListReading;
import ai.helper2.scout.ListSeed;
import ai.helper2.scout.PageStatus;
import ai.helper2.scout.SeedDiscoveryHosts;
import ai.helper2.shared.CanonicalUrl;
import ai.helper2.shared.CategoryCatalog;
import ai.helper2.shared.CategoryMatcher;
import ai.helper2.sources.SourceFixtures;
import ai.helper2.sources.classifier.ItemPageJudge;
import ai.helper2.sources.liveweb.scout.ScoutLiveWebCredits;
import ai.helper2.sources.support.RunInterval;
import ai.helper2.sources.support.SourceSettings;
import ai.helper2.testing.FixtureServer;
import ai.helper2.testing.TestHttp;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.assertj.core.api.InstanceOfAssertFactories;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class DirectoryRatingsTest {

  static final String HOST = "futurepedia.io";
  static final String LIST = "https://futurepedia.io/best-ai-coding-tools";
  static final CanonicalUrl CURSOR = CanonicalUrl.of("https://cursor.com");
  static final CanonicalUrl WINDSURF = CanonicalUrl.of("https://windsurf.com");
  static final CanonicalUrl CURSOR_PAGE = CanonicalUrl.of("https://futurepedia.io/tool/cursor");
  static final CanonicalUrl WINDSURF_PAGE = CanonicalUrl.of("https://futurepedia.io/tool/windsurf");

  /** The catalog side, in memory: which resources exist, which pages were tried and when. */
  static final class Ratings implements DirectoryRatingIndex {
    final Set<CanonicalUrl> inTheCatalog = new HashSet<>();
    final Map<CanonicalUrl, Instant> tried = new HashMap<>();
    final List<Instant> tries = new ArrayList<>();
    final List<ListRatings> replaced = new ArrayList<>();

    @Override
    public List<ItemPage> due(List<ItemPage> pages, Instant since) {
      return pages.stream()
          .filter(page -> inTheCatalog.contains(page.resource()))
          .filter(page -> !tried.containsKey(page.page()) || !tried.get(page.page()).isAfter(since))
          .toList();
    }

    @Override
    public int itemPageTriesSince(Instant since) {
      return (int) tries.stream().filter(at -> !at.isBefore(since)).count();
    }

    @Override
    public void tried(ItemPage page, String host, Instant at) {
      tried.put(page.page(), at);
      tries.add(at);
    }

    @Override
    public void replace(ListRatings list) {
      replaced.add(list);
    }
  }

  final FixtureServer server = new FixtureServer();
  final SimpleMeterRegistry meters = new SimpleMeterRegistry();
  final WebPageListsTest.Days clock = new WebPageListsTest.Days();
  final WebPageListsTest.Credits credits = new WebPageListsTest.Credits();
  final QuotaKeys keys = new QuotaKeys("", clock);
  final WebPageListsTest.FakePages pages = new WebPageListsTest.FakePages();
  final InMemoryListSeedStore added = new InMemoryListSeedStore();
  final InMemoryDiscoveryHostStore hosts = SeedDiscoveryHosts.store();
  final Ratings ratings = new Ratings();
  HostsNeverRead neverRead = HostsNeverRead.NONE;
  int itemPageReadsPerDay = 100;
  int followStepsPerDay = 0;
  long scoutCredits = 1000;

  @BeforeEach
  void aDirectoryWhoseListsAreRead() {
    server.route("/search/repositories", FixtureServer.Reply.ok(WebPageListsTest.NO_LISTS));
    directoryHost(true);
    added.add(
        new ListSeed(
            ListKind.WEB_PAGE,
            LIST,
            "discovery-coding",
            WebPageListsTest.DAY_0,
            null,
            null,
            0,
            ListOrigin.DISCOVERY));
    ratings.inTheCatalog.addAll(List.of(CURSOR, WINDSURF));
  }

  @AfterEach
  void stop() {
    server.close();
  }

  void directoryHost(boolean readsLists) {
    hosts.remove(HOST);
    hosts.add(
        new DiscoveryHost(
            HOST, HostKind.DIRECTORY, "42", WebPageListsTest.DAY_0, null, readsLists));
  }

  void listSays(String... entries) {
    pages.markdown.put(LIST, "# Best AI coding tools\n## Editors\n" + String.join("\n", entries));
  }

  ListRatings ratingsOfThePass() {
    return pass().getLists().stream()
        .filter(list -> list.getUrl().getValue().equals(LIST))
        .findFirst()
        .orElseThrow()
        .getRatings();
  }

  CollectedBatch pass() {
    ScoutLiveWebCredits scout = new ScoutLiveWebCredits(credits, keys, () -> scoutCredits, meters);
    ItemPageReads readInThisPass = new ItemPageReads();
    ListLinkFollower follower =
        new ListLinkFollower(
            List.of(),
            SourceFixtures.SHADOW_LIBRARIES,
            pages,
            address -> new PageStatus.Answered(200, null),
            ItemPageJudge.NOT_ASKED,
            scout,
            credits,
            keys,
            neverRead,
            readInThisPass,
            followStepsPerDay,
            meters);
    WebPageLists webPages =
        new WebPageLists(
            added,
            pages,
            scout,
            neverRead,
            Duration.ofDays(7),
            Duration.ofHours(1),
            10,
            clock,
            follower);
    DirectoryRatingReader reader =
        new DirectoryRatingReader(
            ratings,
            pages,
            scout,
            neverRead,
            Duration.ofDays(7),
            itemPageReadsPerDay,
            meters,
            clock,
            readInThisPass);
    return new GitHubAwesomeCollector(
            new SourceSettings(
                true, server.baseUrl(), "t", Duration.ofSeconds(2), Duration.ZERO, null),
            TestHttp.restClientBuilder(Duration.ofSeconds(2), ObservationRegistry.NOOP),
            clock,
            Resilience.NONE,
            new RunInterval(Duration.ofDays(1)),
            List.of("topic:awesome-list topic:llm"),
            new GitHubAwesomeCollector.Limits(10, 1000, 1000, 1000, 50, 25),
            GitHubAwesomeCollectorTest.DEEP,
            new GitHubAwesomeCollectorTest.FakeModel(),
            new CategoryMatcher(new CategoryCatalog(List.of())),
            meters,
            hosts,
            added,
            SourceFixtures.SHADOW_LIBRARIES,
            GitHubAwesomeCollectorTest.NO_LINK_RULES,
            webPages,
            reader)
        .collect(null, null, 500);
  }

  @Test
  void uc030Br013AnEntryThatLinksOnlyTheDirectorysPageBecomesACandidateFromTheItemsOwnSite() {
    followStepsPerDay = 100;
    listSays("- [Zed](https://futurepedia.io/tool/zed) - Editor.");
    pages.markdown.put(
        "https://futurepedia.io/tool/zed", "# Zed\n[Visit website](https://zed.dev/?ref=fp)");

    CollectedBatch first = pass();

    assertThat(first.getCandidates())
        .singleElement()
        .satisfies(candidate -> {
          assertThat(candidate.getUrl().getValue()).isEqualTo("https://zed.dev");
          assertThat(candidate.getTitle()).isEqualTo("Zed");
          assertThat(candidate.getFacts().get(GitHubAwesomeCollector.FOUND_IN))
              .asInstanceOf(InstanceOfAssertFactories.MAP)
              .containsEntry("list", LIST);
        });
    assertThat(added.lastReads().get(LIST).getFollowed())
        .containsExactly(
            new ListReading.Followed(
                "https://futurepedia.io/tool/zed", "https://zed.dev", 1, "code"));

    pages.asked.clear();
    clock.pass(1);
    CollectedBatch nextDay = pass();

    assertThat(pages.asked).isEmpty();
    assertThat(nextDay.getLists().getFirst().getEntries())
        .extracting(entry -> entry.getUrl().getValue())
        .contains("https://zed.dev");
  }

  @Test
  void uc030Br011APageOfAnItemTheFollowerOfLinksReadIsNotReadAgainForItsRating() {
    followStepsPerDay = 100;
    listSays("- [Cursor](" + CURSOR_PAGE.getValue() + ") - AI code editor.");
    pages.markdown.put(
        CURSOR_PAGE.getValue(),
        "# Cursor\nRated 9.1/10 from 45 votes\n[cursor.com](https://cursor.com)");

    ListRatings rated = ratingsOfThePass();

    assertThat(rated.onItemPages())
        .containsExactly(new Reading(CURSOR, CURSOR_PAGE, figures(9.1, 10, 45), clock.instant()));
    assertThat(itemPagesAsked()).containsExactly(CURSOR_PAGE.getValue());
    assertThat(count("helper2.live-web.credits", "feature", DirectoryRatingReader.FEATURE))
        .isZero();
    assertThat(count("helper2.live-web.credits", "feature", ListLinkFollower.FEATURE)).isEqualTo(1);
  }

  static StatedFigures figures(double average, int scale, int votes) {
    return StatedFigures.checked(average, scale, votes).orElseThrow();
  }

  List<String> itemPagesAsked() {
    return pages.asked.stream().filter(address -> !address.equals(LIST)).toList();
  }

  double count(String name, String... tags) {
    var counter = meters.find(name).tags(tags).counter();
    return counter == null ? 0 : counter.count();
  }

  @Test
  void uc030Br010TheFiguresAListStatesNextToAnEntryAreTakenAndNoPageOfTheItemIsRead() {
    listSays(
        "- [Cursor](https://cursor.com) - AI code editor. 4.6/5 (1,234 reviews)"
            + " [Details](https://futurepedia.io/tool/cursor)",
        "- [Windsurf](https://windsurf.com) - Agentic editor.");

    ListRatings rated = ratingsOfThePass();

    assertThat(rated.host()).isEqualTo(HOST);
    assertThat(rated.named()).containsExactlyInAnyOrder(CURSOR, WINDSURF);
    assertThat(rated.onTheList())
        .containsExactly(
            new Reading(CURSOR, CanonicalUrl.of(LIST), figures(4.6, 5, 1234), clock.instant()));
    assertThat(rated.onItemPages()).isEmpty();
    assertThat(itemPagesAsked()).isEmpty();
    assertThat(count(DirectoryRatingReader.PAGES, "host", HOST, "page", "list")).isEqualTo(1);
    assertThat(count(DirectoryRatingReader.FOUND, "host", HOST, "page", "list")).isEqualTo(1);
  }

  @Test
  void uc030Br011AnEntryWithNoFiguresGetsThemFromTheDirectorysPageOfTheItem() {
    listSays(
        "- [Cursor](https://cursor.com) - AI code editor."
            + " [Details](https://futurepedia.io/tool/cursor)");
    pages.markdown.put(
        CURSOR_PAGE.getValue(),
        "# Cursor\nRated 9.1/10 from 45 votes\n[Visit](https://cursor.com)");

    ListRatings rated = ratingsOfThePass();

    assertThat(rated.onTheList()).isEmpty();
    assertThat(rated.onItemPages())
        .containsExactly(new Reading(CURSOR, CURSOR_PAGE, figures(9.1, 10, 45), clock.instant()));
    assertThat(itemPagesAsked()).containsExactly(CURSOR_PAGE.getValue());
    assertThat(count(DirectoryRatingReader.FOUND, "host", HOST, "page", "item")).isEqualTo(1);
    assertThat(count("helper2.live-web.credits", "feature", DirectoryRatingReader.FEATURE))
        .isEqualTo(1);
  }

  @Test
  void uc030Br011APageOfAnItemThatStatesNothingIsRecordedAsReadWithNoFigures() {
    listSays(
        "- [Cursor](https://cursor.com) - AI code editor."
            + " [Details](https://futurepedia.io/tool/cursor)");
    pages.markdown.put(CURSOR_PAGE.getValue(), "# Cursor\n4.6 stars. 120 upvotes.");

    assertThat(ratingsOfThePass().onItemPages())
        .containsExactly(new Reading(CURSOR, CURSOR_PAGE, null, clock.instant()));
    assertThat(count(DirectoryRatingReader.PAGES, "host", HOST, "page", "item")).isEqualTo(1);
    assertThat(count(DirectoryRatingReader.FOUND, "host", HOST, "page", "item")).isZero();
  }

  @Test
  void uc030Br011APageOfAnItemIsReadAgainNoMoreOftenThanItsList() {
    listSays(
        "- [Cursor](https://cursor.com) - AI code editor."
            + " [Details](https://futurepedia.io/tool/cursor)");
    pages.markdown.put(CURSOR_PAGE.getValue(), "# Cursor\n4.6/5 (800 reviews)");
    ratingsOfThePass();

    clock.pass(6);
    assertThat(ratingsOfThePass().onItemPages()).isEmpty();
    assertThat(itemPagesAsked()).hasSize(1);

    clock.pass(1);
    assertThat(ratingsOfThePass().onItemPages()).hasSize(1);
    assertThat(itemPagesAsked()).hasSize(2);
  }

  @Test
  void uc030Br011ThePagesOfItemsShareANumberOfReadsADayAndEveryTryCounts() {
    itemPageReadsPerDay = 1;
    listSays(
        "- [Cursor](https://cursor.com) - Editor. [Details](https://futurepedia.io/tool/cursor)",
        "- [Windsurf](https://windsurf.com) - Editor."
            + " [Details](https://futurepedia.io/tool/windsurf)");
    ratingsOfThePass();

    assertThat(itemPagesAsked()).hasSize(1);
    assertThat(count(DirectoryRatingReader.SKIPS, "reason", "item-daily-cap")).isEqualTo(1);
    assertThat(count(DirectoryRatingReader.SKIPS, "reason", "item-unavailable")).isEqualTo(1);

    clock.passHours(2);
    ratingsOfThePass();

    assertThat(itemPagesAsked()).hasSize(1);

    clock.pass(1);
    ratingsOfThePass();

    assertThat(itemPagesAsked()).containsExactly(CURSOR_PAGE.getValue(), WINDSURF_PAGE.getValue());
  }

  @Test
  void uc030Br011OnlyAPageOnTheHostOfTheListOfAResourceOfTheCatalogIsRead() {
    ratings.inTheCatalog.remove(WINDSURF);
    listSays(
        "- [Cursor](https://cursor.com) - Editor. [More](https://other-directory.io/tool/cursor)",
        "- [Windsurf](https://windsurf.com) - Editor."
            + " [Details](https://futurepedia.io/tool/windsurf)",
        "- [Details](https://futurepedia.io/tool/unnamed) - An entry that names no resource.");

    ratingsOfThePass();

    assertThat(itemPagesAsked()).isEmpty();
  }

  @Test
  void uc030Br011NoPageOfAnItemIsReadWithTheSwitchOffOnAHostNeverReadOrWithNoCreditLeft() {
    listSays(
        "- [Cursor](https://cursor.com) - Editor. [Details](https://futurepedia.io/tool/cursor)");
    ratingsOfThePass();
    clock.pass(7);

    directoryHost(false);
    assertThat(ratingsOfThePass().onItemPages()).isEmpty();

    directoryHost(true);
    scoutCredits = 0;
    assertThat(ratingsOfThePass().onItemPages()).isEmpty();

    scoutCredits = 1000;
    itemPageReadsPerDay = 0;
    assertThat(ratingsOfThePass().onItemPages()).isEmpty();

    assertThat(itemPagesAsked()).hasSize(1);
  }

  @Test
  void uc030Br010TwoEntriesThatNameOneResourceGiveOneReadingTheFirst() {
    listSays(
        "- [Cursor](https://cursor.com) - AI code editor. 4.6/5 (1,234 reviews)",
        "- [Cursor pricing](https://cursor.com/pricing) - Plans. 4.1/5 (90 reviews)");

    assertThat(ratingsOfThePass().onTheList())
        .extracting(Reading::resource, Reading::figures)
        .containsExactly(org.assertj.core.groups.Tuple.tuple(CURSOR, figures(4.6, 5, 1234)));
  }

  @Test
  void uc030Br010AnEntryThatNamesTwoResourcesIsNotRated() {
    listSays(
        "- [Cursor](https://cursor.com) vs [Windsurf](https://windsurf.com) 4.6/5 (90 reviews)");

    assertThat(ratingsOfThePass().onTheList()).isEmpty();
  }
}
