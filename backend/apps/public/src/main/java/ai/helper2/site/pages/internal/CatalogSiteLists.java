package ai.helper2.site.pages.internal;

import ai.helper2.catalog.usecase.ListRecentResources;
import ai.helper2.catalog.usecase.ListUpcomingEvents;
import ai.helper2.search.MergedResult;
import ai.helper2.site.pages.Related;
import ai.helper2.site.pages.SiteLists;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class CatalogSiteLists implements SiteLists {

  static final Duration KEEP = Duration.ofMinutes(5);
  static final Duration RECENT_DAYS = Duration.ofDays(14);
  static final int SIZE = 20;

  private static final Logger LOG = LoggerFactory.getLogger(CatalogSiteLists.class);

  private final Kept newInCatalog;
  private final Kept upcomingEvents;

  public CatalogSiteLists(ListRecentResources recent, ListUpcomingEvents upcoming, Clock clock) {
    this.newInCatalog =
        new Kept(
            clock,
            () -> {
              Instant now = clock.instant();
              return recent.list(now.minus(RECENT_DAYS), now, Set.of(), SIZE).items();
            });
    this.upcomingEvents =
        new Kept(
            clock,
            () ->
                upcoming.list(clock.instant().truncatedTo(ChronoUnit.DAYS), null, SIZE, 0).items());
  }

  @Override
  public List<Related> newInCatalog() {
    return newInCatalog.get();
  }

  @Override
  public List<Related> upcomingEvents() {
    return upcomingEvents.get();
  }

  private static final class Kept {
    private record Read(List<Related> items, Instant at) {}

    private final Clock clock;
    private final Supplier<List<MergedResult>> read;
    private volatile Read last;

    Kept(Clock clock, Supplier<List<MergedResult>> read) {
      this.clock = clock;
      this.read = read;
    }

    List<Related> get() {
      Read current = last;
      Instant now = clock.instant();
      if (current != null && !current.at().plus(KEEP).isBefore(now)) {
        return current.items();
      }
      try {
        current = new Read(related(read.get()), now);
        last = current;
        return current.items();
      } catch (RuntimeException e) {
        LOG.warn("site list read failed, the page goes out without it", e);
        return current == null ? List.of() : current.items();
      }
    }

    private static List<Related> related(List<MergedResult> cards) {
      return cards.stream().map(c -> new Related(c.getId().getValue(), c.getTitle())).toList();
    }
  }
}
