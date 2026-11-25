package ai.searchavo.catalog.internal;

import static org.assertj.core.api.Assertions.assertThat;

import ai.searchavo.TestcontainersConfiguration;
import ai.searchavo.catalog.CatalogWriter;
import ai.searchavo.catalog.DataOrigin;
import ai.searchavo.catalog.ResourceRemoval;
import ai.searchavo.catalog.ReviewQueue;
import ai.searchavo.search.ResultMerger;
import ai.searchavo.search.SourceDescriptor;
import ai.searchavo.search.SourceHit;
import ai.searchavo.shared.Audience;
import ai.searchavo.shared.CanonicalUrl;
import ai.searchavo.shared.DateFact;
import ai.searchavo.shared.ResourceId;
import ai.searchavo.shared.ResourceType;
import ai.searchavo.shared.SourceMode;
import ai.searchavo.shared.ValueOrigin;
import ai.searchavo.testing.AsBackOffice;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
@AsBackOffice
class ResourceRemovalIT {

  @Autowired CatalogWriter writer;
  @Autowired ResourceRemoval removal;
  @Autowired ReviewQueue queue;
  @Autowired JdbcClient jdbc;
  @Autowired TransactionTemplate tx;

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
    assertThat(queue.proposeCandidate(ReviewQueue.Kind.COURSE_CANDIDATE, url, "New course", "{}"))
        .as("and it does not come back as a course")
        .isFalse();
  }

  @Test
  void aRemovalWaitsForAWriteOfTheSameAddressThatHasNotEndedAndThenRemovesIt() throws Exception {
    CountDownLatch written = new CountDownLatch(1);
    CountDownLatch removalStarted = new CountDownLatch(1);
    try (ExecutorService threads = Executors.newFixedThreadPool(2)) {
      Future<?> write =
          threads.submit(() ->
              tx.executeWithoutResult(status -> {
                write(DataOrigin.INDEXED);
                written.countDown();
                awaitBriefly(removalStarted);
                sleepBriefly();
              }));
      assertThat(written.await(10, TimeUnit.SECONDS)).isTrue();
      Future<Optional<ResourceRemoval.Removed>> removed =
          threads.submit(() -> {
            removalStarted.countDown();
            return tx.execute(status -> removal.remove(id));
          });

      write.get(20, TimeUnit.SECONDS);

      assertThat(removed.get(20, TimeUnit.SECONDS)).contains(new ResourceRemoval.Removed(url));
    }
    assertThat(exists())
        .as("the write that overlapped the removal leaves nothing listed")
        .isFalse();
  }

  @Test
  void aWriteThatStartsWhileARemovalHasNotEndedDoesNotListTheAddressAgain() throws Exception {
    write(DataOrigin.INDEXED);
    CountDownLatch removed = new CountDownLatch(1);
    CountDownLatch writeStarted = new CountDownLatch(1);
    try (ExecutorService threads = Executors.newFixedThreadPool(2)) {
      Future<?> removal =
          threads.submit(() ->
              tx.executeWithoutResult(status -> {
                this.removal.remove(id);
                removed.countDown();
                awaitBriefly(writeStarted);
                sleepBriefly();
              }));
      assertThat(removed.await(10, TimeUnit.SECONDS)).isTrue();
      Future<?> write =
          threads.submit(() -> {
            writeStarted.countDown();
            write(DataOrigin.INDEXED);
          });

      removal.get(20, TimeUnit.SECONDS);
      write.get(20, TimeUnit.SECONDS);
    }
    assertThat(exists()).isFalse();
    assertThat(records()).isZero();
  }

  private static void awaitBriefly(CountDownLatch latch) {
    try {
      latch.await(10, TimeUnit.SECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  private static void sleepBriefly() {
    try {
      Thread.sleep(300);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
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
        URI.create("https://searchavo.ai/sources"),
        null,
        null,
        EnumSet.allOf(ResourceType.class),
        Duration.ofSeconds(1),
        Duration.ZERO);
  }
}
