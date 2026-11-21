package ai.helper2.sources.github.scout;

import ai.helper2.platform.resilience.CallRefused;
import ai.helper2.scout.HostLists;
import ai.helper2.scout.HostsNeverRead;
import ai.helper2.scout.ListKind;
import ai.helper2.scout.ListOrigin;
import ai.helper2.scout.ListReading;
import ai.helper2.scout.ListSeed;
import ai.helper2.scout.ListSeedStore;
import ai.helper2.scout.PageReader;
import ai.helper2.scout.ReadPage;
import ai.helper2.search.SourceFailure;
import ai.helper2.shared.CanonicalUrl;
import ai.helper2.sources.github.scout.GitHubAwesomeCollector.ListRepo;
import ai.helper2.sources.github.scout.GitHubAwesomeCollector.WebPage;
import ai.helper2.sources.liveweb.scout.ScoutLiveWebCredits;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.node.MissingNode;

public final class WebPageLists {

  static final String FEATURE = "scout-list-pages";

  static final String READ_DEPTH = "basic";

  static final String NOT_DUE = "page-not-due";

  static final String DAILY_CAP = "page-daily-cap";

  static final String HOST_OFF = "page-host-off";

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
  private final int discoveredReadsPerDay;
  private final Clock clock;
  private final @Nullable ListLinkFollower follower;

  public WebPageLists(
      ListSeedStore seeds,
      PageReader reader,
      ScoutLiveWebCredits credits,
      HostsNeverRead neverRead,
      Duration readEvery,
      Duration retryAfter,
      int discoveredReadsPerDay,
      Clock clock) {
    this(
        seeds,
        reader,
        credits,
        neverRead,
        readEvery,
        retryAfter,
        discoveredReadsPerDay,
        clock,
        null);
  }

  /** With a follower, the links of the list pages of directory hosts are followed (BR-013). */
  public WebPageLists(
      ListSeedStore seeds,
      PageReader reader,
      ScoutLiveWebCredits credits,
      HostsNeverRead neverRead,
      Duration readEvery,
      Duration retryAfter,
      int discoveredReadsPerDay,
      Clock clock,
      @Nullable ListLinkFollower follower) {
    this(
        seeds,
        new WebPageReader(reader),
        credits.forFeature(FEATURE),
        neverRead,
        readEvery,
        retryAfter,
        discoveredReadsPerDay,
        clock,
        follower);
  }

  private WebPageLists(
      ListSeedStore seeds,
      WebPageReader pages,
      @Nullable ScoutLiveWebCredits credits,
      HostsNeverRead neverRead,
      Duration readEvery,
      Duration retryAfter,
      int discoveredReadsPerDay,
      Clock clock,
      @Nullable ListLinkFollower follower) {
    this.follower = follower;
    this.seeds = seeds;
    this.pages = pages;
    this.credits = credits;
    this.neverRead = neverRead;
    this.readEvery = readEvery;
    this.retryAfter = retryAfter;
    this.discoveredReadsPerDay = discoveredReadsPerDay;
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
        0,
        clock,
        null);
  }

  /** What one pass may still read: the collector's budget and the day's share of discovery. */
  private static final class Reads {
    private final ReadmeBudget budget;
    private int discoveredLeft;
    private final Set<String> readNow = new HashSet<>();

    Reads(ReadmeBudget budget, int discoveredLeft) {
      this.budget = budget;
      this.discoveredLeft = discoveredLeft;
    }
  }

  List<AwesomeCrawl.Read> read(HostLists hosts, ReadmeBudget budget, AwesomeMetrics metrics) {
    Instant now = clock.instant();
    if (follower != null) {
      follower.passStarted();
    }
    // UC-030 BR-004: the lists an administrator added are read before the lists discovery added.
    List<ListSeed> added =
        seeds.all().stream()
            .filter(seed -> seed.getKind() == ListKind.WEB_PAGE)
            .sorted(
                Comparator.comparing(ListSeed::getOrigin)
                    .thenComparing(
                        ListSeed::getReadTriedAt, Comparator.nullsFirst(Comparator.naturalOrder())))
            .toList();
    Reads reads = new Reads(budget, discoveredReadsPerDay - discoveredReadsToday(added, now));
    Map<String, ListReading> stored = seeds.lastReads();
    List<AwesomeCrawl.Read> lists = new ArrayList<>();
    for (ListSeed page : added) {
      CanonicalUrl url = CanonicalUrl.of(page.getAddress());
      if (url == null) {
        continue;
      }
      if (neverRead(page.getAddress(), metrics)) {
        continue;
      }
      ListReading last = stored.get(page.getAddress());
      AwesomeListParser.Parsed read =
          switchedOff(page, url, hosts, metrics)
              ? lastRead(last)
              : listOf(page, last, now, reads, metrics);
      if (read != null) {
        // UC-030 BR-009: a list discovery added is a list of a directory host for good, also
        // after its host is removed from the directory hosts.
        boolean directory =
            page.getOrigin() == ListOrigin.DISCOVERY || hosts.isDirectory(url.getHost());
        boolean readNow = reads.readNow.contains(page.getAddress());
        List<ListReading.Followed> known = readNow || last == null ? List.of() : last.getFollowed();
        AwesomeListParser.Parsed parsed =
            directory ? followed(page, url, read, known, hosts, reads.budget) : read;
        Instant readAt = readNow || last == null ? now : last.getReadAt();
        lists.add(
            new AwesomeCrawl.Read(
                ListRepo.webPage(
                    url,
                    new WebPage(page.getAddress(), title(parsed, url), directory, readAt, readNow)),
                parsed,
                0));
      }
    }
    return lists;
  }

  // UC-030 BR-013: the links of a list page of a directory host are followed only while the
  // switch of its host is on; with the switch off, what following gave since its last read stays.
  private AwesomeListParser.Parsed followed(
      ListSeed page,
      CanonicalUrl url,
      AwesomeListParser.Parsed read,
      List<ListReading.Followed> known,
      HostLists hosts,
      ReadmeBudget budget) {
    if (follower == null) {
      return read;
    }
    boolean mayTry = hosts.readsListsOf(url.getHost());
    ListLinkFollower.Result result = follower.follow(url, read, known, mayTry, hosts, budget);
    if (result.changed()) {
      seeds.followed(page.getAddress(), result.followed());
    }
    return result.parsed();
  }

  // UC-030 BR-005: with the switch of its host off, a list discovery added is no longer read;
  // what its last read gave stays.
  private static boolean switchedOff(
      ListSeed page, CanonicalUrl url, HostLists hosts, AwesomeMetrics metrics) {
    if (page.getOrigin() != ListOrigin.DISCOVERY || hosts.readsListsOf(url.getHost())) {
      return false;
    }
    metrics.skipped(HOST_OFF);
    return true;
  }

  // UC-030 BR-002: a page on a host that is never read is not read and its last read is not used,
  // whatever the switch of its host says.
  private boolean neverRead(String address, AwesomeMetrics metrics) {
    HostsNeverRead.Refusal refusal = neverRead.refusalOf(address);
    if (refusal == null) {
      return false;
    }
    log.info("a list page on {} is not read: {}", refusal.getHost(), refusal.getReason());
    metrics.skipped("page-never-read");
    return true;
  }

  private static int discoveredReadsToday(List<ListSeed> pages, Instant now) {
    Instant midnight = now.truncatedTo(ChronoUnit.DAYS);
    return (int)
        pages.stream()
            .filter(page -> page.getOrigin() == ListOrigin.DISCOVERY)
            .map(ListSeed::getReadTriedAt)
            .filter(tried -> tried != null && !tried.isBefore(midnight))
            .count();
  }

  private AwesomeListParser.@Nullable Parsed listOf(
      ListSeed page,
      @Nullable ListReading stored,
      Instant now,
      Reads reads,
      AwesomeMetrics metrics) {
    String address = page.getAddress();
    String waits = waitsBecause(page, now, reads);
    if (waits == null) {
      if (page.getOrigin() == ListOrigin.DISCOVERY) {
        reads.discoveredLeft--;
      }
      AwesomeListParser.Parsed read = readNow(address, now, reads.budget, metrics);
      if (read != null) {
        reads.readNow.add(address);
        return read;
      }
    } else if (!waits.equals(NOT_DUE)) {
      log.info("a list page waits for the next pass ({})", waits);
      metrics.skipped(waits);
    }
    return lastRead(stored);
  }

  private static AwesomeListParser.@Nullable Parsed lastRead(@Nullable ListReading stored) {
    return stored == null ? null : parsed(stored);
  }

  private @Nullable String waitsBecause(ListSeed page, Instant now, Reads reads) {
    if (!due(page, now)) {
      return NOT_DUE;
    }
    // UC-030 BR-004: discovery's lists share a number of reads a day; past it a list waits.
    if (page.getOrigin() == ListOrigin.DISCOVERY && reads.discoveredLeft <= 0) {
      return DAILY_CAP;
    }
    String spent = reads.budget.spent();
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
