package ai.helper2.catalog.internal;

import static org.assertj.core.api.Assertions.assertThat;

import ai.helper2.TestcontainersConfiguration;
import ai.helper2.search.SourceHit;
import ai.helper2.search.SourceQuery;
import ai.helper2.search.Vitality;
import ai.helper2.shared.DateFact;
import ai.helper2.shared.DateStatus;
import ai.helper2.shared.ResourceType;
import ai.helper2.shared.SourcedValue;
import ai.helper2.shared.WebSources;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class CatalogSearchSourceIT {

  @Autowired
  @Qualifier("indexSearchSource")
  CatalogSearchSource index;

  @Autowired
  @Qualifier("curatedSearchSource")
  CatalogSearchSource curated;

  @Autowired JdbcClient jdbc;

  final String word = "zorblax" + UUID.randomUUID().toString().replaceAll("[^a-f]", "");
  final Instant now = Instant.now();
  final List<UUID> created = new ArrayList<>();

  UUID indexedToolTenDaysOld;
  UUID indexedArticleThreeDaysOld;
  UUID observedToolTwoDaysOld;
  UUID indexedToolALongTimeAgo;
  UUID indexedToolWithoutDate;
  UUID curatedToolFiveDaysOld;

  @BeforeEach
  void catalog() {
    indexedToolTenDaysOld = resource("PRODUCT", "INDEXED", daysAgo(10));
    indexedArticleThreeDaysOld = resource("ARTICLE", "INDEXED", daysAgo(3));
    observedToolTwoDaysOld = resource("PRODUCT", "OBSERVED", daysAgo(2));
    indexedToolALongTimeAgo = resource("PRODUCT", "INDEXED", daysAgo(400));
    indexedToolWithoutDate = resource("PRODUCT", "INDEXED", null);
    curatedToolFiveDaysOld = resource("PRODUCT", "CURATED", daysAgo(5));
  }

  @AfterEach
  void clean() {
    created.forEach(id -> jdbc.sql("DELETE FROM resource WHERE id = :id").param("id", id).update());
  }

  Instant daysAgo(int days) {
    return now.minus(days, ChronoUnit.DAYS);
  }

  UUID resource(String type, String origin, Instant published) {
    UUID id = UUID.randomUUID();
    String url = "https://catalog-search.example/" + id;
    jdbc.sql(
            """
            INSERT INTO resource (id, canonical_url, url, title, description, description_origin, type,
                                  audience, data_origin, published_at, published_precision)
            VALUES (:id, :url, :url, :title, 'A resource of this test.', 'SOURCE_PROVIDED', :type,
                    'USE_AI', :origin, :published, :precision)
            """)
        .params(
            Map.of(
                "id", id,
                "url", url,
                "title", word + " " + type.toLowerCase(Locale.ROOT),
                "type", type,
                "origin", origin))
        .param("published", published == null ? null : Timestamp.from(published))
        .param("precision", published == null ? "UNKNOWN" : "INSTANT")
        .update();
    created.add(id);
    return id;
  }

  SourceQuery browse(LocalDate after, Set<ResourceType> types) {
    return new SourceQuery(
        "", List.of(), null, 5000, List.of(""), List.of(), null, null, after, true, types);
  }

  SourceQuery text(LocalDate after) {
    return new SourceQuery(
        word, List.of(), null, 50, List.of(word), List.of(), null, null, after, false, Set.of());
  }

  List<UUID> ours(List<SourceHit> hits) {
    return hits.stream()
        .map(h -> UUID.fromString(h.getExternalId()))
        .filter(created::contains)
        .toList();
  }

  LocalDate dayOf(Instant instant) {
    return instant.atZone(ZoneOffset.UTC).toLocalDate();
  }

  void record(UUID resource, String extra) {
    jdbc.sql(
            """
            INSERT INTO source_record (id, source_id, external_id, resource_id, source_url, canonical_url,
                                       title, type, retrieved_at, fingerprint, extra)
            VALUES (:id, 'github', :external, :resource, :url, :url, 'record', 'PRODUCT', now(), 'f',
                    CAST(:extra AS jsonb))
            """)
        .param("id", UUID.randomUUID())
        .param("external", "catalog-search-it/" + resource)
        .param("resource", resource)
        .param("url", "https://catalog-search.example/" + resource)
        .param("extra", extra)
        .update();
  }

  @Test
  void aHitStatesWhatAVisitRecordedAboutItsRepository() {
    record(indexedToolTenDaysOld, "{\"vitality\":{\"archived\":true}}");
    record(observedToolTwoDaysOld, "{\"vitality\":{\"archived\":false}}");
    record(indexedToolWithoutDate, "{}");

    Map<UUID, Boolean> archived = new HashMap<>();
    index
        .search(text(null))
        .forEach(
            h -> archived.put(UUID.fromString(h.getExternalId()), h.getVitality().getArchived()));

    assertThat(archived.get(indexedToolTenDaysOld)).isTrue();
    assertThat(archived.get(observedToolTwoDaysOld)).isFalse();
    assertThat(archived.get(indexedToolWithoutDate)).isNull();
  }

  @Test
  void aHitStatesEveryFactOfVitalityItsRecordsHold() {
    record(
        indexedToolTenDaysOld,
        "{\"vitality\":{\"fork\":true,\"deprecated\":true,\"running\":false}}");
    record(
        observedToolTwoDaysOld,
        "{\"vitality\":{\"fork\":false,\"deprecated\":false,\"running\":true}}");

    Map<UUID, Vitality> stated = new HashMap<>();
    index
        .search(text(null))
        .forEach(h -> stated.put(UUID.fromString(h.getExternalId()), h.getVitality()));

    assertThat(stated.get(indexedToolTenDaysOld)).isEqualTo(new Vitality(null, true, true, false));
    assertThat(stated.get(observedToolTwoDaysOld))
        .isEqualTo(new Vitality(null, false, false, true));
    assertThat(stated.get(indexedToolWithoutDate)).isEqualTo(Vitality.UNKNOWN);
  }

  @Test
  void browsingWithoutADateFindsNothing() {
    assertThat(index.search(browse(null, Set.of()))).isEmpty();
  }

  @Test
  void browsingListsTheIndexedAndObservedOfTheWindowNewestFirst() {
    assertThat(ours(index.search(browse(dayOf(daysAgo(30)), Set.of()))))
        .containsExactly(observedToolTwoDaysOld, indexedArticleThreeDaysOld, indexedToolTenDaysOld);
  }

  @Test
  void browsingKeepsOnlyTheAskedTypes() {
    assertThat(ours(index.search(browse(dayOf(daysAgo(30)), Set.of(ResourceType.ARTICLE)))))
        .containsExactly(indexedArticleThreeDaysOld);
  }

  @Test
  void theCuratedSourceSeesOnlyCuratedResources() {
    assertThat(ours(curated.search(browse(dayOf(daysAgo(30)), Set.of()))))
        .containsExactly(curatedToolFiveDaysOld);
    assertThat(ours(curated.search(text(null)))).containsExactly(curatedToolFiveDaysOld);
  }

  @Test
  void aTextSearchFindsEveryIndexedMatchAndADateKeepsTheRecentOnes() {
    assertThat(ours(index.search(text(null))))
        .containsExactlyInAnyOrder(
            indexedToolTenDaysOld,
            indexedArticleThreeDaysOld,
            observedToolTwoDaysOld,
            indexedToolALongTimeAgo,
            indexedToolWithoutDate);
    assertThat(ours(index.search(text(dayOf(daysAgo(30))))))
        .containsExactlyInAnyOrder(
            indexedToolTenDaysOld, indexedArticleThreeDaysOld, observedToolTwoDaysOld);
  }

  @Test
  void eachHitCarriesTheSourceAndTheStatedDay() {
    SourceHit hit =
        index.search(browse(dayOf(daysAgo(30)), Set.of(ResourceType.ARTICLE))).stream()
            .filter(h -> h.getExternalId().equals(indexedArticleThreeDaysOld.toString()))
            .findFirst()
            .orElseThrow();
    assertThat(hit.getSourceId()).isEqualTo(index.getDescriptor().getId());
    assertThat(hit.getType()).isEqualTo(ResourceType.ARTICLE);
    assertThat(hit.getPublished()).isNotNull();
  }

  UUID event(String firstDay, String lastDay) {
    UUID id = resource("EVENT", "INDEXED", null);
    jdbc.sql("UPDATE resource SET event_starts_at = :starts, event_ends_at = :ends WHERE id = :id")
        .param("starts", startOf(firstDay))
        .param("ends", startOf(lastDay))
        .param("id", id)
        .update();
    return id;
  }

  void statesTheDays(String source, UUID event, String firstDay, String lastDay) {
    jdbc.sql("INSERT INTO source (id, mode) VALUES (:id, 'INDEXED') ON CONFLICT (id) DO NOTHING")
        .param("id", source)
        .update();
    jdbc.sql(
            """
            INSERT INTO source_record (id, source_id, external_id, resource_id, source_url, canonical_url,
                                       title, type, source_event_starts_at, source_event_ends_at,
                                       retrieved_at, fingerprint, extra)
            VALUES (:id, :source, :external, :resource, :url, :url, 'record', 'EVENT', :starts, :ends,
                    now(), 'f', '{}'::jsonb)
            """)
        .param("id", UUID.randomUUID())
        .param("source", source)
        .param("external", "catalog-search-it/" + event)
        .param("resource", event)
        .param("url", "https://catalog-search.example/" + event)
        .param("starts", startOf(firstDay))
        .param("ends", startOf(lastDay))
        .update();
  }

  Timestamp startOf(String day) {
    return Timestamp.from(LocalDate.parse(day).atStartOfDay(ZoneOffset.UTC).toInstant());
  }

  SourceHit found(UUID id) {
    return index.search(text(null)).stream()
        .filter(h -> h.getExternalId().equals(id.toString()))
        .findFirst()
        .orElseThrow();
  }

  static DateFact day(String day, String statedBy) {
    return DateFact.scheduledDay(LocalDate.parse(day), statedBy);
  }

  @Test
  void anEventHitCarriesTheDaysOfItsRowAndTheSourceThatStatesEach() {
    UUID summit = event("2027-03-12", "2027-03-15");
    statesTheDays("npm", summit, "2027-03-12", "2027-03-14");
    statesTheDays("github", summit, "2027-03-13", "2027-03-15");

    SourceHit hit = found(summit);

    assertThat(hit.getEventStart().getValue()).isEqualTo(day("2027-03-12", "npm").getValue());
    assertThat(hit.getEventStart().getSourceId()).isEqualTo("npm");
    assertThat(hit.getEventEnd().getValue()).isEqualTo(day("2027-03-15", "github").getValue());
    assertThat(hit.getEventEnd().getSourceId()).isEqualTo("github");
  }

  @Test
  void anEventHitWhoseSourcesStateDifferentDaysCarriesTheOtherDaysAndIsMarkedAsConflicting() {
    UUID summit = event("2027-03-12", "2027-03-15");
    statesTheDays("npm", summit, "2027-03-12", "2027-03-14");
    statesTheDays("github", summit, "2027-03-13", "2027-03-15");

    SourceHit hit = found(summit);

    assertThat(hit.getEventStart().getStatus()).isEqualTo(DateStatus.CONFLICTING);
    assertThat(hit.getEventStart().getAlternatives())
        .containsExactly(new SourcedValue("2027-03-13T00:00:00Z", "github"));
    assertThat(hit.getEventEnd().getStatus()).isEqualTo(DateStatus.CONFLICTING);
    assertThat(hit.getEventEnd().getAlternatives())
        .containsExactly(new SourcedValue("2027-03-14T00:00:00Z", "npm"));
  }

  @Test
  void anEventHitWhoseSourcesStateTheSameDaysIsNotMarkedAsConflicting() {
    UUID summit = event("2027-06-01", "2027-06-03");
    statesTheDays("npm", summit, "2027-06-01", "2027-06-03");
    statesTheDays("github", summit, "2027-06-01", "2027-06-03");

    SourceHit hit = found(summit);

    assertThat(hit.getEventStart().getStatus()).isEqualTo(DateStatus.KNOWN);
    assertThat(hit.getEventStart().getAlternatives()).isEmpty();
  }

  @Test
  void theDaysOnlyAModelReadKeepItsSourceAndASourceThatStatesTheDaysReplacesIt() {
    UUID readByAi = event("2027-04-10", "2027-04-20");
    statesTheDays(WebSources.EVENTS_SCOUT, readByAi, "2027-04-10", "2027-04-20");
    UUID stated = event("2027-05-12", "2027-05-14");
    statesTheDays(WebSources.EVENTS_SCOUT, stated, "2027-05-10", "2027-05-20");
    statesTheDays("github", stated, "2027-05-12", "2027-05-14");

    assertThat(found(readByAi).getEventStart())
        .isEqualTo(day("2027-04-10", WebSources.EVENTS_SCOUT));
    assertThat(found(readByAi).getEventEnd()).isEqualTo(day("2027-04-20", WebSources.EVENTS_SCOUT));
    assertThat(found(stated).getEventStart()).isEqualTo(day("2027-05-12", "github"));
    assertThat(found(stated).getEventEnd()).isEqualTo(day("2027-05-14", "github"));
  }

  @Test
  void aHitOfAResourceWithoutEventDaysCarriesNone() {
    SourceHit hit = found(indexedToolTenDaysOld);

    assertThat(hit.getEventStart()).isEqualTo(DateFact.unknown());
    assertThat(hit.getEventEnd()).isEqualTo(DateFact.unknown());
  }
}
