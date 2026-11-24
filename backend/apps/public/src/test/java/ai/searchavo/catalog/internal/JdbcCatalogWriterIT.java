package ai.searchavo.catalog.internal;

import static org.assertj.core.api.Assertions.assertThat;

import ai.searchavo.TestcontainersConfiguration;
import ai.searchavo.catalog.CatalogWriter;
import ai.searchavo.catalog.CategoryLabels;
import ai.searchavo.catalog.DataOrigin;
import ai.searchavo.catalog.ResourceRow;
import ai.searchavo.catalog.Resources;
import ai.searchavo.catalog.usecase.GetResourceDetail;
import ai.searchavo.search.MergedResult;
import ai.searchavo.search.PopularityKind;
import ai.searchavo.search.PopularityMetric;
import ai.searchavo.search.ResultMerger;
import ai.searchavo.search.ServedFrom;
import ai.searchavo.search.SourceContext;
import ai.searchavo.search.SourceDescriptor;
import ai.searchavo.search.SourceHit;
import ai.searchavo.search.Vitality;
import ai.searchavo.shared.Audience;
import ai.searchavo.shared.CanonicalUrl;
import ai.searchavo.shared.DateFact;
import ai.searchavo.shared.Pricing;
import ai.searchavo.shared.ResourceId;
import ai.searchavo.shared.ResourceType;
import ai.searchavo.shared.SourceMode;
import ai.searchavo.shared.ValueOrigin;
import java.math.BigDecimal;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class JdbcCatalogWriterIT {

  private static final Set<String> LIVE = Set.of("github", "npm");

  @Autowired JdbcCatalogSearch catalogSearch;
  @Autowired CategoryLabels labels;
  @Autowired Resources store;
  @Autowired CatalogWriter writer;

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
    return titled(source, url, null, title);
  }

  private SourceHit titled(String source, String url, String repo, String title) {
    return new SourceHit(
        source,
        source + ":" + url,
        URI.create(url),
        CanonicalUrl.of(url),
        CanonicalUrl.of(repo),
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
    writer.upsert(merge(titled("live-web", repo, raw)), DataOrigin.OBSERVED, Set.of("live-web"));

    assertThat(store.findResource(id).orElseThrow().title()).isEqualTo("acme/paged-" + run);
    assertThat(store.recordsOf(id))
        .singleElement()
        .satisfies(r -> assertThat(r.hit().getTitle()).isEqualTo("acme/paged-" + run));
    assertThat(details.find(id).orElseThrow().card().getTitle()).isEqualTo("acme/paged-" + run);
    assertThat(storedTitle(id)).isEqualTo(raw);
  }

  @Test
  void theSitemapListsARawPageTitleCleanAsTheResourcePageShowsIt() {
    String repo = "https://github.com/acme/mapped-" + run;
    String raw = "GitHub - acme/mapped-" + run + ": An AI agent framework for everyone. · GitHub";
    UUID id = ResourceId.of(CanonicalUrl.of(repo)).getValue();
    writer.upsert(merge(titled("live-web", repo, raw)), DataOrigin.OBSERVED, Set.of("live-web"));
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
    writer.upsert(
        merge(
            titled("discovery-local-ai", repo, raw)
                .withFacts(
                    Map.of(
                        "discovery",
                        Map.of("category", "local-ai", "rank", 3),
                        "categories",
                        List.of("local-ai"),
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
    writer.upsert(merge(titled("github", repo, name)), DataOrigin.OBSERVED, LIVE);

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
    writer.upsert(
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
    writer.upsert(
        merge(hit("github", repo, null, Set.of("ollama", "mcp"), List.of())),
        DataOrigin.OBSERVED,
        LIVE);
    assertThat(tags(id)).containsExactlyInAnyOrder("ollama", "mcp");

    writer.upsert(
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
    writer.upsert(
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
    writer.upsert(merge(hit("github", repo, null, many, List.of())), DataOrigin.OBSERVED, LIVE);

    assertThat(tags(id)).hasSize(JdbcCatalogWriter.MAX_TAGS).doesNotContain("self-hosted");
    assertThat(categories(id)).containsExactly("self-hosted");
  }

  @Test
  void aDiscoveryFinderGivesItsCategoryAndAHealthTagAloneDoesNot() {
    String repo = "https://github.com/acme/finder-" + run;
    UUID id = ResourceId.of(CanonicalUrl.of(repo)).getValue();
    SourceHit found =
        titled("discovery-legal", repo, "Acme " + run).withFacts(Map.of("foundBy", List.of()));
    writer.upsert(
        merge(found, hit("github", repo, null, Set.of("health"), List.of())),
        DataOrigin.OBSERVED,
        Set.of("discovery-legal", "github"));

    assertThat(categories(id)).containsExactly("legal");
  }

  @Test
  void refreshingCategoriesTwiceGivesTheSameLabels() {
    String repo = "https://github.com/acme/again-" + run;
    UUID id = ResourceId.of(CanonicalUrl.of(repo)).getValue();
    writer.upsert(
        merge(titled("github", repo, "A self-hosted coding assistant " + run)),
        DataOrigin.OBSERVED,
        LIVE);
    jdbc.sql("DELETE FROM resource_label WHERE resource_id = :id AND kind = 'CATEGORY'")
        .param("id", id)
        .update();

    assertThat(labels.refreshCategories(List.of(id))).isEqualTo(1);
    assertThat(labels.refreshCategories(List.of(id))).isEqualTo(1);
    assertThat(categories(id)).containsExactly("coding", "self-hosted");
  }

  private SourceHit stating(String source, String url, ResourceType type, String license) {
    return new SourceHit(
        source,
        source + ":" + url,
        URI.create(url),
        CanonicalUrl.of(url),
        null,
        "tool " + run,
        "An AI tool",
        ValueOrigin.SOURCE_PROVIDED,
        type,
        Audience.BUILD_WITH_AI,
        null,
        Set.of(),
        Set.of(),
        license,
        null,
        Set.of(),
        null,
        DateFact.unknown(),
        DateFact.unknown(),
        List.of(),
        now,
        0);
  }

  private Map<String, Object> factsOf(String url) {
    return jdbc.sql("SELECT license, type, pricing, open_source FROM resource WHERE id = :id")
        .param("id", ResourceId.of(CanonicalUrl.of(url)).getValue())
        .query()
        .singleRow();
  }

  @Test
  void aCollectorThatStatesNoLicenseKeepsTheLicenseAndTheMostSpecificTypeWins() {
    String repo = "https://github.com/acme/licensed-" + run;
    writer.upsert(
        merge(stating("npm", repo, ResourceType.LIBRARY, "MIT")), DataOrigin.INDEXED, LIVE);

    writer.upsert(
        merge(stating("github", repo, ResourceType.MCP_SERVER, null)), DataOrigin.INDEXED, LIVE);

    assertThat(factsOf(repo)).containsEntry("license", "MIT").containsEntry("type", "MCP_SERVER");

    writer.upsert(
        merge(stating("npm", repo, ResourceType.LIBRARY, "MIT")), DataOrigin.INDEXED, LIVE);

    assertThat(factsOf(repo))
        .as("the order of the runs does not matter")
        .containsEntry("type", "MCP_SERVER");
  }

  @Test
  void aLicenceNoRecordStatesAnyMoreIsCleared() {
    String repo = "https://github.com/acme/unlicensed-" + run;
    writer.upsert(
        merge(stating("npm", repo, ResourceType.LIBRARY, "MIT")), DataOrigin.INDEXED, LIVE);

    writer.upsert(
        merge(stating("npm", repo, ResourceType.LIBRARY, null)), DataOrigin.INDEXED, LIVE);

    assertThat(factsOf(repo).get("license")).isNull();
  }

  @Test
  void anObservedRecordDoesNotChangeTheLicenceOfAnIndexedResource() {
    String repo = "https://github.com/acme/indexed-licence-" + run;
    writer.upsert(
        merge(stating("npm", repo, ResourceType.LIBRARY, "MIT")), DataOrigin.INDEXED, LIVE);

    writer.upsert(
        merge(stating("github", repo, ResourceType.LIBRARY, "Apache-2.0")),
        DataOrigin.OBSERVED,
        LIVE);
    writer.upsert(
        merge(stating("npm", repo, ResourceType.LIBRARY, "MIT")), DataOrigin.INDEXED, LIVE);

    assertThat(factsOf(repo))
        .as("also after the next indexed write")
        .containsEntry("license", "MIT");
  }

  @Test
  void anObservedRecordDoesNotChangeTheTypeOfAnIndexedResource() {
    String repo = "https://github.com/acme/indexed-type-" + run;
    writer.upsert(
        merge(stating("npm", repo, ResourceType.LIBRARY, null)), DataOrigin.INDEXED, LIVE);
    writer.upsert(
        merge(stating("github", repo, ResourceType.MCP_SERVER, null)), DataOrigin.OBSERVED, LIVE);

    writer.upsert(
        merge(stating("npm", repo, ResourceType.LIBRARY, null)), DataOrigin.INDEXED, LIVE);

    assertThat(factsOf(repo)).containsEntry("type", "LIBRARY");
  }

  @Test
  void anIndexedRecordAWebSearchSawAgainStillCounts() {
    String repo = "https://github.com/acme/indexed-then-seen-" + run;
    writer.upsert(
        merge(stating("github", repo, ResourceType.MCP_SERVER, null)), DataOrigin.INDEXED, LIVE);
    writer.upsert(
        merge(stating("github", repo, ResourceType.MCP_SERVER, null)), DataOrigin.OBSERVED, LIVE);

    writer.upsert(
        merge(stating("npm", repo, ResourceType.LIBRARY, null)), DataOrigin.INDEXED, LIVE);

    assertThat(factsOf(repo)).containsEntry("type", "MCP_SERVER");
  }

  @Test
  void sourcesThatDisagreeOnTheLicenseLeaveItOpenWhicheverRanLast() {
    String repo = "https://github.com/acme/disputed-" + run;
    writer.upsert(
        merge(stating("npm", repo, ResourceType.LIBRARY, "MIT")), DataOrigin.INDEXED, LIVE);

    writer.upsert(
        merge(stating("github", repo, ResourceType.LIBRARY, "Apache-2.0")),
        DataOrigin.INDEXED,
        LIVE);

    assertThat(factsOf(repo).get("license")).isNull();
  }

  @Test
  void licensesThatDifferOnlyInCaseAgree() {
    String repo = "https://github.com/acme/same-licence-" + run;
    writer.upsert(
        merge(stating("npm", repo, ResourceType.LIBRARY, "CC-BY-4.0")), DataOrigin.INDEXED, LIVE);

    writer.upsert(
        merge(stating("github", repo, ResourceType.LIBRARY, "cc-by-4.0")),
        DataOrigin.INDEXED,
        LIVE);

    assertThat((String) factsOf(repo).get("license")).isEqualToIgnoringCase("CC-BY-4.0");
  }

  @Test
  void twoSpellingsOfOneLicenceAreOneLicenceWhicheverRanLast() {
    String repo = "https://github.com/acme/spelled-" + run;
    String reversed = repo + "-reversed";
    writer.upsert(
        merge(stating("npm", repo, ResourceType.LIBRARY, "MIT")), DataOrigin.INDEXED, LIVE);
    writer.upsert(
        merge(stating("github", repo, ResourceType.LIBRARY, "MIT License")),
        DataOrigin.INDEXED,
        LIVE);
    writer.upsert(
        merge(stating("github", reversed, ResourceType.LIBRARY, "MIT License")),
        DataOrigin.INDEXED,
        LIVE);
    writer.upsert(
        merge(stating("npm", reversed, ResourceType.LIBRARY, "MIT")), DataOrigin.INDEXED, LIVE);

    assertThat(factsOf(repo)).containsEntry("license", "MIT");
    assertThat(factsOf(reversed)).containsEntry("license", "MIT");
  }

  private List<String> recordedLicences(String url) {
    return jdbc.sql("SELECT license FROM source_record WHERE resource_id = :id")
        .param("id", ResourceId.of(CanonicalUrl.of(url)).getValue())
        .query((rs, i) -> String.valueOf(rs.getString("license")))
        .list();
  }

  @Test
  void aLicenceIsStoredInItsShortSpelling() {
    String repo = "https://github.com/acme/spelled-out-" + run;
    writer.upsert(
        merge(stating("github", repo, ResourceType.LIBRARY, "Apache License 2.0")),
        DataOrigin.INDEXED,
        LIVE);

    assertThat(recordedLicences(repo)).containsExactly("Apache-2.0");
    assertThat(factsOf(repo)).containsEntry("license", "Apache-2.0");
  }

  @Test
  void aLicenceTheSourceDoesNotAssertIsNoLicence() {
    String repo = "https://github.com/acme/not-asserted-" + run;
    writer.upsert(
        merge(stating("github", repo, ResourceType.LIBRARY, "NOASSERTION")),
        DataOrigin.INDEXED,
        LIVE);

    assertThat(recordedLicences(repo)).containsExactly("null");
    assertThat(factsOf(repo).get("license")).isNull();
  }

  private SourceHit offering(String source, String url, Pricing pricing, Boolean openSource) {
    return new SourceHit(
        source,
        source + ":" + url,
        URI.create(url),
        CanonicalUrl.of(url),
        null,
        "tool " + run,
        "An AI tool",
        ValueOrigin.SOURCE_PROVIDED,
        ResourceType.LIBRARY,
        Audience.BUILD_WITH_AI,
        null,
        Set.of(),
        Set.of(),
        null,
        pricing,
        Set.of(),
        openSource,
        DateFact.unknown(),
        DateFact.unknown(),
        List.of(),
        now,
        0);
  }

  private void indexed(SourceHit hit) {
    writer.upsert(merge(hit), DataOrigin.INDEXED, LIVE);
  }

  @Test
  void sourcesThatDisagreeOnThePricingOrOnOpenSourceLeaveItOpenWhicheverRanLast() {
    String repo = "https://github.com/acme/disputed-offer-" + run;
    String reversed = repo + "-reversed";
    indexed(offering("npm", repo, Pricing.FREE, true));
    indexed(offering("github", repo, Pricing.PAID, false));
    indexed(offering("github", reversed, Pricing.PAID, false));
    indexed(offering("npm", reversed, Pricing.FREE, true));

    assertThat(factsOf(repo).get("pricing")).isNull();
    assertThat(factsOf(repo).get("open_source")).isNull();
    assertThat(factsOf(reversed).get("pricing")).isNull();
    assertThat(factsOf(reversed).get("open_source")).isNull();
  }

  @Test
  void sourcesThatAgreeOnThePricingAndOnOpenSourceGiveThemToTheResource() {
    String repo = "https://github.com/acme/agreed-offer-" + run;
    indexed(offering("npm", repo, Pricing.FREE, true));
    indexed(offering("github", repo, Pricing.FREE, true));

    assertThat(factsOf(repo)).containsEntry("pricing", "FREE").containsEntry("open_source", true);
  }

  @Test
  void aSourceThatStatesNoPricingAndNoOpenSourceKeepsWhatAnotherSourceStates() {
    String repo = "https://github.com/acme/one-offer-" + run;
    indexed(offering("npm", repo, Pricing.FREEMIUM, false));
    indexed(offering("github", repo, null, null));

    assertThat(factsOf(repo))
        .containsEntry("pricing", "FREEMIUM")
        .containsEntry("open_source", false);
  }

  @Test
  void aPricingAndAnOpenSourceFlagNoRecordStatesAnyMoreAreCleared() {
    String repo = "https://github.com/acme/withdrawn-offer-" + run;
    indexed(offering("npm", repo, Pricing.FREE, true));
    indexed(offering("github", repo, null, null));

    indexed(offering("npm", repo, null, null));

    assertThat(factsOf(repo).get("pricing")).isNull();
    assertThat(factsOf(repo).get("open_source")).isNull();
  }

  @Test
  void anObservedRecordDoesNotChangeThePricingOrOpenSourceOfAnIndexedResource() {
    String repo = "https://github.com/acme/indexed-offer-" + run;
    indexed(offering("npm", repo, Pricing.FREE, true));

    writer.upsert(merge(offering("github", repo, Pricing.PAID, false)), DataOrigin.OBSERVED, LIVE);
    indexed(offering("npm", repo, Pricing.FREE, true));

    assertThat(factsOf(repo))
        .as("also after the next indexed write")
        .containsEntry("pricing", "FREE")
        .containsEntry("open_source", true);
  }

  @Test
  void aCuratedPricingStaysWhenTheSourcesDisagree() {
    String repo = "https://github.com/acme/curated-offer-" + run;
    indexed(offering("npm", repo, Pricing.FREE, true));
    jdbc.sql(
            "UPDATE resource SET pricing = 'FREEMIUM', open_source = true,"
                + " curated_fields = ARRAY['pricing', 'openSource'] WHERE id = :id")
        .param("id", ResourceId.of(CanonicalUrl.of(repo)).getValue())
        .update();

    indexed(offering("github", repo, Pricing.PAID, false));

    assertThat(factsOf(repo))
        .containsEntry("pricing", "FREEMIUM")
        .containsEntry("open_source", true);
  }

  @Test
  void aCuratedDescriptionLanguageAndPublishedDateStayWhenASourceRefreshes() {
    String repo = "https://github.com/acme/curated-text-" + run;
    indexed(withText("npm", repo, "An AI tool for notes.", Instant.parse("2024-01-10T00:00:00Z")));
    curate(repo, "AI_SUMMARY");

    indexed(
        withText(
            "github",
            repo,
            "A completely different description from the source.",
            Instant.parse("2023-05-05T00:00:00Z")));

    assertThat(textOf(repo))
        .containsEntry("description", "Ein Werkzeug, das Notizen schreibt.")
        .containsEntry("description_origin", "AI_SUMMARY")
        .containsEntry("language", "de")
        .containsEntry("published", "2025-03-02")
        .containsEntry("published_precision", "DAY");
  }

  @Test
  void aDescriptionTheAdminWroteStaysCuratedWhenASourceRefreshes() {
    String repo = "https://github.com/acme/curated-by-hand-" + run;
    indexed(withText("npm", repo, "An AI tool for notes.", Instant.parse("2024-01-10T00:00:00Z")));
    curate(repo, "CURATED");

    indexed(withText("npm", repo, "Another text the source sends.", null));

    assertThat(textOf(repo))
        .containsEntry("description", "Ein Werkzeug, das Notizen schreibt.")
        .containsEntry("description_origin", "CURATED");
  }

  private SourceHit withText(String source, String url, String description, Instant published) {
    return new SourceHit(
        source,
        source + ":" + url,
        URI.create(url),
        CanonicalUrl.of(url),
        null,
        "tool " + run,
        description,
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
        published == null ? DateFact.unknown() : DateFact.fromSource(published, source, now),
        DateFact.unknown(),
        List.of(),
        now,
        0);
  }

  private void curate(String url, String origin) {
    jdbc.sql(
            """
            UPDATE resource SET description = 'Ein Werkzeug, das Notizen schreibt.',
                   description_origin = :origin, language = 'de',
                   published_at = '2025-03-02T00:00:00Z', published_precision = 'DAY',
                   published_source_id = NULL,
                   curated_fields = ARRAY['description', 'language', 'publishedAt']
             WHERE id = :id
            """)
        .param("origin", origin)
        .param("id", ResourceId.of(CanonicalUrl.of(url)).getValue())
        .update();
  }

  private Map<String, Object> textOf(String url) {
    return jdbc.sql(
            """
            SELECT description, description_origin, language,
                   to_char(published_at AT TIME ZONE 'UTC', 'YYYY-MM-DD') AS published, published_precision
            FROM resource WHERE id = :id
            """)
        .param("id", ResourceId.of(CanonicalUrl.of(url)).getValue())
        .query()
        .singleRow();
  }

  @Test
  void onlyChangedRecordsAreReportedAndAListingTwiceIsOneRecord() {
    String repo = "https://github.com/acme/changes-" + run;
    SourceHit h = hit("github", repo, null, Set.of(), List.of());
    List<MergedResult> twice = merge(h, h);
    assertThat(twice.getFirst().getHits()).hasSize(2);

    assertThat(writer.upsert(twice, DataOrigin.OBSERVED, LIVE)).hasSize(1);
    assertThat(writer.upsert(merge(h), DataOrigin.OBSERVED, LIVE)).isEmpty();
  }

  @Test
  void indexRowsRelayThePopularityTheirSourcesStated() {
    String repo = "https://github.com/acme/starry-" + run;
    Instant asOf = Instant.parse("2026-10-20T00:00:00Z");
    writer.upsert(
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

  private static PopularityMetric count(PopularityKind kind, long value, Instant asOf) {
    return new PopularityMetric(kind, value, "github", asOf);
  }

  private static PopularityMetric stars(long value, Instant asOf) {
    return count(PopularityKind.GITHUB_STARS, value, asOf);
  }

  private void observed(String repo, PopularityMetric... counts) {
    writer.upsert(
        merge(hit("github", repo, null, Set.of(), List.of(counts))), DataOrigin.OBSERVED, LIVE);
  }

  private List<PopularityMetric> countsOf(String repo) {
    return store
        .findResource(ResourceId.of(CanonicalUrl.of(repo)).getValue())
        .orElseThrow()
        .popularity();
  }

  private BigDecimal popularityOfTheResourceOf(String repo) {
    return jdbc.sql(
            """
            SELECT r.popularity FROM resource r JOIN source_record sr ON sr.resource_id = r.id
            WHERE sr.source_id = 'github' AND sr.external_id = :id
            """)
        .param("id", "github:" + repo)
        .query(BigDecimal.class)
        .single();
  }

  // S2.11.6, ADR-0020 §7 (newest wins per kind of count): a hit that states no stars keeps the
  // stars
  // the repository sweep stored, and the popularity of the resource stays (ADR-0025 §3).
  @Test
  void aHitThatStatesNoStarsKeepsTheStoredStars() {
    String repo = "https://github.com/acme/kept-stars-" + run;
    Instant sweptAt = Instant.parse("2026-11-01T00:00:00Z");
    observed(repo, stars(1200, sweptAt));
    BigDecimal before = popularityOfTheResourceOf(repo);

    observed(repo);

    assertThat(countsOf(repo)).containsExactly(stars(1200, sweptAt));
    assertThat(before).isGreaterThan(BigDecimal.ZERO);
    assertThat(popularityOfTheResourceOf(repo)).isEqualByComparingTo(before);
  }

  // S2.11.6, ADR-0020 §7: a count read before the stored one (a hit served from the search cache)
  // neither lowers nor raises it.
  @Test
  void aCountReadBeforeTheStoredOneDoesNotTakeItsPlace() {
    String repo = "https://github.com/acme/cached-stars-" + run;
    Instant sweptAt = Instant.parse("2026-11-10T00:00:00Z");
    Instant cachedAt = Instant.parse("2026-11-09T23:59:59.500Z");
    observed(repo, stars(5000, sweptAt));
    BigDecimal before = popularityOfTheResourceOf(repo);

    observed(repo, stars(100, cachedAt));
    observed(repo, stars(9000, cachedAt));

    assertThat(countsOf(repo)).containsExactly(stars(5000, sweptAt));
    assertThat(popularityOfTheResourceOf(repo)).isEqualByComparingTo(before);
  }

  // S2.11.6, ADR-0020 §7: a count read after the stored one takes its place, and so does one read
  // at the same time; the popularity of the resource follows (ADR-0025 §3).
  @Test
  void aCountReadAfterTheStoredOneOrAtTheSameTimeTakesItsPlace() {
    String repo = "https://github.com/acme/newer-stars-" + run;
    Instant first = Instant.parse("2026-11-10T00:00:00Z");
    Instant later = Instant.parse("2026-11-10T00:00:00.250Z");
    observed(repo, stars(100, first));
    BigDecimal before = popularityOfTheResourceOf(repo);

    observed(repo, stars(200, first));
    assertThat(countsOf(repo)).containsExactly(stars(200, first));

    observed(repo, stars(50_000, later));
    assertThat(countsOf(repo)).containsExactly(stars(50_000, later));
    assertThat(popularityOfTheResourceOf(repo)).isGreaterThan(before);
  }

  // S2.11.6, ADR-0020 §7: the counts of a record are merged entry by entry, each kind by its own
  // date; a kind the hit does not state is untouched.
  @Test
  void theCountsOfARecordAreMergedKindByKind() {
    String repo = "https://github.com/acme/merged-counts-" + run;
    Instant old = Instant.parse("2026-10-01T00:00:00Z");
    Instant recent = Instant.parse("2026-11-15T00:00:00Z");
    Instant between = Instant.parse("2026-11-01T00:00:00Z");
    observed(repo, stars(10, old), count(PopularityKind.NPM_DOWNLOADS_WEEKLY, 700, recent));

    observed(
        repo,
        stars(20, between),
        count(PopularityKind.NPM_DOWNLOADS_WEEKLY, 1, between),
        count(PopularityKind.HF_LIKES, 3, between));

    assertThat(countsOf(repo))
        .containsExactlyInAnyOrder(
            stars(20, between),
            count(PopularityKind.NPM_DOWNLOADS_WEEKLY, 700, recent),
            count(PopularityKind.HF_LIKES, 3, between));
  }

  // S2.11.6, ADR-0020 §7: a stored count without a date that can be read is older than a dated one.
  @Test
  void aStoredCountWithoutAReadableDateGivesWayToADatedOne() {
    String undated = "https://github.com/acme/undated-stars-" + run;
    String misdated = "https://github.com/acme/misdated-stars-" + run;
    Instant longAgo = Instant.parse("2020-01-01T00:00:00Z");
    observed(undated);
    observed(misdated);
    storedCounts(undated, "[{\"kind\": \"GITHUB_STARS\", \"value\": 7}]");
    storedCounts(
        misdated, "[{\"kind\": \"GITHUB_STARS\", \"value\": 7, \"asOf\": \"not a date\"}]");

    observed(undated, stars(5, longAgo));
    observed(misdated, stars(5, longAgo));

    assertThat(countsOf(undated)).containsExactly(stars(5, longAgo));
    assertThat(countsOf(misdated)).containsExactly(stars(5, longAgo));
  }

  private void storedCounts(String repo, String counts) {
    jdbc.sql(
            """
            UPDATE source_record SET extra = jsonb_set(extra, '{popularity}', CAST(:counts AS jsonb))
            WHERE source_id = 'github' AND external_id = :id
            """)
        .param("counts", counts)
        .param("id", "github:" + repo)
        .update();
  }

  private String vitalityOf(String externalId) {
    return jdbc.sql(
            "SELECT extra ->> 'vitality' FROM source_record WHERE source_id = 'npm' AND external_id = :id")
        .param("id", externalId)
        .query(String.class)
        .optional()
        .orElse(null);
  }

  private void checkedArchived(String externalId) {
    jdbc.sql(
            """
            UPDATE source_record
            SET extra = jsonb_set(extra, '{vitality}', '{"archived":true,"checkedAt":"2026-11-01T00:00:00Z"}')
            WHERE source_id = 'npm' AND external_id = :id
            """)
        .param("id", externalId)
        .update();
  }

  private SourceHit repository(String url, Vitality stated) {
    return hit("github", url, null, Set.of(), List.of()).withVitality(stated);
  }

  private void checked(String url, boolean archived, Instant at) {
    jdbc.sql(
            """
            UPDATE source_record
            SET extra = jsonb_set(extra, '{vitality}', coalesce(extra -> 'vitality', '{}'::jsonb)
                || jsonb_build_object('archived', :archived, 'checkedAt', :at::text))
            WHERE source_id = 'github' AND external_id = :id
            """)
        .param("archived", archived)
        .param("at", at.toString())
        .param("id", "github:" + url)
        .update();
  }

  private Vitality statedInTheIndex(String url) {
    ResourceRow row =
        store.findResource(ResourceId.of(CanonicalUrl.of(url)).getValue()).orElseThrow();
    return catalogSearch.statedVitality(List.of(row)).getOrDefault(row.id(), Vitality.UNKNOWN);
  }

  private String checkedAt(String url) {
    return jdbc.sql(
            """
            SELECT extra -> 'vitality' ->> 'checkedAt' FROM source_record
            WHERE source_id = 'github' AND external_id = :id
            """)
        .param("id", "github:" + url)
        .query(String.class)
        .optional()
        .orElse(null);
  }

  @Test
  void aRecordStatedDeprecatedComesBackDeprecatedWithEveryFactItsSourceStated() {
    String url = "https://github.com/acme/deprecated-" + run;

    writer.upsert(
        merge(repository(url, new Vitality(false, true, true, false))), DataOrigin.INDEXED, LIVE);

    assertThat(statedInTheIndex(url)).isEqualTo(new Vitality(false, true, true, false));
  }

  @Test
  void aRecordWhoseSourceStatesNoVitalityStatesNone() {
    String url = "https://github.com/acme/silent-" + run;

    writer.upsert(merge(repository(url, Vitality.UNKNOWN)), DataOrigin.INDEXED, LIVE);

    assertThat(statedInTheIndex(url)).isEqualTo(Vitality.UNKNOWN);
  }

  @Test
  void aNewerCheckOfTheRepositoryIsNotLostByAStatementMadeBeforeIt() {
    String url = "https://github.com/acme/archived-since-" + run;
    writer.upsert(merge(repository(url, new Vitality(false, true))), DataOrigin.INDEXED, LIVE);
    Instant afterTheStatement = now.plus(1, ChronoUnit.HOURS);
    checked(url, true, afterTheStatement);

    writer.upsert(merge(repository(url, new Vitality(false, true))), DataOrigin.INDEXED, LIVE);

    assertThat(statedInTheIndex(url)).isEqualTo(new Vitality(true, true));
    assertThat(checkedAt(url)).isEqualTo(afterTheStatement.toString());
  }

  @Test
  void aRepositoryStoredAsGoneIsArchivedInTheIndexUntilItsSourceStatesItAgain() {
    String url = "https://github.com/acme/gone-" + run;
    writer.upsert(merge(repository(url, Vitality.UNKNOWN)), DataOrigin.INDEXED, LIVE);
    Instant before = now.minus(1, ChronoUnit.DAYS);
    jdbc.sql(
            """
            UPDATE source_record
            SET extra = jsonb_set(extra, '{vitality}', jsonb_build_object(
                'gone', true, 'notFoundAt', :at::text, 'notFoundVisits', 2, 'checkedAt', :at::text,
                'visitedAt', :at::text))
            WHERE source_id = 'github' AND external_id = :id
            """)
        .param("at", before.toString())
        .param("id", "github:" + url)
        .update();
    assertThat(statedInTheIndex(url)).isEqualTo(new Vitality(true));

    writer.upsert(merge(repository(url, new Vitality(false, false))), DataOrigin.INDEXED, LIVE);

    assertThat(statedInTheIndex(url)).isEqualTo(new Vitality(false, false));
    // UC-030 BR-012: a source that states the repository again starts the count of visits again.
    assertThat(
            jdbc.sql(
                    """
                    SELECT extra -> 'vitality' ->> 'notFoundVisits' FROM source_record
                    WHERE source_id = 'github' AND external_id = :id
                    """)
                .param("id", "github:" + url)
                .query(String.class)
                .optional())
        .isEmpty();
  }

  @Test
  void aNewerStatementOfTheSourceReplacesAnOlderCheckOfTheRepository() {
    String url = "https://github.com/acme/unarchived-" + run;
    writer.upsert(merge(repository(url, Vitality.UNKNOWN)), DataOrigin.INDEXED, LIVE);
    checked(url, true, now.minus(1, ChronoUnit.DAYS));

    writer.upsert(merge(repository(url, new Vitality(false, false))), DataOrigin.INDEXED, LIVE);

    assertThat(statedInTheIndex(url)).isEqualTo(new Vitality(false, false));
    assertThat(checkedAt(url)).isNull();
  }

  @Test
  void aStatementThatSaysNothingOfArchivedKeepsTheCheckOfTheRepository() {
    String url = "https://github.com/acme/deprecated-and-archived-" + run;
    writer.upsert(merge(repository(url, Vitality.UNKNOWN)), DataOrigin.INDEXED, LIVE);
    Instant earlier = now.minus(1, ChronoUnit.DAYS);
    checked(url, true, earlier);

    writer.upsert(merge(repository(url, new Vitality(null, null, true))), DataOrigin.INDEXED, LIVE);

    assertThat(statedInTheIndex(url)).isEqualTo(new Vitality(true, null, true));
    assertThat(checkedAt(url)).isEqualTo(earlier.toString());
  }

  @Test
  void aCheckOfTheRepositoryStaysWhenTheSourceListsTheSameRepositoryAgain() {
    String pkg = "https://npmjs.com/package/still-linked-" + run;
    String repo = "https://github.com/acme/still-linked-" + run;
    writer.upsert(merge(hit("npm", pkg, repo, Set.of(), List.of())), DataOrigin.OBSERVED, LIVE);
    checkedArchived("npm:" + pkg);

    writer.upsert(
        merge(hit("npm", pkg, repo, Set.of("seen-again"), List.of())), DataOrigin.OBSERVED, LIVE);

    assertThat(vitalityOf("npm:" + pkg)).contains("\"archived\": true");
  }

  @Test
  void aCheckOfTheRepositoryIsDroppedWhenThePackageLinksAnotherRepository() {
    String pkg = "https://npmjs.com/package/relinked-" + run;
    writer.upsert(
        merge(hit("npm", pkg, "https://github.com/acme/old-home-" + run, Set.of(), List.of())),
        DataOrigin.OBSERVED,
        LIVE);
    checkedArchived("npm:" + pkg);

    writer.upsert(
        merge(hit("npm", pkg, "https://github.com/acme/new-home-" + run, Set.of(), List.of())),
        DataOrigin.OBSERVED,
        LIVE);

    assertThat(vitalityOf("npm:" + pkg)).isNull();
  }

  @Test
  void aRecordMovingToItsRepositoryTakesTheLinksWithIt() {
    String pkg = "https://npmjs.com/package/moving-" + run;
    String repo = "https://github.com/acme/moving-" + run;
    UUID oldId = ResourceId.of(CanonicalUrl.of(pkg)).getValue();
    UUID newId = ResourceId.of(CanonicalUrl.of(repo)).getValue();
    writer.upsert(merge(hit("npm", pkg, null, Set.of(), List.of())), DataOrigin.OBSERVED, LIVE);
    assertThat(store.findResource(oldId)).isPresent();

    writer.upsert(
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

  private Instant firstDiscovered(UUID id) {
    return jdbc.sql("SELECT first_discovered_at FROM resource WHERE id = :id")
        .param("id", id)
        .query(Instant.class)
        .single();
  }

  @Test
  void aRecordThatJoinedItsRepositoryStaysThereWhenASearchSeesItAlone() {
    String pkg = "https://npmjs.com/package/sticky-" + run;
    String repo = "https://github.com/acme/sticky-" + run;
    UUID pkgId = ResourceId.of(CanonicalUrl.of(pkg)).getValue();
    UUID repoId = ResourceId.of(CanonicalUrl.of(repo)).getValue();
    writer.upsert(
        merge(
            hit("npm", pkg, repo, Set.of(), List.of()),
            hit("github", repo, null, Set.of(), List.of())),
        DataOrigin.OBSERVED,
        LIVE);
    Instant discovered = firstDiscovered(repoId);

    writer.upsert(merge(hit("npm", pkg, repo, Set.of(), List.of())), DataOrigin.OBSERVED, LIVE);

    assertThat(store.findResource(pkgId)).as("no resource of its own again").isEmpty();
    assertThat(store.recordsOf(repoId)).hasSize(2);
    assertThat(firstDiscovered(repoId)).isEqualTo(discovered);
  }

  private String originOf(UUID id) {
    return jdbc.sql("SELECT data_origin FROM resource WHERE id = :id")
        .param("id", id)
        .query(String.class)
        .single();
  }

  @Test
  void aRepositoryBecomesIndexedWhenScoutIndexesThePackageThatJoinedIt() {
    String pkg = "https://npmjs.com/package/promoted-" + run;
    String repo = "https://github.com/acme/promoted-" + run;
    UUID repoId = ResourceId.of(CanonicalUrl.of(repo)).getValue();
    writer.upsert(
        merge(
            hit("npm", pkg, repo, Set.of(), List.of()),
            hit("github", repo, null, Set.of(), List.of())),
        DataOrigin.OBSERVED,
        LIVE);
    String title = storedTitle(repoId);

    writer.upsert(merge(hit("npm", pkg, repo, Set.of(), List.of())), DataOrigin.INDEXED, LIVE);

    assertThat(originOf(repoId)).isEqualTo("INDEXED");
    assertThat(storedTitle(repoId)).isEqualTo(title);
    assertThat(store.recordsOf(repoId)).hasSize(2);
  }

  @Test
  void anIndexedRepositoryStaysIndexedWhenASearchSeesItsPackageAlone() {
    String pkg = "https://npmjs.com/package/steady-" + run;
    String repo = "https://github.com/acme/steady-" + run;
    UUID repoId = ResourceId.of(CanonicalUrl.of(repo)).getValue();
    writer.upsert(
        merge(
            hit("npm", pkg, repo, Set.of(), List.of()),
            hit("github", repo, null, Set.of(), List.of())),
        DataOrigin.INDEXED,
        LIVE);

    writer.upsert(merge(hit("npm", pkg, repo, Set.of(), List.of())), DataOrigin.OBSERVED, LIVE);

    assertThat(originOf(repoId)).isEqualTo("INDEXED");
  }

  private String storedUrl(UUID id) {
    return jdbc.sql("SELECT url FROM resource WHERE id = :id")
        .param("id", id)
        .query(String.class)
        .single();
  }

  @Test
  void aPackageSeenAloneLeavesTheTitleAndLinkOfItsRepository() {
    String pkg = "https://npmjs.com/package/shown-" + run;
    String repo = "https://github.com/acme/shown-" + run;
    UUID repoId = ResourceId.of(CanonicalUrl.of(repo)).getValue();
    writer.upsert(merge(titled("github", repo, "The repository")), DataOrigin.OBSERVED, LIVE);

    writer.upsert(merge(titled("npm", pkg, repo, "the-package")), DataOrigin.INDEXED, LIVE);

    assertThat(storedTitle(repoId)).isEqualTo("The repository");
    assertThat(storedUrl(repoId)).isEqualTo(repo);
    assertThat(originOf(repoId)).isEqualTo("INDEXED");
  }

  @Test
  void aRepositoryArrivingAfterItsPackageGivesTheResourceItsTitleAndLink() {
    String pkg = "https://npmjs.com/package/late-" + run;
    String repo = "https://github.com/acme/late-" + run;
    UUID repoId = ResourceId.of(CanonicalUrl.of(repo)).getValue();
    writer.upsert(merge(titled("npm", pkg, repo, "the-package")), DataOrigin.OBSERVED, LIVE);
    assertThat(storedTitle(repoId)).isEqualTo("the-package");

    writer.upsert(merge(titled("github", repo, "The repository")), DataOrigin.OBSERVED, LIVE);

    assertThat(storedTitle(repoId)).isEqualTo("The repository");
    assertThat(storedUrl(repoId)).isEqualTo(repo);
  }

  @Test
  void thePackagesOfOneRepositoryAreRecordsOfTheRepository_inAnyOrderOfArrival() {
    String repo = "https://github.com/acme/mono-" + run;
    String first = "https://npmjs.com/package/@acme/first-" + run;
    String second = "https://npmjs.com/package/@acme/second-" + run;
    UUID repository = ResourceId.of(CanonicalUrl.of(repo)).getValue();

    writer.upsert(merge(hit("npm", first, repo, Set.of(), List.of())), DataOrigin.OBSERVED, LIVE);
    assertThat(store.recordsOf(repository)).hasSize(1);

    writer.upsert(merge(hit("github", repo, null, Set.of(), List.of())), DataOrigin.OBSERVED, LIVE);
    writer.upsert(merge(hit("npm", second, repo, Set.of(), List.of())), DataOrigin.OBSERVED, LIVE);

    assertThat(store.recordsOf(repository))
        .extracting(r -> r.sourceId())
        .containsExactlyInAnyOrder("github", "npm", "npm");
    assertThat(store.recordsOf(ResourceId.of(CanonicalUrl.of(first)).getValue())).isEmpty();
    assertThat(store.recordsOf(ResourceId.of(CanonicalUrl.of(second)).getValue())).isEmpty();
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
    writer.upsert(merge(hit("npm", pkg, null, Set.of(), List.of())), DataOrigin.OBSERVED, LIVE);
    UUID report = report(oldId);

    writer.upsert(
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
    writer.upsert(
        merge(
            hit("npm", pkg, null, Set.of(), List.of()),
            hit("npm", secondId, pkg, null, Set.of(), List.of())),
        DataOrigin.OBSERVED,
        LIVE);
    assertThat(store.recordsOf(oldId)).hasSize(2);
    UUID report = report(oldId);

    writer.upsert(
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

  @Test
  void theMatchConditionIsServedByTheTextAndTrigramIndexes() {
    String plan =
        tx.execute(status -> {
          jdbc.sql("SET LOCAL enable_seqscan = off").update();
          return String.join(
              "\n",
              jdbc.sql("EXPLAIN SELECT r.id FROM resource r WHERE " + JdbcCatalogSearch.MATCHES)
                  .param("anyq", "postgres")
                  .param("raw", "postgres")
                  .param("lang", "simple")
                  .param("similarity", JdbcCatalogSearch.TITLE_SIMILARITY)
                  .query(String.class)
                  .list());
        });
    assertThat(plan)
        .contains("BitmapOr")
        .contains("resource_title_trgm_idx")
        .contains("resource_search_idx")
        .doesNotContain("Seq Scan");
    assertThat(JdbcCatalogSearch.searchSql(1)).contains(JdbcCatalogSearch.MATCHES);
  }

  private SourceHit described(
      String url, String title, String description, List<PopularityMetric> popularity) {
    return new SourceHit(
        "github",
        "github:" + url,
        URI.create(url),
        CanonicalUrl.of(url),
        null,
        title,
        description,
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
        popularity,
        now,
        0);
  }

  @Test
  void aTranslatedQueryKeepsTheEveryWordBonusWhenExpansionsAreAdded() {
    String fit = "https://github.com/acme/translator-" + run;
    String popular = "https://github.com/acme/translation-hub-" + run;
    writer.upsert(
        merge(
            described(fit, "Translator " + run, "An app to translate texts " + run, List.of()),
            described(
                popular,
                "Translation hub " + run,
                "Translation and writing datasets " + run,
                List.of(new PopularityMetric(PopularityKind.GITHUB_STARS, 50_000, "github", now)))),
        DataOrigin.OBSERVED,
        LIVE);
    settled(fit, popular);
    String english = "app to translate texts " + run;

    List<String> found =
        catalogSearch
            .search(
                "app per tradurre testi " + run,
                List.of(english, "app per tradurre testi " + run),
                List.of(english, "translation", "writing"),
                List.of(),
                "it",
                Set.of(DataOrigin.OBSERVED),
                10,
                null)
            .stream()
            .map(ResourceRow::canonicalUrl)
            .filter(u -> u.equals(fit) || u.equals(popular))
            .toList();

    assertThat(found).containsExactly(fit, popular);
  }

  @Test
  void searchFindsATitleBySimilarityAlone() {
    String repo = "https://github.com/acme/similar-" + run;
    writer.upsert(merge(hit("github", repo, null, Set.of(), List.of())), DataOrigin.OBSERVED, LIVE);
    settled(repo);

    List<ResourceRow> rows =
        catalogSearch.search(
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

  private void settled(String... canonicalUrls) {
    jdbc.sql(
            """
            UPDATE resource SET first_discovered_at = now() - interval '25 hours'
            WHERE canonical_url = ANY(:urls)
            """)
        .param("urls", canonicalUrls)
        .update();
  }

  private SourceHit eventFrom(String source, String url, String start, String end) {
    return eventFrom(source, url, null, start, end);
  }

  private SourceHit eventFrom(
      String source, String url, @Nullable String description, String start, String end) {
    SourceHit h = titled(source, url, "AI Summit " + run);
    return new SourceHit(
        source,
        source + ":" + url,
        h.getUrl(),
        h.getCanonicalUrl(),
        null,
        h.getTitle(),
        description,
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
    String scout = ai.searchavo.shared.WebSources.EVENTS_SCOUT;
    Map<String, SourceContext> contexts =
        Map.of("ai-deadlines", context("ai-deadlines"), scout, context(scout));
    writer.upsert(
        new ResultMerger()
            .merge(List.of(eventFrom(scout, url, "2027-03-10", "2027-03-20")), contexts),
        DataOrigin.OBSERVED,
        Set.of(scout));
    assertThat(eventDays(url)).containsExactly("2027-03-10", "2027-03-20");

    writer.upsert(
        new ResultMerger()
            .merge(List.of(eventFrom("ai-deadlines", url, "2027-03-12", "2027-03-14")), contexts),
        DataOrigin.OBSERVED,
        Set.of("ai-deadlines"));

    assertThat(eventDays(url)).containsExactly("2027-03-12", "2027-03-14");
  }

  @Test
  void
      the_card_says_whether_the_event_days_were_read_by_ai_after_a_round_trip_through_the_catalog() {
    String aiOnly = "https://hack-" + run + ".example/2027";
    String stated = "https://conf-" + run + ".example/2027";
    String scout = ai.searchavo.shared.WebSources.EVENTS_SCOUT;
    Map<String, SourceContext> contexts =
        Map.of("ai-deadlines", context("ai-deadlines"), scout, context(scout));
    writer.upsert(
        new ResultMerger()
            .merge(List.of(eventFrom(scout, aiOnly, "2027-03-12", "2027-03-14")), contexts),
        DataOrigin.OBSERVED,
        Set.of(scout));
    writer.upsert(
        new ResultMerger()
            .merge(
                List.of(
                    eventFrom(scout, stated, "2027-04-01", "2027-04-02"),
                    eventFrom("ai-deadlines", stated, "2027-04-02", "2027-04-03")),
                contexts),
        DataOrigin.OBSERVED,
        Set.of(scout, "ai-deadlines"));

    var aiCard =
        ai.searchavo.search.web.CardMapper.card(
            details.find(resourceId(aiOnly)).orElseThrow().card());
    var statedCard =
        ai.searchavo.search.web.CardMapper.card(
            details.find(resourceId(stated)).orElseThrow().card());

    assertThat(aiCard.getEventOrigin())
        .isEqualTo(ai.searchavo.api.common.model.ValueOriginDto.AI_EXTRACTED);
    assertThat(statedCard.getEventOrigin())
        .isEqualTo(ai.searchavo.api.common.model.ValueOriginDto.SOURCE_PROVIDED);
    assertThat(statedCard.getTimeline().getEventStart().getValue().toInstant())
        .hasToString("2027-04-02T00:00:00Z");
  }

  @Test
  void event_days_only_a_model_read_do_not_put_the_page_in_the_sitemap_days_a_source_states_do() {
    String url = "https://meetup-" + run + ".example/2027";
    String scout = ai.searchavo.shared.WebSources.EVENTS_SCOUT;
    Map<String, SourceContext> contexts =
        Map.of("ai-deadlines", context("ai-deadlines"), scout, context(scout));
    writer.upsert(
        new ResultMerger()
            .merge(
                List.of(
                    eventFrom(scout, url, "A meetup about AI agents", "2027-05-10", "2027-05-11")),
                contexts),
        DataOrigin.OBSERVED,
        Set.of(scout));
    UUID id = resourceId(url);
    assertThat(eventDays(url)).containsExactly("2027-05-10", "2027-05-11");

    assertThat(store.indexable(List.of("EVENT"), 0, 100_000)).noneMatch(r -> r.id().equals(id));

    writer.upsert(
        new ResultMerger()
            .merge(
                List.of(
                    eventFrom(
                        "ai-deadlines",
                        url,
                        "A meetup about AI agents",
                        "2027-05-10",
                        "2027-05-11")),
                contexts),
        DataOrigin.OBSERVED,
        Set.of("ai-deadlines"));

    assertThat(store.indexable(List.of("EVENT"), 0, 100_000)).anyMatch(r -> r.id().equals(id));
  }

  private UUID resourceId(String url) {
    return jdbc.sql("SELECT id FROM resource WHERE canonical_url = :url")
        .param("url", CanonicalUrl.of(url).getValue())
        .query(UUID.class)
        .single();
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
