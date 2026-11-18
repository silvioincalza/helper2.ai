package ai.helper2.catalog.internal;

import static org.assertj.core.api.Assertions.assertThat;

import ai.helper2.TestcontainersConfiguration;
import ai.helper2.catalog.CatalogWriter;
import ai.helper2.catalog.DataOrigin;
import ai.helper2.catalog.ResourceRemoval;
import ai.helper2.catalog.ReviewQueue;
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
@Import(TestcontainersConfiguration.class)
@AsBackOffice
class ResourceRemovalIT {

  @Autowired CatalogWriter writer;
  @Autowired ResourceRemoval removal;
  @Autowired ReviewQueue queue;
  @Autowired JdbcClient jdbc;

  private final String run = UUID.randomUUID().toString().substring(0, 8);
  private final String source = "awesome-" + run;
  private final String url = "https://github.com/someone/awesome-crypto-" + run;
  private final UUID id = ResourceId.of(CanonicalUrl.of(url)).getValue();

  @BeforeEach
  void source() {
    writer.registerSources(List.of(descriptor(source)));
  }

  @AfterEach
  void clean() {
    jdbc.sql("DELETE FROM resource WHERE id = :id").param("id", id).update();
    jdbc.sql("DELETE FROM removed_resource WHERE resource_key = :id").param("id", id).update();
    jdbc.sql("DELETE FROM candidate_decision WHERE candidate_key = :id").param("id", id).update();
    jdbc.sql("DELETE FROM review_item WHERE candidate_url = :url").param("url", url).update();
  }

  @Test
  void aRemovedResourceIsGoneAndTheScoutDoesNotAddItsAddressAgain() {
    write(DataOrigin.INDEXED);
    assertThat(exists()).isTrue();

    assertThat(removal.remove(id)).contains(new ResourceRemoval.Removed(url));

    assertThat(exists()).isFalse();
    assertThat(records()).isZero();
    assertThat(
            jdbc.sql(
                    "SELECT status FROM candidate_decision"
                        + " WHERE kind = 'RESOURCE_CANDIDATE' AND candidate_key = :id")
                .param("id", id)
                .query(String.class)
                .single())
        .isEqualTo("REJECTED");

    write(DataOrigin.INDEXED);
    write(DataOrigin.OBSERVED);

    assertThat(exists()).as("an index or a live search finds the address again").isFalse();
    assertThat(records()).isZero();
    assertThat(
            queue.proposeCandidate(ReviewQueue.Kind.RESOURCE_CANDIDATE, url, "New LIBRARY", "{}"))
        .as("the review queue skips it too")
        .isFalse();
  }

  @Test
  void anAdminWhoAddsTheAddressByHandBringsItBack() {
    write(DataOrigin.INDEXED);
    removal.remove(id);

    write(DataOrigin.CURATED);

    assertThat(exists()).isTrue();
    assertThat(
            jdbc.sql("SELECT count(*) FROM removed_resource WHERE resource_key = :id")
                .param("id", id)
                .query(Integer.class)
                .single())
        .isZero();
    write(DataOrigin.INDEXED);
    assertThat(records()).isEqualTo(1);
  }

  @Test
  void anUnknownResourceIsNotRemoved() {
    assertThat(removal.remove(UUID.randomUUID())).isEmpty();
  }

  private void write(DataOrigin origin) {
    writer.upsert(new ResultMerger().merge(List.of(hit()), Map.of()), origin, Set.of(source));
  }

  private boolean exists() {
    return jdbc.sql("SELECT EXISTS (SELECT 1 FROM resource WHERE id = :id)")
        .param("id", id)
        .query(Boolean.class)
        .single();
  }

  private int records() {
    return jdbc.sql("SELECT count(*) FROM source_record WHERE canonical_url = :url")
        .param("url", url)
        .query(Integer.class)
        .single();
  }

  private SourceHit hit() {
    return new SourceHit(
        source,
        source + ":" + url,
        URI.create(url),
        CanonicalUrl.of(url),
        null,
        "awesome-crypto",
        "A list of crypto sources",
        ValueOrigin.SOURCE_PROVIDED,
        ResourceType.AWESOME_LIST,
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
        Instant.parse("2026-11-18T00:00:00Z"),
        0);
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
}
