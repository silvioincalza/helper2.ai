package ai.helper2.catalog.internal;

import static org.assertj.core.api.Assertions.assertThat;

import ai.helper2.TestcontainersConfiguration;
import ai.helper2.catalog.internal.ResourceStore.DataOrigin;
import ai.helper2.catalog.internal.ResourceStore.ResourceRow;
import ai.helper2.catalog.usecase.GetResourceDetail;
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
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

/** The catalog's write path and index query against real PostgreSQL. */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class ResourceStoreIT {

  private static final Set<String> LIVE = Set.of("github", "npm");

  @Autowired ResourceStore store;

  @Autowired GetResourceDetail details;

  @Autowired JdbcClient jdbc;

  @Autowired TransactionTemplate tx;

  private final String run = UUID.randomUUID().toString().substring(0, 8);
  private final Instant now = Instant.now();

  private SourceHit hit(
      String source, String url, String repo, Set<String> tags, List<PopularityMetric> popularity) {
    return hit(source, source + ":" + url, url, repo, tags, popularity);
  }

  private SourceHit hit(
      String source,
      String externalId,
      String url,
      String repo,
      Set<String> tags,
      List<PopularityMetric> popularity) {
    return new SourceHit(
        source,
        externalId,
        URI.create(url),
        CanonicalUrl.of(url),
        CanonicalUrl.of(repo),
        "tool " + run,
        "An AI tool",
        ValueOrigin.SOURCE_PROVIDED,
        ResourceType.LIBRARY,
        Audience.BUILD_WITH_AI,
        null,
        Set.of(),
        tags,
        null,
        null,
        Set.of(),
        null,
        DateFact.unknown(),
        DateFact.unknown(),
        popularity,
        now,
        0);
  }

  private static List<MergedResult> merge(SourceHit... hits) {
    Map<String, SourceContext> contexts =
        Map.of("github", context("github"), "npm", context("npm"));
    return new ResultMerger().merge(List.of(hits), contexts);
  }

  private static SourceContext context(String id) {
    return new SourceContext(
        new SourceDescriptor(
            id,
            id,
            id.length() <= 12 ? id : id.substring(0, 12),
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

  private Set<String> tags(UUID id) {
    return Set.copyOf(
        jdbc.sql("SELECT value FROM resource_label WHERE resource_id = :id AND kind = 'TAG'")
            .param("id", id)
            .query(String.class)
            .list());
  }

  private SourceHit titled(String source, String url, String title) {
    return new SourceHit(
        source,
        source + ":" + url,
        URI.create(url),
        CanonicalUrl.of(url),
        null,
        title,
        "An AI tool",
        ValueOrigin.SOURCE_PROVIDED,
        ResourceType.LIBRARY,
        Audience.BUILD_WITH_AI,
        null,
        Set.of(),
        Set.of(),
        null,
        null,
        Set.of(),
        null,
        DateFact.unknown(),
        DateFact.unknown(),
        List.of(),
        now,
        0);
  }

  private String storedTitle(UUID id) {
    return jdbc.sql("SELECT title FROM resource WHERE id = :id")
        .param("id", id)
        .query(String.class)
        .single();
  }

  @Test
  void aRawPageTitleStoredFromTavilyReadsBackClean() {
    String repo = "https://github.com/acme/paged-" + run;
    String raw = "GitHub - acme/paged-" + run + ": An AI agent framework for everyone. · GitHub";
    UUID id = ResourceId.of(CanonicalUrl.of(repo)).getValue();
    // A row stored before titles were cleaned on write.
    store.upsert(merge(titled("live-web", repo, raw)), DataOrigin.OBSERVED, Set.of("live-web"));

    assertThat(store.findResource(id).orElseThrow().title()).isEqualTo("acme/paged-" + run);
    assertThat(store.recordsOf(id))
        .singleElement()
        .satisfies(r -> assertThat(r.hit().getTitle()).isEqualTo("acme/paged-" + run));
    assertThat(details.find(id).orElseThrow().card().getTitle()).isEqualTo("acme/paged-" + run);
    // Read-time only: the stored row is not changed.
    assertThat(storedTitle(id)).isEqualTo(raw);
  }

  /**
   * #265: the sitemap builds its slug from the title it lists, so that title must be the one the
   * resource page shows, or the sitemap address answers 301.
   */
  @Test
  void theSitemapListsARawPageTitleCleanAsTheResourcePageShowsIt() {
    String repo = "https://github.com/acme/mapped-" + run;
    String raw = "GitHub - acme/mapped-" + run + ": An AI agent framework for everyone. · GitHub";
    UUID id = ResourceId.of(CanonicalUrl.of(repo)).getValue();
    store.upsert(merge(titled("live-web", repo, raw)), DataOrigin.OBSERVED, Set.of("live-web"));
    jdbc.sql("UPDATE resource SET license = 'MIT' WHERE id = :id").param("id", id).update();

    assertThat(store.indexable(List.of("LIBRARY"), 0, 100_000))
        .filteredOn(r -> r.id().equals(id))
        .singleElement()
        .satisfies(r -> {
          assertThat(r.title()).isEqualTo("acme/mapped-" + run);
          assertThat(r.title()).isEqualTo(details.find(id).orElseThrow().card().getTitle());
        });
  }

  @Test
  void aRawPageTitleFromADiscoveryCategoryReadsBackCleanAndKeepsItsFacts() {
    String repo = "https://github.com/acme/found-" + run;
    String raw = "GitHub - acme/found-" + run + ": An AI app to run models locally. · GitHub";
    UUID id = ResourceId.of(CanonicalUrl.of(repo)).getValue();
    store.upsert(
        merge(
            titled("discovery-local-ai", repo, raw)
                .withFacts(
                    Map.of(
                        "discovery",
                        Map.of("category", "local-ai", "rank", 3),
                        "categories",
                        List.of("local-ai"),
                        // Never over a field the catalog writes itself.
                        "tags",
                        List.of("not-this")))),
        DataOrigin.OBSERVED,
        Set.of("discovery-local-ai"));

    assertThat(store.findResource(id).orElseThrow().title()).isEqualTo("acme/found-" + run);
    assertThat(
            jdbc.sql(
                    "SELECT extra -> 'discovery' ->> 'category' || ' ' || (extra -> 'tags')::text"
                        + " FROM source_record WHERE resource_id = :id")
                .param("id", id)
                .query(String.class)
                .single())
        .isEqualTo("local-ai []");
  }

  @Test
  void aTitleFromAStructuredSourceIsNeverCleaned() {
    String repo = "https://github.com/acme/named-" + run;
    String name = "GitHub - acme/named-" + run + ": a name as the source states it · GitHub";
    UUID id = ResourceId.of(CanonicalUrl.of(repo)).getValue();
    store.upsert(merge(titled("github", repo, name)), DataOrigin.OBSERVED, LIVE);

    assertThat(store.findResource(id).orElseThrow().title()).isEqualTo(name);
    assertThat(store.recordsOf(id))
        .singleElement()
        .satisfies(r -> assertThat(r.hit().getTitle()).isEqualTo(name));
  }

  @Test
  void aResourceWithAStructuredRecordKeepsItsTitleEvenWithATavilyRecord() {
    String repo = "https://github.com/acme/mixed-" + run;
    String name = "Mixed tool - acme";
    UUID id = ResourceId.of(CanonicalUrl.of(repo)).getValue();
    store.upsert(
        merge(
            titled("github", repo, name),
            titled("live-web", repo, "GitHub - acme/mixed-" + run + " · GitHub")),
        DataOrigin.OBSERVED,
        Set.of("github", "live-web"));

    assertThat(store.findResource(id).orElseThrow().title()).isEqualTo(name);
    assertThat(store.recordsOf(id))
        .filteredOn(r -> r.sourceId().equals("live-web"))
        .singleElement()
        .satisfies(r -> assertThat(r.hit().getTitle()).isEqualTo("acme/mixed-" + run));
  }

  @Test
  void labelsFollowWhatTheSourceSaysNow() {
    String repo = "https://github.com/acme/labels-" + run;
    UUID id = ResourceId.of(CanonicalUrl.of(repo)).getValue();
    store.upsert(
        merge(hit("github", repo, null, Set.of("ollama", "mcp"), List.of())),
        DataOrigin.OBSERVED,
        LIVE);
    assertThat(tags(id)).containsExactlyInAnyOrder("ollama", "mcp");

    store.upsert(
        merge(hit("github", repo, null, Set.of("mcp"), List.of())), DataOrigin.OBSERVED, LIVE);

    assertThat(tags(id)).containsExactly("mcp");
  }

  private List<String> categories(UUID id) {
    return jdbc.sql(
            "SELECT value FROM resource_label WHERE resource_id = :id AND kind = 'CATEGORY'"
                + " ORDER BY value")
        .param("id", id)
        .query(String.class)
        .list();
  }

  @Test
  void indexingAssignsCategoriesFromEveryRecordForEverySource() {
    String repo = "https://github.com/acme/coder-" + run;
    UUID id = ResourceId.of(CanonicalUrl.of(repo)).getValue();
    store.upsert(
        merge(titled("github", repo, "Acme AI coding assistant " + run)),
        DataOrigin.OBSERVED,
        LIVE);

    assertThat(categories(id)).containsExactly("coding");
    assertThat(store.findResource(id).orElseThrow().categories()).containsExactly("coding");
    assertThat(details.find(id).orElseThrow().card().getCategories()).containsExactly("coding");
  }

  @Test
  void categoriesDoNotTakeTheRoomOfTheTwentyTags() {
    String repo = "https://github.com/acme/tagged-" + run;
    UUID id = ResourceId.of(CanonicalUrl.of(repo)).getValue();
    Set<String> many = new java.util.TreeSet<>();
    for (int i = 0; i < 25; i++) {
      many.add("aa-tag-" + (char) ('a' + i));
    }
    many.add("self-hosted");
    store.upsert(merge(hit("github", repo, null, many, List.of())), DataOrigin.OBSERVED, LIVE);

    assertThat(tags(id)).hasSize(ResourceStore.MAX_TAGS).doesNotContain("self-hosted");
    assertThat(categories(id)).containsExactly("self-hosted");
  }

  @Test
  void aDiscoveryFinderGivesItsCategoryAndAHealthTagAloneDoesNot() {
    String repo = "https://github.com/acme/finder-" + run;
    UUID id = ResourceId.of(CanonicalUrl.of(repo)).getValue();
    SourceHit found =
        titled("discovery-legal", repo, "Acme " + run).withFacts(Map.of("foundBy", List.of()));
    store.upsert(
        merge(found, hit("github", repo, null, Set.of("health"), List.of())),
        DataOrigin.OBSERVED,
        Set.of("discovery-legal", "github"));

    assertThat(categories(id)).containsExactly("legal");
  }

  @Test
  void refreshingCategoriesTwiceGivesTheSameLabels() {
    String repo = "https://github.com/acme/again-" + run;
    UUID id = ResourceId.of(CanonicalUrl.of(repo)).getValue();
    store.upsert(
        merge(titled("github", repo, "A self-hosted coding assistant " + run)),
        DataOrigin.OBSERVED,
        LIVE);
    jdbc.sql("DELETE FROM resource_label WHERE resource_id = :id AND kind = 'CATEGORY'")
        .param("id", id)
        .update();

    assertThat(store.refreshCategories(List.of(id))).isEqualTo(1);
    assertThat(store.refreshCategories(List.of(id))).isEqualTo(1);
    assertThat(categories(id)).containsExactly("coding", "self-hosted");
  }

  @Test
  void onlyChangedRecordsAreReportedAndAListingTwiceIsOneRecord() {
    String repo = "https://github.com/acme/changes-" + run;
    SourceHit h = hit("github", repo, null, Set.of(), List.of());
    // The source listed the same item twice: one result holding the hit twice.
    List<MergedResult> twice = merge(h, h);
    assertThat(twice.getFirst().getHits()).hasSize(2);

    assertThat(store.upsert(twice, DataOrigin.OBSERVED, LIVE)).hasSize(1);
    assertThat(store.upsert(merge(h), DataOrigin.OBSERVED, LIVE)).isEmpty();
  }

  @Test
  void indexRowsRelayThePopularityTheirSourcesStated() {
    String repo = "https://github.com/acme/starry-" + run;
    Instant asOf = Instant.parse("2026-10-20T00:00:00Z");
    store.upsert(
        merge(
            hit(
                "github",
                repo,
                null,
                Set.of(),
                List.of(new PopularityMetric(PopularityKind.GITHUB_STARS, 3400, "github", asOf)))),
        DataOrigin.OBSERVED,
        LIVE);

    ResourceRow row =
        store.findResource(ResourceId.of(CanonicalUrl.of(repo)).getValue()).orElseThrow();

    assertThat(row.popularity())
        .containsExactly(new PopularityMetric(PopularityKind.GITHUB_STARS, 3400, "github", asOf));
  }

  @Test
  void aRecordMovingToItsRepositoryTakesTheLinksWithIt() {
    String pkg = "https://npmjs.com/package/moving-" + run;
    String repo = "https://github.com/acme/moving-" + run;
    UUID oldId = ResourceId.of(CanonicalUrl.of(pkg)).getValue();
    UUID newId = ResourceId.of(CanonicalUrl.of(repo)).getValue();
    store.upsert(merge(hit("npm", pkg, null, Set.of(), List.of())), DataOrigin.OBSERVED, LIVE);
    assertThat(store.findResource(oldId)).isPresent();

    // Later the package is found with its repository: the record now belongs to the repository's
    // resource.
    store.upsert(
        merge(
            hit("npm", pkg, repo, Set.of(), List.of()),
            hit("github", repo, null, Set.of(), List.of())),
        DataOrigin.OBSERVED,
        LIVE);

    assertThat(store.findResource(oldId)).as("orphaned observed row").isEmpty();
    assertThat(store.ownerOfRecordUrl(oldId)).contains(newId);
    var detail = details.find(oldId).orElseThrow();
    assertThat(detail.card().getId().getValue()).isEqualTo(newId);
    assertThat(details.find(newId).orElseThrow().card().getId().getValue()).isEqualTo(newId);
  }

  @Test
  void twoPackagesOfOneMonorepoAreTwoResources() {
    String repo = "https://github.com/acme/mono-" + run;
    String first = "https://npmjs.com/package/@acme/first-" + run;
    String second = "https://npmjs.com/package/@acme/second-" + run;

    store.upsert(
        merge(
            hit("npm", first, repo, Set.of(), List.of()),
            hit("npm", second, repo, Set.of(), List.of()),
            hit("github", repo, null, Set.of(), List.of())),
        DataOrigin.OBSERVED,
        LIVE);

    UUID firstId = ResourceId.of(CanonicalUrl.of(first)).getValue();
    UUID secondId = ResourceId.of(CanonicalUrl.of(second)).getValue();
    assertThat(store.recordsOf(firstId))
        .singleElement()
        .satisfies(r -> assertThat(r.sourceId()).isEqualTo("npm"));
    assertThat(store.recordsOf(secondId)).hasSize(1);
    assertThat(store.ownerOfRecordUrl(firstId)).contains(firstId);
    assertThat(store.ownerOfRecordUrl(secondId)).contains(secondId);
  }

  private UUID report(UUID resourceId) {
    UUID report = UUID.randomUUID();
    jdbc.sql(
            "INSERT INTO issue_report (id, resource_id, kind, message) VALUES (:id, :resource, 'WRONG', 'Wrong license')")
        .param("id", report)
        .param("resource", resourceId)
        .update();
    jdbc.sql(
            """
            INSERT INTO review_item (id, kind, resource_id, report_id, reason)
            VALUES (:id, 'USER_REPORT', :resource, :report, 'User report')
            """)
        .param("id", UUID.randomUUID())
        .param("resource", resourceId)
        .param("report", report)
        .update();
    return report;
  }

  private UUID reportOwner(UUID report) {
    return jdbc.sql("SELECT resource_id FROM issue_report WHERE id = :id")
        .param("id", report)
        .query(UUID.class)
        .single();
  }

  private List<UUID> reviewOwners(UUID report) {
    return jdbc.sql("SELECT resource_id FROM review_item WHERE report_id = :id")
        .param("id", report)
        .query(UUID.class)
        .list();
  }

  @Test
  void aUserReportFollowsTheRecordWhenItsResourceIsMerged() {
    String pkg = "https://npmjs.com/package/reported-" + run;
    String repo = "https://github.com/acme/reported-" + run;
    UUID oldId = ResourceId.of(CanonicalUrl.of(pkg)).getValue();
    UUID newId = ResourceId.of(CanonicalUrl.of(repo)).getValue();
    store.upsert(merge(hit("npm", pkg, null, Set.of(), List.of())), DataOrigin.OBSERVED, LIVE);
    UUID report = report(oldId);

    store.upsert(
        merge(
            hit("npm", pkg, repo, Set.of(), List.of()),
            hit("github", repo, null, Set.of(), List.of())),
        DataOrigin.OBSERVED,
        LIVE);

    assertThat(store.findResource(oldId)).as("merged observed row").isEmpty();
    assertThat(reportOwner(report)).isEqualTo(newId);
    assertThat(reviewOwners(report)).containsExactly(newId);
  }

  @Test
  void aResourceWhoseRecordsSplitKeepsItsReportsAndIsNotDeleted() {
    String pkg = "https://npmjs.com/package/split-" + run;
    String repo = "https://github.com/acme/split-" + run;
    String other = "https://npmjs.com/package/split-other-" + run;
    String secondId = "npm:split-second-" + run;
    UUID oldId = ResourceId.of(CanonicalUrl.of(pkg)).getValue();
    store.upsert(
        merge(
            hit("npm", pkg, null, Set.of(), List.of()),
            hit("npm", secondId, pkg, null, Set.of(), List.of())),
        DataOrigin.OBSERVED,
        LIVE);
    assertThat(store.recordsOf(oldId)).hasSize(2);
    UUID report = report(oldId);

    // One record now names a repository, the other a different page: no single resource took this
    // one's place.
    store.upsert(
        merge(
            hit("npm", pkg, repo, Set.of(), List.of()),
            hit("github", repo, null, Set.of(), List.of()),
            hit("npm", secondId, other, null, Set.of(), List.of())),
        DataOrigin.OBSERVED,
        LIVE);

    assertThat(store.recordsOf(oldId)).isEmpty();
    assertThat(store.findResource(oldId)).as("kept for its report").isPresent();
    assertThat(reportOwner(report)).isEqualTo(oldId);
    assertThat(reviewOwners(report)).containsExactly(oldId);
  }

  /**
   * Both arms of the match condition can be served by an index. With sequential scans priced out, a
   * condition with an unindexable arm still has to scan the table; this one is a BitmapOr of both
   * indexes. (On the small test table the full query prefers other plans, so the condition is
   * checked on its own.)
   */
  @Test
  void theMatchConditionIsServedByTheTextAndTrigramIndexes() {
    String plan =
        tx.execute(status -> {
          jdbc.sql("SET LOCAL enable_seqscan = off").update();
          return String.join(
              "\n",
              jdbc.sql("EXPLAIN SELECT r.id FROM resource r WHERE " + ResourceStore.MATCHES)
                  .param("anyq", "postgres")
                  .param("raw", "postgres")
                  .param("lang", "simple")
                  .param("similarity", ResourceStore.TITLE_SIMILARITY)
                  .query(String.class)
                  .list());
        });
    assertThat(plan)
        .contains("BitmapOr")
        .contains("resource_title_trgm_idx")
        .contains("resource_search_idx")
        .doesNotContain("Seq Scan");
    // ...and the production query is built with exactly that condition.
    assertThat(ResourceStore.searchSql(1)).contains(ResourceStore.MATCHES);
  }

  @Test
  void searchFindsATitleBySimilarityAlone() {
    String repo = "https://github.com/acme/similar-" + run;
    store.upsert(merge(hit("github", repo, null, Set.of(), List.of())), DataOrigin.OBSERVED, LIVE);

    // One character off and no common word: only the trigram branch can find it.
    List<ResourceRow> rows =
        store.search(
            run.substring(0, 7) + "x",
            List.of(),
            List.of(),
            List.of(),
            null,
            Set.of(DataOrigin.OBSERVED),
            10,
            null);

    assertThat(rows).extracting(ResourceRow::canonicalUrl).contains(repo);
  }

  private SourceHit eventFrom(String source, String url, String start, String end) {
    SourceHit h = titled(source, url, "AI Summit " + run);
    return new SourceHit(
        source,
        source + ":" + url,
        h.getUrl(),
        h.getCanonicalUrl(),
        null,
        h.getTitle(),
        null,
        ValueOrigin.SOURCE_PROVIDED,
        ResourceType.EVENT,
        Audience.BUILD_WITH_AI,
        null,
        Set.of(),
        Set.of(),
        null,
        null,
        Set.of(),
        null,
        DateFact.unknown(),
        DateFact.unknown(),
        List.of(),
        now,
        0,
        DateFact.scheduledDay(java.time.LocalDate.parse(start), source),
        DateFact.scheduledDay(java.time.LocalDate.parse(end), source));
  }

  @Test
  void stored_event_days_are_a_sources_own_when_any_states_them_not_the_ones_a_model_read() {
    String url = "https://summit-" + run + ".example/2027";
    String scout = ai.helper2.shared.WebSources.EVENTS_SCOUT;
    Map<String, SourceContext> contexts =
        Map.of("ai-deadlines", context("ai-deadlines"), scout, context(scout));
    store.upsert(
        new ResultMerger()
            .merge(List.of(eventFrom(scout, url, "2027-03-10", "2027-03-20")), contexts),
        DataOrigin.OBSERVED,
        Set.of(scout));
    assertThat(eventDays(url)).containsExactly("2027-03-10", "2027-03-20");

    store.upsert(
        new ResultMerger()
            .merge(List.of(eventFrom("ai-deadlines", url, "2027-03-12", "2027-03-14")), contexts),
        DataOrigin.OBSERVED,
        Set.of("ai-deadlines"));

    assertThat(eventDays(url)).containsExactly("2027-03-12", "2027-03-14");
  }

  private List<String> eventDays(String url) {
    return jdbc.sql(
            "SELECT to_char(event_starts_at AT TIME ZONE 'UTC', 'YYYY-MM-DD') AS s,"
                + " to_char(event_ends_at AT TIME ZONE 'UTC', 'YYYY-MM-DD') AS e"
                + " FROM resource WHERE canonical_url = :url")
        .param("url", CanonicalUrl.of(url).getValue())
        .query((rs, i) -> List.of(rs.getString("s"), rs.getString("e")))
        .single();
  }
}
