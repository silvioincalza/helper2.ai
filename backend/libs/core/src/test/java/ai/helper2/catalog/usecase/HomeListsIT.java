package ai.helper2.catalog.usecase;

import static org.assertj.core.api.Assertions.assertThat;

import ai.helper2.TestcontainersConfiguration;
import ai.helper2.catalog.CatalogWriter;
import ai.helper2.catalog.DataOrigin;
import ai.helper2.catalog.ResourceCards;
import ai.helper2.catalog.Resources;
import ai.helper2.search.MergedResult;
import ai.helper2.search.PopularityKind;
import ai.helper2.search.PopularityMetric;
import ai.helper2.search.ResultMerger;
import ai.helper2.search.ServedFrom;
import ai.helper2.search.ShadowLibraries;
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

@SpringBootTest
@Import({TestcontainersConfiguration.class, StatementCounting.class})
class HomeListsIT {

  static final Instant SINCE = Instant.parse("2099-01-01T00:00:00Z");
  static final Instant UNTIL = Instant.parse("2099-02-01T00:00:00Z");
  static final Set<String> LIVE = Set.of("github", "npm");

  @Autowired Resources store;
  @Autowired CatalogWriter writer;

  @Autowired ResourceCards cards;

  @Autowired ListRecentResources recent;

  @Autowired ListUpcomingEvents upcoming;

  @Autowired JdbcClient jdbc;

  @Autowired ShadowLibraries shadowLibraries;

  @Autowired GetResourceDetail details;

  final String run = UUID.randomUUID().toString().substring(0, 8);
  final Instant now = Instant.now();
  final List<UUID> created = new ArrayList<>();

  UUID repositoryWithPackages;

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
    writer.upsert(merged, DataOrigin.OBSERVED, LIVE);
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
    repositoryWithPackages = ResourceId.of(CanonicalUrl.of(repo)).getValue();
    created.add(repositoryWithPackages);
    publish(repositoryWithPackages, 20);
    withoutRecords =
        upsert(hit("github", "https://github.com/acme/empty-" + run, null, 0)).getFirst();
    publish(withoutRecords, 21);
    TestcontainersConfiguration.ownerJdbc()
        .sql("DELETE FROM source_record WHERE resource_id = :id")
        .param("id", withoutRecords)
        .update();
  }

  @AfterEach
  void clean() {
    TestcontainersConfiguration.ownerJdbc()
        .sql("DELETE FROM shadow_library WHERE name = :name")
        .param("name", mirror())
        .update();
    shadowLibraries.reload();
    jdbc.sql("DELETE FROM resource WHERE id = ANY(:ids)")
        .param("ids", created.toArray(UUID[]::new))
        .update();
  }

  private String mirror() {
    return "mirror-" + run;
  }

  @Test
  void aStoredResourceOnANameListedAfterwardsLeavesTheHomeListsAndItsDetail() {
    UUID book =
        upsert(hit("github", "https://de." + mirror() + ".fm/book/rag", null, 0)).getFirst();
    publish(book, 22);
    UUID fair = upsert(hit("github", "https://" + mirror() + ".fm/fair", null, 0)).getFirst();
    schedule(fair, 1);
    assertThat(recent.list(SINCE, UNTIL, Set.of(), 24).items())
        .anyMatch(c -> c.getId().getValue().equals(book));
    assertThat(details.find(book)).isPresent();

    listTheMirror();

    assertThat(recent.list(SINCE, UNTIL, Set.of(), 24).items())
        .noneMatch(c -> c.getId().getValue().equals(book));
    assertThat(upcoming.list(SINCE, null, 60, 0).items())
        .noneMatch(c -> c.getId().getValue().equals(fair));
    assertThat(details.find(book)).isEmpty();
  }

  private void listTheMirror() {
    TestcontainersConfiguration.ownerJdbc()
        .sql(
            "INSERT INTO shadow_library (name, added_by, added_at) VALUES (:name, 'home-lists-it', now())")
        .param("name", mirror())
        .update();
    shadowLibraries.reload();
  }

  @Test
  void sixtyNewerBlockedRowsLeaveThePageToTheAllowedOnesAndAreNotCounted() {
    SourceHit[] books = new SourceHit[60];
    for (int i = 0; i < books.length; i++) {
      books[i] = hit("github", "https://de." + mirror() + ".fm/book-" + i, null, 0);
    }
    List<UUID> blocked = upsert(books);
    jdbc.sql(
            "UPDATE resource SET published_at = :at, published_precision = 'INSTANT', published_source_id = 'github' WHERE id = ANY(:ids)")
        .param("at", Timestamp.from(SINCE.plus(Duration.ofDays(25))))
        .param("ids", blocked.toArray(UUID[]::new))
        .update();
    blocked.forEach(id -> schedule(id, 0));
    listTheMirror();

    ListRecentResources.Page recentPage = recent.list(SINCE, UNTIL, Set.of(), 14);
    ListUpcomingEvents.Page upcomingPage = upcoming.list(SINCE, null, 6, 0);

    assertThat(recentPage.items())
        .hasSize(13)
        .noneMatch(c -> blocked.contains(c.getId().getValue()));
    assertThat(recentPage.total()).isEqualTo(14);
    assertThat(upcomingPage.items())
        .hasSize(6)
        .noneMatch(c -> blocked.contains(c.getId().getValue()));
    assertThat(upcomingPage.total()).isEqualTo(6);
  }

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
    assertThat(counted.statements()).isLessThanOrEqualTo(7);
  }

  @Test
  void theRecentTotalCountsTheWholeWindowNotThePage() {
    ListRecentResources.Page page = recent.list(SINCE, UNTIL, Set.of(), 5);

    assertThat(page.items()).hasSize(4);
    assertThat(page.total()).isEqualTo(14);
  }

  @Test
  void theUpcomingListTakesSevenStatementsForAnyNumberOfCards() {
    var counted = StatementCounting.count(() -> upcoming.list(SINCE, null, 60, 0));

    assertThat(counted.result().items()).hasSize(6);
    assertThat(counted.result().total()).isEqualTo(6);
    assertThat(counted.statements()).isLessThanOrEqualTo(7);
  }

  @Test
  void anOffsetSkipsThatManyEventsOfTheSameOrder() {
    List<UUID> all = store.upcomingEvents(SINCE, null, 500, 0);
    List<UUID> afterTwo = store.upcomingEvents(SINCE, null, 500, 2);

    assertThat(afterTwo).containsExactlyElementsOf(all.subList(2, all.size()));
  }

  @Test
  void theUpcomingWindowEndsBeforeItsLastDayAndTheTotalFollowsIt() {
    ListUpcomingEvents.Page page = upcoming.list(SINCE, SINCE.plus(Duration.ofDays(3)), 2, 0);

    assertThat(page.items()).hasSize(2);
    assertThat(page.total()).isEqualTo(3);
  }

  @Test
  void undatedEventsAreTheEventsWithNoStartDayFoundSinceAGivenTime() {
    Instant since = now.minus(Duration.ofMinutes(5));
    UUID undated =
        upsert(hit("github", "https://github.com/acme/undated-" + run, null, 0)).getFirst();
    UUID oldUndated =
        upsert(hit("github", "https://github.com/acme/old-undated-" + run, null, 0)).getFirst();
    jdbc.sql("UPDATE resource SET type = 'EVENT' WHERE id = ANY(:ids)")
        .param("ids", new UUID[] {undated, oldUndated})
        .update();
    jdbc.sql("UPDATE resource SET first_discovered_at = :at WHERE id = :id")
        .param("at", Timestamp.from(now.minus(Duration.ofDays(30))))
        .param("id", oldUndated)
        .update();

    List<String> urls =
        store.undatedEvents(since, null, null, 500).stream()
            .map(Resources.UndatedEvent::url)
            .toList();

    assertThat(urls)
        .contains("https://github.com/acme/undated-" + run)
        .doesNotContain("https://github.com/acme/old-undated-" + run)
        .noneMatch(u -> u.contains("/event-" + run))
        .noneMatch(u -> u.contains("/home-" + run));
  }

  @Test
  void undatedEventsArePagedOldestFirstAfterTheLastOneRead() {
    Instant since = now.minus(Duration.ofMinutes(5));
    List<UUID> ids = new ArrayList<>();
    for (int i = 0; i < 3; i++) {
      UUID id =
          upsert(hit("github", "https://github.com/acme/page-undated-" + run + "-" + i, null, 0))
              .getFirst();
      jdbc.sql("UPDATE resource SET type = 'EVENT', first_discovered_at = :at WHERE id = :id")
          .param("at", Timestamp.from(now.minus(Duration.ofMinutes(3 - i))))
          .param("id", id)
          .update();
      ids.add(id);
    }

    List<Resources.UndatedEvent> mine =
        store.undatedEvents(since, null, null, 500).stream()
            .filter(e -> ids.contains(e.id()))
            .toList();
    Resources.UndatedEvent first = mine.getFirst();
    List<UUID> rest =
        store.undatedEvents(since, first.discoveredAt(), first.id(), 500).stream()
            .map(Resources.UndatedEvent::id)
            .filter(ids::contains)
            .toList();

    assertThat(mine).extracting(Resources.UndatedEvent::id).containsExactlyElementsOf(ids);
    assertThat(rest).containsExactly(ids.get(1), ids.get(2));
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
    ids.addAll(store.upcomingEvents(SINCE, null, 60, 0));

    List<MergedResult> batched = cards.cardsOf(ids);

    List<MergedResult> expected =
        ids.stream().map(this::oneByOne).flatMap(Optional::stream).toList();
    assertThat(batched).isEqualTo(expected).hasSize(19);
    assertThat(batched)
        .extracting(c -> c.getId().getValue())
        .containsExactlyElementsOf(ids.stream().filter(id -> oneByOne(id).isPresent()).toList());
    MergedResult pkg =
        batched.stream()
            .filter(c -> c.getId().getValue().equals(repositoryWithPackages))
            .findFirst()
            .orElseThrow();
    assertThat(pkg.getHits())
        .extracting(SourceHit::getSourceId)
        .containsExactlyInAnyOrder("github", "npm", "npm");
    assertThat(cards.cardsOf(List.of(repositoryWithPackages))).containsExactly(pkg);
    assertThat(cards.cardsOf(List.of())).isEmpty();
  }
}
