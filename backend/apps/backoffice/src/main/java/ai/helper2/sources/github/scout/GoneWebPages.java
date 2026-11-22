package ai.helper2.sources.github.scout;

import ai.helper2.scout.GoneWebPageRule;
import ai.helper2.scout.ListSeed;
import ai.helper2.scout.ListSeedStore;
import ai.helper2.scout.PageStatus;
import ai.helper2.scout.PageStatusReader;
import ai.helper2.shared.CanonicalUrl;
import io.micrometer.core.instrument.MeterRegistry;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * UC-030 BR-012: a web page list whose site says the page is gone no longer names its resources.
 * The page reader does not tell a page that is gone from a page it could not read, so after a
 * failed read the site is asked for the status of the page with the Scout's own status request
 * (BR-014). Only 404 and 410 count, on a set number of checks in a row over at least a day.
 */
public final class GoneWebPages {

  /** One bad hour of a site removes nothing: the first and the last check are a day apart. */
  static final Duration CONFIRMED_AFTER = Duration.ofDays(1);

  static final String CHECKS = "helper2.scout.lists.gone.checks";

  private static final Logger log = LoggerFactory.getLogger(GoneWebPages.class);

  private final PageStatusReader statuses;
  private final ListSeedStore seeds;
  private final GoneWebPageRule catalog;
  private final int checksInARow;
  private final MeterRegistry meters;

  public GoneWebPages(
      PageStatusReader statuses,
      ListSeedStore seeds,
      GoneWebPageRule catalog,
      int checksInARow,
      MeterRegistry meters) {
    this.statuses = statuses;
    this.seeds = seeds;
    this.catalog = catalog;
    this.checksInARow = checksInARow;
    this.meters = meters;
  }

  /** What one status check says about the page. */
  private enum Verdict {
    GONE,
    ANSWERS,
    SAYS_NOTHING
  }

  /**
   * Asks the site about a page whose read just failed. True when the page is gone and what it named
   * was removed.
   */
  boolean readFailed(ListSeed page, CanonicalUrl list, Instant now) {
    PageStatus status = statuses.status(URI.create(page.getAddress()));
    meters.counter(CHECKS, "outcome", outcomeOf(status)).increment();
    return switch (verdictOf(status)) {
      case GONE -> goneOnEnoughChecks(page, list, now);
      case ANSWERS -> {
        if (page.getGoneChecks() > 0) {
          seeds.notGone(page.getAddress());
        }
        yield false;
      }
      case SAYS_NOTHING -> false;
    };
  }

  // UC-030 BR-012: only 404 and 410 say that a page is gone. A 2xx says it is there, and a
  // redirect says the site still answers for the address: both start the count again, and the
  // redirect is not followed. Every other answer and every request that was not made or gave no
  // answer says nothing: it removes nothing and leaves the count as it is.
  private static Verdict verdictOf(PageStatus status) {
    if (!(status instanceof PageStatus.Answered answer)) {
      return Verdict.SAYS_NOTHING;
    }
    int code = answer.getCode();
    if (code == 404 || code == 410) {
      return Verdict.GONE;
    }
    return code / 100 == 2 || code / 100 == 3 ? Verdict.ANSWERS : Verdict.SAYS_NOTHING;
  }

  private static String outcomeOf(PageStatus status) {
    return switch (status) {
      case PageStatus.Answered answer ->
          switch (verdictOf(answer)) {
            case GONE -> "gone";
            case ANSWERS -> answer.getCode() / 100 == 2 ? "found" : "redirect";
            case SAYS_NOTHING -> "other-status";
          };
      case PageStatus.Refused refused -> refused.getReason().getTag();
    };
  }

  private boolean goneOnEnoughChecks(ListSeed page, CanonicalUrl list, Instant now) {
    ListSeed counted = seeds.goneChecked(page.getAddress(), now);
    if (counted == null || !isGone(counted, now)) {
      return false;
    }
    // The catalog first: when the list's own state is not cleaned after it, the next pass records
    // the stored read again and the next check that says "gone" removes it again.
    if (catalog.forget(list, now)) {
      meters.counter(GoneWebPageRule.FORGOTTEN, "kind", "web-page").increment();
      log.info(
          "a list page on {} is gone at its site: it no longer names its resources",
          list.getHost());
    }
    seeds.forgotten(page.getAddress());
    return true;
  }

  private boolean isGone(ListSeed page, Instant now) {
    Instant since = page.getGoneSince();
    return page.getGoneChecks() >= checksInARow
        && since != null
        && !now.isBefore(since.plus(CONFIRMED_AFTER));
  }
}
