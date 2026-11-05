package ai.helper2.catalog.usecase;

import static org.assertj.core.api.Assertions.assertThat;

import ai.helper2.TestcontainersConfiguration;
import ai.helper2.catalog.internal.ResourceCards;
import ai.helper2.catalog.internal.ResourceStore;
import ai.helper2.catalog.internal.ResourceStore.DataOrigin;
import ai.helper2.search.MergedResult;
import ai.helper2.search.PopularityKind;
import ai.helper2.search.PopularityMetric;
import ai.helper2.search.ResultMerger;
import ai.helper2.search.ServedFrom;
import ai.helper2.search.SourceContext;
import ai.helper2.search.SourceDescriptor;
import ai.helper2.search.SourceHit;
import ai.helper2.shared.Audience;
import ai.helper2.shared.CanonicalUrl;
import ai.helper2.shared.DateFact;
import ai.helper2.shared.ResourceId;
import ai.helper2.shared.ResourceType;
import ai.helper2.shared.SourceMode;
import ai.helper2.shared.ValueOrigin;
import ai.helper2.testing.StatementCounting;
import java.net.URI;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The home page lists ("Today" and "Next events") read their cards in a fixed number of statements,
 * and the batched cards are the cards the one-by-one path builds. Dates are in 2099, so only this
 * class's rows are in the windows.
 */
@SpringBootTest
@Import({TestcontainersConfiguration.class, StatementCounting.class})
class HomeListsIT {

  static final Instant SINCE = Instant.parse("2099-01-01T00:00:00Z");
  static final Instant UNTIL = Instant.parse("2099-02-01T00:00:00Z");
  static final Set<String> LIVE = Set.of("github", "npm");

  @Autowired ResourceStore store;

  @Autowired ResourceCards cards;

  @Autowired ListRecentResources recent;

  @Autowired ListUpcomingEvents upcoming;

  @Autowired JdbcClient jdbc;

  final String run = UUID.randomUUID().toString().substring(0, 8);
  final Instant now = Instant.now();
  final List<UUID> created = new ArrayList<>();

  /**
   * An npm package of a monorepo: its record names the repository, so its hits merge to the
   * repository's identity.
   */
  UUID monorepoPackage;

  /** A resource row whose records are gone: no card. */
  UUID withoutRecords;

  private SourceHit hit(String source, String url, String repo, int stars) {
    return new SourceHit(
        source,
        source + ":" + url,
        URI.create(url),
        CanonicalUrl.of(url),
        CanonicalUrl.of(repo),
        "home " + run + " " + url.substring(url.lastIndexOf('/') + 1),
        "An AI tool",
        ValueOrigin.SOURCE_PROVIDED,
        ResourceType.LIBRARY,
        Audience.BUILD_WITH_AI,
        null,
        Set.of(),
        Set.of("mcp", "python"),
        null,
        null,
        Set.of(),
        null,
        DateFact.unknown(),
        DateFact.unknown(),
        stars == 0
            ? List.of()
            : List.of(new PopularityMetric(PopularityKind.GITHUB_STARS, stars, source, now)),
        now,
        0);
  }

  private static SourceContext context(String id) {
    return new SourceContext(
        new SourceDescriptor(
            id,
            id,
            id,
            true,
            SourceMode.LIVE,
            URI.create("https://" + id + ".example"),
            null,
            null,
            EnumSet.allOf(ResourceType.class),
            Duration.ofSeconds(1),
            Duration.ZERO),
        ServedFrom.LIVE);
  }

  private List<UUID> upsert(SourceHit... hits) {
    List<MergedResult> merged =
        new ResultMerger()
            .merge(List.of(hits), Map.of("github", context("github"), "npm", context("npm")));
    List<UUID> ids = merged.stream().map(m -> m.getId().getValue()).toList();
    store.upsert(merged, DataOrigin.OBSERVED, LIVE);
    created.addAll(ids);
    return ids;
  }

  private void publish(UUID id, int day) {
    jdbc.sql(
            "UPDATE resource SET published_at = :at, published_precision = 'INSTANT', published_source_id = 'github' WHERE id = :id")
        .param("at", Timestamp.from(SINCE.plus(Duration.ofDays(day))))
        .param("id", id)
        .update();
  }

  private void schedule(UUID id, int day) {
    jdbc.sql(
            "UPDATE resource SET type = 'EVENT', event_starts_at = :start, event_ends_at = :end WHERE id = :id")
        .param("start", Timestamp.from(SINCE.plus(Duration.ofDays(day))))
        .param("end", Timestamp.from(SINCE.plus(Duration.ofDays(day + 2))))
        .param("id", id)
        .update();
  }

  @BeforeEach
  void seed() {
    for (int i = 0; i < 12; i++) {
      UUID id =
          upsert(
                  hit(
                      "github",
                      "https://github.com/acme/home-" + run + "-" + i,
                      null,
                      100 * (i + 1)))
              .getFirst();
      publish(id, i);
    }
    for (int i = 0; i < 6; i++) {
      schedule(
          upsert(hit("github", "https://github.com/acme/event-" + run + "-" + i, null, 0))
              .getFirst(),
          i);
    }
    String repo = "https://github.com/acme/mono-" + run;
    String first = "https://npmjs.com/package/@acme/first-" + run;
    String second = "https://npmjs.com/package/@acme/second-" + run;
    upsert(hit("npm", first, repo, 0), hit("npm", second, repo, 0), hit("github", repo, null, 50));
    monorepoPackage = ResourceId.of(CanonicalUrl.of(first)).getValue();
    created.add(monorepoPackage);
    publish(monorepoPackage, 20);
    withoutRecords =
        upsert(hit("github", "https://github.com/acme/empty-" + run, null, 0)).getFirst();
    publish(withoutRecords, 21);
    jdbc.sql("DELETE FROM source_record WHERE resource_id = :id")
        .param("id", withoutRecords)
        .update();
  }

  @AfterEach
  void clean() {
    jdbc.sql("DELETE FROM resource WHERE id = ANY(:ids)")
        .param("ids", created.toArray(UUID[]::new))
        .update();
  }

  /** The card as the home lists built it before batching: one resource, then its records. */
  private Optional<MergedResult> oneByOne(UUID id) {
    return store.findResource(id).flatMap(row -> cards.card(row, store.recordsOf(id)));
  }

  @Test
  void theRecentListTakesSevenStatementsForAnyNumberOfCards() {
    var counted = StatementCounting.count(() -> recent.list(SINCE, UNTIL, Set.of(), 24));

    assertThat(counted.result().items())
        .hasSize(13)
        .as("the row without records is dropped")
        .noneMatch(c -> c.getId().getValue().equals(withoutRecords));
    assertThat(counted.result().total()).as("but counted").isEqualTo(14);
    // Before batching: 1 + 5 per card (here 1 + 5 × 14 = 71, the row without records included).
    // The seventh statement is the total.
    assertThat(counted.statements()).isLessThanOrEqualTo(7);
  }

  @Test
  void theRecentTotalCountsTheWholeWindowNotThePage() {
    ListRecentResources.Page page = recent.list(SINCE, UNTIL, Set.of(), 5);

    // The newest five include the row without records: it is counted, not shown.
    assertThat(page.items()).hasSize(4);
    assertThat(page.total()).isEqualTo(14);
  }

  @Test
  void theUpcomingListTakesSevenStatementsForAnyNumberOfCards() {
    var counted = StatementCounting.count(() -> upcoming.list(SINCE, null, 60));

    assertThat(counted.result().items()).hasSize(6);
    assertThat(counted.result().total()).isEqualTo(6);
    // Before batching: 1 + 5 × 6 = 31. The seventh statement is the total.
    assertThat(counted.statements()).isLessThanOrEqualTo(7);
  }

  @Test
  void theUpcomingWindowEndsBeforeItsLastDayAndTheTotalFollowsIt() {
    // Events start on days 0..5 after SINCE; a 3-day window holds days 0, 1 and 2.
    ListUpcomingEvents.Page page = upcoming.list(SINCE, SINCE.plus(Duration.ofDays(3)), 2);

    assertThat(page.items()).hasSize(2);
    assertThat(page.total()).isEqualTo(3);
  }

  @Test
  void eachSourceCountsTheResourcesFirstDiscoveredSinceAGivenTime() {
    Instant since = now.minus(Duration.ofMinutes(5));
    Map<String, Long> before = store.discoveredSince(since);
    UUID old = upsert(hit("npm", "https://npmjs.com/package/old-" + run, null, 0)).getFirst();
    jdbc.sql("UPDATE resource SET first_discovered_at = :at WHERE id = :id")
        .param("at", Timestamp.from(now.minus(Duration.ofDays(30))))
        .param("id", old)
        .update();
    upsert(hit("npm", "https://npmjs.com/package/fresh-" + run, null, 0));

    Map<String, Long> after = store.discoveredSince(since);

    assertThat(after.get("npm") - before.getOrDefault("npm", 0L))
        .as("only the fresh package")
        .isEqualTo(1L);
  }

  @Test
  void batchedCardsAreTheCardsOfTheOneByOnePathInTheGivenOrder() {
    List<UUID> ids =
        new ArrayList<>(
            store.recentlyPublished(SINCE, UNTIL, Set.of(), Set.of(DataOrigin.OBSERVED), 24));
    ids.add(1, UUID.randomUUID());
    ids.addAll(store.upcomingEvents(SINCE, null, 60));

    List<MergedResult> batched = cards.cardsOf(ids);

    List<MergedResult> expected =
        ids.stream().map(this::oneByOne).flatMap(Optional::stream).toList();
    assertThat(batched).isEqualTo(expected).hasSize(19);
    assertThat(batched)
        .extracting(c -> c.getId().getValue())
        .containsExactlyElementsOf(ids.stream().filter(id -> oneByOne(id).isPresent()).toList());
    MergedResult pkg =
        batched.stream()
            .filter(c -> c.getId().getValue().equals(monorepoPackage))
            .findFirst()
            .orElseThrow();
    assertThat(pkg.getHits()).extracting(SourceHit::getSourceId).containsExactly("npm");
    assertThat(cards.cardsOf(List.of(monorepoPackage))).containsExactly(pkg);
    assertThat(cards.cardsOf(List.of())).isEmpty();
  }
}
