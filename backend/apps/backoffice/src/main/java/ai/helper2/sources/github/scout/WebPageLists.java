package ai.helper2.sources.github.scout;

import ai.helper2.platform.resilience.CallRefused;
import ai.helper2.scout.HostsNeverRead;
import ai.helper2.scout.ListKind;
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
import java.util.concurrent.ConcurrentHashMap;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.node.MissingNode;

public final class WebPageLists {

  static final String FEATURE = "scout-list-pages";

  static final String READ_DEPTH = "basic";

  static final String NOT_DUE = "page-not-due";

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
  private final Clock clock;
  private final Map<String, AwesomeCrawl.Read> lastRead = new ConcurrentHashMap<>();

  public WebPageLists(
      ListSeedStore seeds,
      PageReader reader,
      ScoutLiveWebCredits credits,
      HostsNeverRead neverRead,
      Duration readEvery,
      Clock clock) {
    this(
        seeds, new WebPageReader(reader), credits.forFeature(FEATURE), neverRead, readEvery, clock);
  }

  private WebPageLists(
      ListSeedStore seeds,
      WebPageReader pages,
      @Nullable ScoutLiveWebCredits credits,
      HostsNeverRead neverRead,
      Duration readEvery,
      Clock clock) {
    this.seeds = seeds;
    this.pages = pages;
    this.credits = credits;
    this.neverRead = neverRead;
    this.readEvery = readEvery;
    this.clock = clock;
  }

  static WebPageLists notRead(ListSeedStore seeds, Clock clock) {
    return new WebPageLists(
        seeds, new WebPageReader(NO_READER), null, HostsNeverRead.NONE, Duration.ofDays(7), clock);
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
    lastRead.keySet().retainAll(added.stream().map(ListSeed::getAddress).toList());
    List<AwesomeCrawl.Read> lists = new ArrayList<>();
    for (ListSeed page : added) {
      AwesomeCrawl.Read list =
          listOf(page.getAddress(), page.getReadTriedAt(), now, budget, metrics);
      if (list != null) {
        lists.add(list);
      }
    }
    return lists;
  }

  private AwesomeCrawl.@Nullable Read listOf(
      String address,
      @Nullable Instant triedAt,
      Instant now,
      ReadmeBudget budget,
      AwesomeMetrics metrics) {
    HostsNeverRead.Refusal refusal = neverRead.refusalOf(address);
    if (refusal != null) {
      log.info("a list page on {} is not read: {}", refusal.getHost(), refusal.getReason());
      metrics.skipped("page-never-read");
      lastRead.remove(address);
      return null;
    }
    String waits = waitsBecause(triedAt, now, budget);
    if (waits == null) {
      readNow(address, now, budget, metrics);
    } else if (!waits.equals(NOT_DUE)) {
      log.info("a list page waits for the next pass ({})", waits);
      metrics.skipped(waits);
    }
    return lastRead.get(address);
  }

  private @Nullable String waitsBecause(
      @Nullable Instant triedAt, Instant now, ReadmeBudget budget) {
    if (triedAt != null && now.isBefore(triedAt.plus(readEvery))) {
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

  private void readNow(String address, Instant now, ReadmeBudget budget, AwesomeMetrics metrics) {
    CanonicalUrl url = CanonicalUrl.of(address);
    if (url == null || credits == null) {
      return;
    }
    seeds.readTried(address, now);
    credits.spent(MissingNode.getInstance(), READ_DEPTH);
    try {
      String markdown = pages.markdown(address);
      budget.read(markdown);
      AwesomeListParser.Parsed parsed = AwesomeListParser.parse(markdown, "");
      lastRead.put(
          address,
          new AwesomeCrawl.Read(ListRepo.webPage(url, address, title(parsed, url)), parsed, 0));
      metrics.listRead(0);
    } catch (SourceFailure | CallRefused e) {
      log.warn("a list page on {} is unavailable: {}", url.getHost(), e.getMessage());
      metrics.skipped("page-unavailable");
    }
  }

  private static String title(AwesomeListParser.Parsed parsed, CanonicalUrl url) {
    return parsed.headings().isEmpty()
        ? url.getValue().substring("https://".length())
        : parsed.headings().getFirst();
  }
}
