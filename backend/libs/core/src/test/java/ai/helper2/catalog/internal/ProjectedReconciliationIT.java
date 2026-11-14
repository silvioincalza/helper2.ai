package ai.helper2.catalog.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.helper2.TestcontainersConfiguration;
import ai.helper2.catalog.CatalogWriter;
import ai.helper2.catalog.DataOrigin;
import ai.helper2.catalog.RecordRow;
import ai.helper2.catalog.ResourceCards;
import ai.helper2.catalog.ResourceRow;
import ai.helper2.catalog.Resources;
import ai.helper2.search.DescribedSource;
import ai.helper2.search.MergedResult;
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
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest
@Import({TestcontainersConfiguration.class, ProjectedReconciliationIT.CardsThatCanFail.class})
class ProjectedReconciliationIT {

  private static final Set<String> LIVE = Set.of("github", "npm");
  private static final Duration PATIENCE = Duration.ofSeconds(20);

  @TestConfiguration(proxyBeanMethods = false)
  static class CardsThatCanFail {
    static final AtomicBoolean FAILING = new AtomicBoolean();

    @Bean
    @Primary
    ResourceCards cardsThatCanFail(Resources resources, List<DescribedSource> sources) {
      return new ResourceCards(resources, sources) {
        @Override
        public Optional<MergedResult> card(ResourceRow row, List<RecordRow> records) {
          if (FAILING.get()) {
            throw new IllegalStateException("the projection fails");
          }
          return super.card(row, records);
        }
      };
    }
  }

  @Autowired CatalogWriter writer;
  @Autowired Reconciliation reconciliation;
  @Autowired JdbcClient jdbc;
  @Autowired TransactionTemplate tx;

  private final String run = UUID.randomUUID().toString().substring(0, 8);

  @AfterEach
  void theCardsWorkAgain() {
    CardsThatCanFail.FAILING.set(false);
  }

  private static SourceHit stating(String source, String url, String license) {
    return new SourceHit(
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
  }

  private static List<MergedResult> merge(SourceHit hit) {
    Map<String, SourceContext> contexts =
        Map.of("github", context("github"), "npm", context("npm"));
    return new ResultMerger().merge(List.of(hit), contexts);
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
    return jdbc.sql(
            "SELECT source_id FROM source_record WHERE resource_id = :id ORDER BY source_id")
        .param("id", idOf(url))
        .query(String.class)
        .list();
  }

  private void untilWaitingForALock(CompletableFuture<Void> write) throws Exception {
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

  private CompletableFuture<Void> heldOpenUntil(
      CountDownLatch release, CountDownLatch written, Runnable write) {
    return CompletableFuture.runAsync(() ->
        tx.executeWithoutResult(status -> {
          write.run();
          written.countDown();
          try {
            assertThat(release.await(PATIENCE.toSeconds(), TimeUnit.SECONDS)).isTrue();
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
          }
        }));
  }

  @Test
  void twoSearchesThatWriteOneResourceAtOnceKeepBothRecordsAndTheirDisagreement() throws Exception {
    String url = "https://github.com/acme/at-once-" + run;
    CountDownLatch written = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);

    CompletableFuture<Void> first =
        heldOpenUntil(
            release,
            written,
            () -> writer.upsert(merge(stating("npm", url, "MIT")), DataOrigin.INDEXED, LIVE));
    assertThat(written.await(PATIENCE.toSeconds(), TimeUnit.SECONDS)).isTrue();
    CompletableFuture<Void> second =
        CompletableFuture.runAsync(() ->
            writer.upsert(merge(stating("github", url, "Apache-2.0")), DataOrigin.INDEXED, LIVE));
    untilWaitingForALock(second);
    release.countDown();
    CompletableFuture.allOf(first, second).get(PATIENCE.toSeconds(), TimeUnit.SECONDS);

    assertThat(sourcesOfTheRecordsOf(url)).containsExactly("github", "npm");
    assertThat(licenceOf(url)).as("the two sources disagree, so no licence").isEmpty();
  }

  @Test
  void aProjectionThatFailsRollsTheWholeWriteBack() {
    String url = "https://github.com/acme/rolled-back-" + run;
    writer.upsert(merge(stating("npm", url, "MIT")), DataOrigin.INDEXED, LIVE);
    CardsThatCanFail.FAILING.set(true);

    assertThatThrownBy(() ->
            writer.upsert(merge(stating("github", url, "Apache-2.0")), DataOrigin.INDEXED, LIVE))
        .hasMessageContaining("the projection fails");

    assertThat(sourcesOfTheRecordsOf(url)).containsExactly("npm");
    assertThat(licenceOf(url)).contains("MIT");
  }

  @Test
  void theCatalogReconcilesByProjectionByDefault() {
    assertThat(reconciliation).isInstanceOf(ProjectedReconciliation.class);
  }
}
