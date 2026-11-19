package ai.helper2.site.pages.internal;

import ai.helper2.catalog.usecase.ListNewestIndexable;
import ai.helper2.catalog.usecase.ListRecentResources;
import ai.helper2.catalog.usecase.ListUpcomingEvents;
import ai.helper2.search.MergedResult;
import ai.helper2.shared.ResourceType;
import ai.helper2.site.pages.Hub;
import ai.helper2.site.pages.IndexPolicy;
import ai.helper2.site.pages.Related;
import ai.helper2.site.pages.SiteLists;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class CatalogSiteLists implements SiteLists {

  static final Duration KEEP = Duration.ofMinutes(5);
  static final Duration KEEP_HUB = Duration.ofMinutes(10);
  static final Duration RECENT_DAYS = Duration.ofDays(14);
  static final int SIZE = 20;

  private static final Logger LOG = LoggerFactory.getLogger(CatalogSiteLists.class);

  private final Clock clock;
  private final Kept newInCatalog;
  private final Kept upcomingEvents;
  private final Map<ResourceType, Kept> hubs = new EnumMap<>(ResourceType.class);

  public CatalogSiteLists(
      ListRecentResources recent,
      ListUpcomingEvents upcoming,
      ListNewestIndexable newest,
      Clock clock) {
    this.clock = clock;
    this.newInCatalog =
        new Kept(
            clock,
            KEEP,
            () -> {
              Instant now = clock.instant();
              return recent.list(now.minus(RECENT_DAYS), now, Set.of(), SIZE).items();
            });
    this.upcomingEvents =
        new Kept(
            clock,
            KEEP,
            () ->
                upcoming.list(clock.instant().truncatedTo(ChronoUnit.DAYS), null, SIZE, 0).items());
    for (Hub hub : Hub.getEntries()) {
      hubs.put(
          hub.getType(),
          new Kept(
              clock,
              KEEP_HUB,
              () -> newest.list(hub.getType().name(), IndexPolicy.PROMPT_MIN_LENGTH, Hub.SIZE)));
    }
  }

  @Override
  public List<Related> newInCatalog() {
    return related(newInCatalog.get().cards());
  }

  @Override
  public List<Related> upcomingEvents() {
    return related(upcomingEvents.get().cards());
  }

  @Override
  public List<Related> hub(ResourceType type) {
    return related(hubCards(type).cards());
  }

  public Read hubCards(ResourceType type) {
    Kept kept = hubs.get(type);
    return kept == null ? new Read(List.of(), clock.instant()) : kept.get();
  }

  public record Read(List<MergedResult> cards, Instant at) {}

  private static List<Related> related(List<MergedResult> cards) {
    return cards.stream().map(c -> new Related(c.getId().getValue(), c.getTitle())).toList();
  }

  private static final class Kept {
    private final Clock clock;
    private final Duration keep;
    private final Supplier<List<MergedResult>> read;
    private volatile Read last;

    Kept(Clock clock, Duration keep, Supplier<List<MergedResult>> read) {
      this.clock = clock;
      this.keep = keep;
      this.read = read;
    }

    Read get() {
      Read current = last;
      Instant now = clock.instant();
      if (current != null && !current.at().plus(keep).isBefore(now)) {
        return current;
      }
      try {
        current = new Read(read.get(), now);
        last = current;
        return current;
      } catch (RuntimeException e) {
        LOG.warn("site list read failed, the page goes out without it", e);
        return current == null ? new Read(List.of(), now) : current;
      }
    }
  }
}
