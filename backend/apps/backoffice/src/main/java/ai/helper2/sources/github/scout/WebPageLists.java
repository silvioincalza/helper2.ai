package ai.helper2.sources.github.scout;

import ai.helper2.platform.resilience.CallRefused;
import ai.helper2.scout.HostsNeverRead;
import ai.helper2.scout.ListKind;
import ai.helper2.scout.ListReading;
import ai.helper2.scout.ListSeed;
import ai.helper2.scout.ListSeedStore;
import ai.helper2.scout.PageReader;
import ai.helper2.scout.ReadPage;
import ai.helper2.search.SourceFailure;
import ai.helper2.shared.CanonicalUrl;
import ai.helper2.sources.github.scout.GitHubAwesomeCollector.ListRepo;
import ai.helper2.sources.liveweb.scout.ScoutLiveWebCredits;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.node.MissingNode;

public final class WebPageLists {

  static final String FEATURE = "scout-list-pages";

  static final String READ_DEPTH = "basic";

  static final String NOT_DUE = "page-not-due";

  static final int MAX_FAILED_TRIES = 3;

  private static final Logger log = LoggerFactory.getLogger(WebPageLists.class);

  private static final PageReader NO_READER =
      new PageReader() {
        @Override
        public List<ReadPage> extract(List<URI> urls) {
          return List.of();
        }

        @Override
        public boolean getAvailable() {
          return false;
        }
      };

  private final ListSeedStore seeds;
  private final WebPageReader pages;
  private final @Nullable ScoutLiveWebCredits credits;
  private final HostsNeverRead neverRead;
  private final Duration readEvery;
  private final Duration retryAfter;
  private final Clock clock;

  public WebPageLists(
      ListSeedStore seeds,
      PageReader reader,
      ScoutLiveWebCredits credits,
      HostsNeverRead neverRead,
      Duration readEvery,
      Duration retryAfter,
      Clock clock) {
    this(
        seeds,
        new WebPageReader(reader),
        credits.forFeature(FEATURE),
        neverRead,
        readEvery,
        retryAfter,
        clock);
  }

  private WebPageLists(
      ListSeedStore seeds,
      WebPageReader pages,
      @Nullable ScoutLiveWebCredits credits,
      HostsNeverRead neverRead,
      Duration readEvery,
      Duration retryAfter,
      Clock clock) {
    this.seeds = seeds;
    this.pages = pages;
    this.credits = credits;
    this.neverRead = neverRead;
    this.readEvery = readEvery;
    this.retryAfter = retryAfter;
    this.clock = clock;
  }

  static WebPageLists notRead(ListSeedStore seeds, Clock clock) {
    return new WebPageLists(
        seeds,
        new WebPageReader(NO_READER),
        null,
        HostsNeverRead.NONE,
        Duration.ofDays(7),
        Duration.ofHours(1),
        clock);
  }

  List<AwesomeCrawl.Read> read(ReadmeBudget budget, AwesomeMetrics metrics) {
    Instant now = clock.instant();
    List<ListSeed> added =
        seeds.all().stream()
            .filter(seed -> seed.getKind() == ListKind.WEB_PAGE)
            .sorted(
                Comparator.comparing(
                    ListSeed::getReadTriedAt, Comparator.nullsFirst(Comparator.naturalOrder())))
            .toList();
    Map<String, ListReading> stored = seeds.lastReads();
    List<AwesomeCrawl.Read> lists = new ArrayList<>();
    for (ListSeed page : added) {
      AwesomeListParser.Parsed parsed =
          listOf(page, stored.get(page.getAddress()), now, budget, metrics);
      CanonicalUrl url = CanonicalUrl.of(page.getAddress());
      if (parsed != null && url != null) {
        lists.add(
            new AwesomeCrawl.Read(
                ListRepo.webPage(url, page.getAddress(), title(parsed, url)), parsed, 0));
      }
    }
    return lists;
  }

  private AwesomeListParser.@Nullable Parsed listOf(
      ListSeed page,
      @Nullable ListReading stored,
      Instant now,
      ReadmeBudget budget,
      AwesomeMetrics metrics) {
    String address = page.getAddress();
    HostsNeverRead.Refusal refusal = neverRead.refusalOf(address);
    if (refusal != null) {
      log.info("a list page on {} is not read: {}", refusal.getHost(), refusal.getReason());
      metrics.skipped("page-never-read");
      return null;
    }
    String waits = waitsBecause(page, now, budget);
    if (waits == null) {
      AwesomeListParser.Parsed read = readNow(address, now, budget, metrics);
      if (read != null) {
        return read;
      }
    } else if (!waits.equals(NOT_DUE)) {
      log.info("a list page waits for the next pass ({})", waits);
      metrics.skipped(waits);
    }
    return stored == null ? null : parsed(stored);
  }

  private @Nullable String waitsBecause(ListSeed page, Instant now, ReadmeBudget budget) {
    if (!due(page, now)) {
      return NOT_DUE;
    }
    String spent = budget.spent();
    if (spent != null) {
      return spent;
    }
    if (credits == null || !pages.available()) {
      return "page-reader-off";
    }
    return credits.capReached() ? "page-credit-cap" : null;
  }

  private boolean due(ListSeed page, Instant now) {
    Instant tried = page.getReadTriedAt();
    if (tried == null) {
      return true;
    }
    int failures = page.getReadFailures();
    Duration wait = failures > 0 && failures < MAX_FAILED_TRIES ? retryAfter : readEvery;
    return !now.isBefore(tried.plus(wait));
  }

  private AwesomeListParser.@Nullable Parsed readNow(
      String address, Instant now, ReadmeBudget budget, AwesomeMetrics metrics) {
    CanonicalUrl url = CanonicalUrl.of(address);
    if (url == null || credits == null) {
      return null;
    }
    seeds.readStarted(address, now);
    credits.spent(MissingNode.getInstance(), READ_DEPTH);
    try {
      String markdown = pages.markdown(address);
      budget.read(markdown);
      AwesomeListParser.Parsed parsed = AwesomeListParser.parse(markdown, "");
      seeds.readSucceeded(address, reading(parsed, now));
      metrics.listRead(0);
      return parsed;
    } catch (SourceFailure | CallRefused e) {
      log.warn("a list page on {} is unavailable: {}", url.getHost(), e.getMessage());
      metrics.skipped("page-unavailable");
      return null;
    }
  }

  static ListReading reading(AwesomeListParser.Parsed parsed, Instant readAt) {
    return new ListReading(
        readAt,
        parsed.headings(),
        parsed.entries().stream()
            .map(e ->
                new ListReading.Entry(
                    e.fullName(), e.section(), e.headingPath(), e.text(), e.inListOfLists()))
            .toList(),
        parsed.links().stream()
            .map(l ->
                new ListReading.Link(
                    l.url(), l.text(), l.section(), l.headingPath(), l.entryText()))
            .toList(),
        parsed.textLines(),
        parsed.linkedItems());
  }

  static AwesomeListParser.Parsed parsed(ListReading reading) {
    return new AwesomeListParser.Parsed(
        reading.getEntries().stream()
            .map(e ->
                new AwesomeListParser.Entry(
                    e.getRepository(),
                    e.getSection(),
                    e.getHeadingPath(),
                    e.getText(),
                    e.getInListOfLists()))
            .toList(),
        reading.getHeadings(),
        reading.getLinks().stream()
            .map(l ->
                new AwesomeListParser.Link(
                    l.getUrl(), l.getWords(), l.getSection(), l.getHeadingPath(), l.getEntryText()))
            .toList(),
        reading.getTextLines(),
        reading.getLinkedItems());
  }

  private static String title(AwesomeListParser.Parsed parsed, CanonicalUrl url) {
    return parsed.headings().isEmpty()
        ? url.getValue().substring("https://".length())
        : parsed.headings().getFirst();
  }
}
