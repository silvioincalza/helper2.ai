package ai.helper2.catalog.web;

import ai.helper2.api.PublicCache;
import ai.helper2.api.common.model.ResourceTypeDto;
import ai.helper2.api.common.model.ResultCardDto;
import ai.helper2.api.generated.HomeListsApi;
import ai.helper2.api.generated.model.RecentResponseDto;
import ai.helper2.api.generated.model.UpcomingEventsResponseDto;
import ai.helper2.catalog.usecase.ListRecentResources;
import ai.helper2.catalog.usecase.ListUpcomingEvents;
import ai.helper2.search.web.CardMapper;
import ai.helper2.shared.UtcTimes;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
class HomeListsController implements HomeListsApi {

  private static final CacheControl RECENT_CACHE =
      CacheControl.maxAge(Duration.ofMinutes(2)).cachePublic();

  private final ListRecentResources recent;
  private final ListUpcomingEvents upcoming;
  private final Clock clock;

  HomeListsController(ListRecentResources recent, ListUpcomingEvents upcoming, Clock clock) {
    this.recent = recent;
    this.upcoming = upcoming;
    this.clock = clock;
  }

  @Override
  public ResponseEntity<RecentResponseDto> listRecent(
      Integer days, @Nullable List<ResourceTypeDto> type, Integer size) {
    Instant now = clock.instant();
    Instant since = now.minus(Duration.ofDays(days == null ? 14 : days));
    Set<String> types =
        type == null
            ? Set.of()
            : type.stream().map(ResourceTypeDto::getValue).collect(Collectors.toSet());
    ListRecentResources.Page page = recent.list(since, now, types, size == null ? 12 : size);
    List<ResultCardDto> items = page.items().stream().map(CardMapper::card).toList();
    return ResponseEntity.ok()
        .cacheControl(RECENT_CACHE)
        .body(new RecentResponseDto(items, page.total(), UtcTimes.odt(since), UtcTimes.odt(now)));
  }

  @Override
  public ResponseEntity<UpcomingEventsResponseDto> listUpcomingEvents(
      @Nullable Integer days, Integer size) {
    Instant now = clock.instant();
    Instant today = now.truncatedTo(ChronoUnit.DAYS);
    Instant until = days == null ? null : today.plus(Duration.ofDays(days));
    ListUpcomingEvents.Page page = upcoming.list(today, until, size == null ? 6 : size);
    List<ResultCardDto> items = page.items().stream().map(CardMapper::card).toList();
    return ResponseEntity.ok()
        .cacheControl(PublicCache.SHORT)
        .body(
            new UpcomingEventsResponseDto(items, page.total(), UtcTimes.odt(now))
                .until(UtcTimes.odt(until)));
  }
}
