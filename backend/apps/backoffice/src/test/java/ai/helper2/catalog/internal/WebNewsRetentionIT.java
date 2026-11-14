package ai.helper2.catalog.internal;

import static org.assertj.core.api.Assertions.assertThat;

import ai.helper2.TestcontainersConfiguration;
import ai.helper2.catalog.CatalogRetention;
import ai.helper2.catalog.CatalogWriter;
import ai.helper2.catalog.DataOrigin;
import ai.helper2.search.MergedResult;
import ai.helper2.search.ResultMerger;
import ai.helper2.search.SourceDescriptor;
import ai.helper2.search.SourceHit;
import ai.helper2.shared.Audience;
import ai.helper2.shared.CanonicalUrl;
import ai.helper2.shared.DateFact;
import ai.helper2.shared.ResourceId;
import ai.helper2.shared.ResourceType;
import ai.helper2.shared.SourceMode;
import ai.helper2.shared.ValueOrigin;
import ai.helper2.testing.AsBackOffice;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
@AsBackOffice
class WebNewsRetentionIT {

  static final Instant NOW = Instant.parse("2000-07-01T00:00:00Z");
  static final Instant CUTOFF = NOW.minus(Duration.ofDays(180));
  static final Instant EXPIRED = NOW.minus(Duration.ofDays(181));
  static final Instant KEPT = NOW.minus(Duration.ofDays(1));

  @Autowired CatalogWriter writer;
  @Autowired CatalogRetention retention;
  @Autowired JdbcClient jdbc;

  private final String run = UUID.randomUUID().toString().substring(0, 8);
  private final String feed = "feed-" + run;
  private final String lists = "lists-" + run;
  private final Set<String> sources = Set.of(feed, lists);

  @BeforeEach
  void sources() {
    writer.registerSources(List.of(descriptor(feed), descriptor(lists)));
  }

  private static SourceDescriptor descriptor(String id) {
    return new SourceDescriptor(
        id,
        id,
        "test",
        true,
        SourceMode.INDEXED,
        URI.create("https://helper2.ai/sources"),
        null,
        null,
        EnumSet.allOf(ResourceType.class),
        Duration.ofSeconds(1),
        Duration.ZERO);
  }

  private static SourceHit article(
      String source, String url, @Nullable String license, Instant retrieved) {
    return new SourceHit(
        source,
        source + ":" + url,
        URI.create(url),
        CanonicalUrl.of(url),
        null,
        "article " + url,
        "An article about AI",
        ValueOrigin.SOURCE_PROVIDED,
        ResourceType.ARTICLE,
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
        retrieved,
        0);
  }

  private UUID write(String url, SourceHit... hits) {
    List<MergedResult> merged = new ResultMerger().merge(List.of(hits), Map.of());
    writer.upsert(merged, DataOrigin.INDEXED, sources);
    return ResourceId.of(CanonicalUrl.of(url)).getValue();
  }

  private List<String> sourcesOfTheRecordsOf(UUID resource) {
    return jdbc.sql("SELECT source_id FROM source_record WHERE resource_id = :id")
        .param("id", resource)
        .query(String.class)
        .list();
  }

  private Optional<String> licenceOf(UUID resource) {
    return jdbc.sql("SELECT license FROM resource WHERE id = :id")
        .param("id", resource)
        .query((rs, i) -> Optional.ofNullable(rs.getString("license")))
        .single();
  }

  private boolean exists(UUID resource) {
    return jdbc.sql("SELECT EXISTS (SELECT 1 FROM resource WHERE id = :id)")
        .param("id", resource)
        .query(Boolean.class)
        .single();
  }

  @Test
  void aResourceThatKeepsAnotherRecordLosesTheFactsOfTheExpiredNewsRecord() {
    String url = "https://news.example/kept-" + run;
    UUID resource = write(url, article(feed, url, "MIT", EXPIRED), article(lists, url, null, KEPT));
    assertThat(licenceOf(resource)).contains("MIT");

    int deleted = retention.deleteWebNewsRetrievedBefore(CUTOFF);

    assertThat(deleted).isGreaterThanOrEqualTo(1);
    assertThat(sourcesOfTheRecordsOf(resource)).containsExactly(lists);
    assertThat(licenceOf(resource)).isEmpty();
  }

  @Test
  void aResourceWhoseOnlyRecordIsExpiredNewsIsDeleted() {
    String url = "https://news.example/gone-" + run;
    UUID resource = write(url, article(feed, url, null, EXPIRED));
    assertThat(exists(resource)).isTrue();

    retention.deleteWebNewsRetrievedBefore(CUTOFF);

    assertThat(exists(resource)).isFalse();
  }

  @Test
  void aNewsRecordInsideThePeriodStays() {
    String url = "https://news.example/fresh-" + run;
    UUID resource = write(url, article(feed, url, "MIT", KEPT));

    retention.deleteWebNewsRetrievedBefore(CUTOFF);

    assertThat(sourcesOfTheRecordsOf(resource)).containsExactly(feed);
    assertThat(licenceOf(resource)).contains("MIT");
  }
}
