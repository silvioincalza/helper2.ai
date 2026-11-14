package ai.helper2.catalog.internal;

import static org.assertj.core.api.Assertions.assertThat;

import ai.helper2.TestcontainersConfiguration;
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
class ResourceIdentityIT {

  @Autowired CatalogWriter writer;
  @Autowired JdbcClient jdbc;

  private final String run = UUID.randomUUID().toString().substring(0, 8);
  private final String repositories = "repositories-" + run;
  private final String packages = "packages-" + run;
  private final Set<String> sources = Set.of(repositories, packages);

  @BeforeEach
  void sources() {
    writer.registerSources(List.of(descriptor(repositories), descriptor(packages)));
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

  private static SourceHit stating(
      String source, String url, @Nullable String repository, @Nullable String license) {
    return new SourceHit(
        source,
        source + ":" + url,
        URI.create(url),
        CanonicalUrl.of(url),
        repository == null ? null : CanonicalUrl.of(repository),
        "tool " + url,
        "An AI tool",
        ValueOrigin.SOURCE_PROVIDED,
        ResourceType.LIBRARY,
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
        Instant.now(),
        0);
  }

  private void write(SourceHit... hits) {
    List<MergedResult> merged = new ResultMerger().merge(List.of(hits), Map.of());
    writer.upsert(merged, DataOrigin.INDEXED, sources);
  }

  private static UUID idOf(String url) {
    return ResourceId.of(CanonicalUrl.of(url)).getValue();
  }

  private List<UUID> resourcesWithRecordsOfThisRun() {
    return jdbc.sql("SELECT DISTINCT resource_id FROM source_record WHERE source_id IN (:sources)")
        .param("sources", sources)
        .query(UUID.class)
        .list();
  }

  private List<String> sourcesOfTheRecordsOf(UUID resource) {
    return jdbc.sql(
            "SELECT source_id FROM source_record WHERE resource_id = :id ORDER BY source_id")
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
  void aPackageAndItsRepositoryAreOneResource() {
    String repository = "https://github.com/acme/one-thing-" + run;
    String registryPage = "https://www.npmjs.com/package/one-thing-" + run;

    write(
        stating(repositories, repository, null, null),
        stating(packages, registryPage, repository, null));

    assertThat(resourcesWithRecordsOfThisRun()).containsExactly(idOf(repository));
    assertThat(sourcesOfTheRecordsOf(idOf(repository))).containsExactly(packages, repositories);
    assertThat(exists(idOf(registryPage))).isFalse();
  }

  @Test
  void thePackagesOfOneRepositoryAreRecordsOfTheRepositoryInAnyOrderOfArrival() {
    String repository = "https://github.com/acme/monorepo-" + run;
    String first = "https://www.npmjs.com/package/@acme/first-" + run;
    String second = "https://www.npmjs.com/package/@acme/second-" + run;

    write(stating(packages, first, repository, null));
    write(stating(repositories, repository, null, null));
    write(stating(packages, second, repository, null));

    assertThat(resourcesWithRecordsOfThisRun()).containsExactly(idOf(repository));
    assertThat(sourcesOfTheRecordsOf(idOf(repository)))
        .containsExactly(packages, packages, repositories);
  }

  @Test
  void theGitRefOfARepositoryPathIsNotPartOfTheIdentity() {
    String onMain = "https://github.com/acme/refs-" + run + "/tree/main/packages/player";
    String onHead = "https://github.com/acme/refs-" + run + "/tree/HEAD/packages/player";
    String registryPage = "https://www.npmjs.com/package/@acme/player-" + run;

    write(stating(repositories, onMain, null, null));
    write(stating(packages, registryPage, onHead, null));

    assertThat(idOf(onMain)).isEqualTo(idOf(onHead));
    assertThat(resourcesWithRecordsOfThisRun()).containsExactly(idOf(onHead));
    assertThat(sourcesOfTheRecordsOf(idOf(onHead))).containsExactly(packages, repositories);
  }

  @Test
  void aFactStatedLaterIsMergedIntoTheExistingResource() {
    String url = "https://github.com/acme/better-data-" + run;
    write(stating(repositories, url, null, null));
    assertThat(licenceOf(idOf(url))).isEmpty();

    write(stating(packages, url, null, "MIT"));

    assertThat(resourcesWithRecordsOfThisRun()).containsExactly(idOf(url));
    assertThat(licenceOf(idOf(url))).contains("MIT");
    assertThat(sourcesOfTheRecordsOf(idOf(url))).containsExactly(packages, repositories);
  }
}
