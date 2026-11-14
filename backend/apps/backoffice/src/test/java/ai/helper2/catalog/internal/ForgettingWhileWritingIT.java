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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
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
class ForgettingWhileWritingIT {

  private static final Duration PATIENCE = Duration.ofSeconds(20);

  @Autowired CatalogWriter writer;
  @Autowired JdbcClient jdbc;
  @Autowired TransactionTemplate tx;

  private final String run = UUID.randomUUID().toString().substring(0, 8);
  private final String lists = "lists-" + run;
  private final String registry = "registry-" + run;
  private final Set<String> sources = Set.of(lists, registry);

  @BeforeEach
  void sources() {
    writer.registerSources(List.of(descriptor(lists), descriptor(registry)));
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

  private static List<MergedResult> stating(String source, String url, String license) {
    SourceHit hit =
        new SourceHit(
            source,
            source + ":" + url,
            URI.create(url),
            CanonicalUrl.of(url),
            null,
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
    return new ResultMerger().merge(List.of(hit), Map.of());
  }

  private static UUID idOf(String url) {
    return ResourceId.of(CanonicalUrl.of(url)).getValue();
  }

  private Optional<String> licenceOf(String url) {
    return jdbc.sql("SELECT license FROM resource WHERE id = :id")
        .param("id", idOf(url))
        .query((rs, i) -> Optional.ofNullable(rs.getString("license")))
        .single();
  }

  private List<String> sourcesOfTheRecordsOf(String url) {
    return jdbc.sql("SELECT source_id FROM source_record WHERE resource_id = :id")
        .param("id", idOf(url))
        .query(String.class)
        .list();
  }

  private void untilWaitingForALock(CompletableFuture<?> write) throws Exception {
    long deadline = System.nanoTime() + PATIENCE.toNanos();
    while (System.nanoTime() < deadline) {
      if (write.isDone()) {
        write.get();
        throw new AssertionError("the write ended without waiting for the lock");
      }
      int waiting =
          jdbc.sql(
                  """
                  SELECT count(*) FROM pg_stat_activity
                  WHERE datname = current_database() AND wait_event_type = 'Lock'
                  """)
              .query(Integer.class)
              .single();
      if (waiting > 0) {
        return;
      }
      Thread.sleep(20);
    }
    throw new AssertionError("no write waited for the lock");
  }

  @Test
  void aRecordForgottenWhileAnotherIsWrittenLeavesTheFactsOfTheRecordThatStays() throws Exception {
    String url = "https://github.com/acme/forgotten-while-written-" + run;
    writer.upsert(stating(lists, url, "MIT"), DataOrigin.INDEXED, sources);
    CountDownLatch written = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);

    CompletableFuture<Void> upsert =
        CompletableFuture.runAsync(() ->
            tx.executeWithoutResult(status -> {
              writer.upsert(stating(registry, url, "Apache-2.0"), DataOrigin.INDEXED, sources);
              written.countDown();
              try {
                assertThat(release.await(PATIENCE.toSeconds(), TimeUnit.SECONDS)).isTrue();
              } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
              }
            }));
    assertThat(written.await(PATIENCE.toSeconds(), TimeUnit.SECONDS)).isTrue();
    CompletableFuture<CatalogWriter.Forgotten> forget =
        CompletableFuture.supplyAsync(
            () -> writer.forgetRecords(lists, List.of(lists + ":" + url)));
    untilWaitingForALock(forget);
    release.countDown();
    CompletableFuture.allOf(upsert, forget).get(PATIENCE.toSeconds(), TimeUnit.SECONDS);

    assertThat(sourcesOfTheRecordsOf(url)).containsExactly(registry);
    assertThat(licenceOf(url)).contains("Apache-2.0");
  }
}
