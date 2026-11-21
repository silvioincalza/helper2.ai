package ai.helper2.sources.github.scout;

import ai.helper2.catalog.DirectoryRatingIndex;
import ai.helper2.catalog.DirectoryRatingIndex.ItemPage;
import ai.helper2.catalog.DirectoryRatingIndex.ListRatings;
import ai.helper2.catalog.DirectoryRatingIndex.Reading;
import ai.helper2.catalog.StatedFigures;
import ai.helper2.platform.resilience.CallRefused;
import ai.helper2.scout.HostLists;
import ai.helper2.scout.HostsNeverRead;
import ai.helper2.scout.PageReader;
import ai.helper2.search.SourceFailure;
import ai.helper2.shared.CanonicalUrl;
import ai.helper2.sources.liveweb.scout.ScoutLiveWebCredits;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.node.MissingNode;

/**
 * UC-030 BR-010, BR-011: takes the rating a directory states for the entries of its lists. The
 * figures come from the text of the list page next to an entry; when the entry has none and links
 * to the directory's own page of the item, that page is read through the page reader, inside its
 * own number of reads a day. Nothing else of a page is kept and no link on it is followed.
 */
public final class DirectoryRatingReader {

  /** One list page of a directory host, as its last read gave it. */
  record DirectoryList(
      CanonicalUrl url,
      Instant readAt,
      boolean readInThisPass,
      List<CanonicalUrl> named,
      List<Entry> entries) {}

  /** One entry that names one resource; {@code itemPage} is the directory's page of the item. */
  record Entry(CanonicalUrl resource, String text, @Nullable CanonicalUrl itemPage) {}

  static final String FEATURE = "scout-item-pages";

  static final String PAGES = "helper2.scout.ratings.pages";

  static final String FOUND = "helper2.scout.ratings.found";

  static final String SKIPS = "helper2.scout.ratings.skips";

  private static final String READ_DEPTH = "basic";

  private static final Logger log = LoggerFactory.getLogger(DirectoryRatingReader.class);

  private final @Nullable DirectoryRatingIndex index;
  private final WebPageReader pages;
  private final @Nullable ScoutLiveWebCredits credits;
  private final HostsNeverRead neverRead;
  private final Duration readEvery;
  private final int itemPageReadsPerDay;
  private final MeterRegistry meters;
  private final Clock clock;

  public DirectoryRatingReader(
      DirectoryRatingIndex index,
      PageReader reader,
      ScoutLiveWebCredits credits,
      HostsNeverRead neverRead,
      Duration readEvery,
      int itemPageReadsPerDay,
      MeterRegistry meters,
      Clock clock) {
    this.index = index;
    this.pages = new WebPageReader(reader);
    this.credits = credits.forFeature(FEATURE);
    this.neverRead = neverRead;
    this.readEvery = readEvery;
    this.itemPageReadsPerDay = itemPageReadsPerDay;
    this.meters = meters;
    this.clock = clock;
  }

  private DirectoryRatingReader(MeterRegistry meters, Clock clock) {
    this.index = null;
    this.pages = new WebPageReader(urls -> List.of());
    this.credits = null;
    this.neverRead = HostsNeverRead.NONE;
    this.readEvery = Duration.ofDays(7);
    this.itemPageReadsPerDay = 0;
    this.meters = meters;
    this.clock = clock;
  }

  /** Reads the figures of the list pages and no page of an item. */
  static DirectoryRatingReader listPagesOnly(MeterRegistry meters, Clock clock) {
    return new DirectoryRatingReader(meters, clock);
  }

  /** What one pass may still read of the pages of an item. */
  private static final class Reads {
    private final ReadmeBudget budget;
    private int left;

    Reads(ReadmeBudget budget, int left) {
      this.budget = budget;
      this.left = left;
    }
  }

  List<ListRatings> read(List<DirectoryList> lists, HostLists hosts, ReadmeBudget budget) {
    Instant now = clock.instant();
    Reads reads = new Reads(budget, itemPagesLeftToday(now));
    List<ListRatings> ratings = new ArrayList<>();
    for (DirectoryList list : lists) {
      ratings.add(ratingsOf(list, hosts, now, reads));
    }
    return ratings;
  }

  // UC-030 BR-011: every try of a day counts on the number of reads a day.
  private int itemPagesLeftToday(Instant now) {
    if (index == null || itemPageReadsPerDay <= 0) {
      return 0;
    }
    return itemPageReadsPerDay - index.itemPageTriesSince(now.truncatedTo(ChronoUnit.DAYS));
  }

  private ListRatings ratingsOf(DirectoryList list, HostLists hosts, Instant now, Reads reads) {
    String host = list.url().getHost();
    List<Reading> onTheList = new ArrayList<>();
    List<ItemPage> itemPages = new ArrayList<>();
    Set<CanonicalUrl> rated = new HashSet<>();
    for (Entry entry : list.entries()) {
      // Two entries of one list that name one resource give one reading: the first.
      if (!rated.add(entry.resource())) {
        continue;
      }
      Optional<StatedFigures> stated = StatedRatings.in(entry.text());
      if (stated.isPresent()) {
        onTheList.add(new Reading(entry.resource(), list.url(), stated.get(), list.readAt()));
      } else if (entry.itemPage() != null) {
        itemPages.add(new ItemPage(list.url(), entry.resource(), entry.itemPage()));
      }
    }
    if (list.readInThisPass()) {
      counted(host, "list", onTheList.size());
    }
    List<Reading> onItemPages =
        mayReadItemPages(list.url(), hosts) ? read(itemPages, host, now, reads) : List.of();
    return new ListRatings(list.url(), host, list.named(), onTheList, onItemPages);
  }

  // UC-030 BR-011: a page of an item is read only on a directory host whose switch is on, and
  // never on a host that is never read.
  private boolean mayReadItemPages(CanonicalUrl list, HostLists hosts) {
    return index != null
        && credits != null
        && pages.available()
        && hosts.readsListsOf(list.getHost())
        && neverRead.refusal(list.getHost()) == null;
  }

  private List<Reading> read(List<ItemPage> itemPages, String host, Instant now, Reads reads) {
    List<Reading> readings = new ArrayList<>();
    // UC-030 BR-011: a page of an item is read again no more often than its list.
    for (ItemPage page : index.due(itemPages, now.minus(readEvery))) {
      String waits = waitsBecause(reads);
      if (waits != null) {
        meters.counter(SKIPS, "reason", waits).increment();
        break;
      }
      reads.left--;
      index.tried(page, host, now);
      credits.spent(MissingNode.getInstance(), READ_DEPTH);
      figuresOn(page, host, reads.budget)
          .ifPresent(figures ->
              readings.add(new Reading(page.resource(), page.page(), figures.orElse(null), now)));
    }
    return readings;
  }

  private @Nullable String waitsBecause(Reads reads) {
    if (reads.left <= 0) {
      return "item-daily-cap";
    }
    String spent = reads.budget.spent();
    if (spent != null) {
      return spent;
    }
    return credits.capReached() ? "item-credit-cap" : null;
  }

  /** Empty when the page could not be read: what its last read stated then stays. */
  private Optional<Optional<StatedFigures>> figuresOn(
      ItemPage page, String host, ReadmeBudget budget) {
    try {
      String markdown = pages.markdown(page.page().getValue());
      budget.read(markdown);
      Optional<StatedFigures> stated = StatedRatings.in(markdown);
      counted(host, "item", stated.isPresent() ? 1 : 0);
      return Optional.of(stated);
    } catch (SourceFailure | CallRefused e) {
      log.warn("a page of an item on {} is unavailable: {}", host, e.getMessage());
      meters.counter(SKIPS, "reason", "item-unavailable").increment();
      return Optional.empty();
    }
  }

  // How many pages of a host were read and how many ratings they stated: the share says whether
  // the patterns fit the host.
  private void counted(String host, String page, int found) {
    meters.counter(PAGES, "host", host, "page", page).increment();
    meters.counter(FOUND, "host", host, "page", page).increment(found);
  }
}
