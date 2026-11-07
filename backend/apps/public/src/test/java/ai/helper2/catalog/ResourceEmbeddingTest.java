package ai.helper2.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import ai.helper2.TestcontainersConfiguration;
import ai.helper2.catalog.Embedder.EmbeddingFailure;
import ai.helper2.catalog.Embedder.FailureReason;
import ai.helper2.catalog.internal.EmbeddingIndexer;
import ai.helper2.catalog.internal.ResourceStore;
import ai.helper2.catalog.internal.ResourceStore.DataOrigin;
import ai.helper2.catalog.internal.ResourceStore.EmbeddingTask;
import ai.helper2.catalog.internal.ResourceStore.ResourceRow;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

/**
 * resource_embedding against real Postgres + pgvector: the indexer loop and nearest-neighbour
 * search.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class ResourceEmbeddingTest {

  static final int DIM = 1024;

  @Autowired JdbcClient jdbc;

  @Autowired ResourceStore store;

  final String run = UUID.randomUUID().toString();
  final String model = "test-model-" + run;
  final List<UUID> created = new ArrayList<>();

  @AfterEach
  void clean() {
    jdbc.sql("DELETE FROM resource WHERE id = ANY(:ids)")
        .param("ids", created.toArray(UUID[]::new))
        .update();
  }

  private UUID insert(String title, String description) {
    return insert(title, description, "INDEXED");
  }

  private UUID insert(String title, String description, String dataOrigin) {
    UUID id = UUID.randomUUID();
    String url = "https://embedding.example/" + run + "/" + id;
    jdbc.sql(
            """
            INSERT INTO resource (id, canonical_url, url, title, description, description_origin, type, audience, data_origin)
            VALUES (:id, :url, :url, :title, :description, 'SOURCE_PROVIDED', 'LIBRARY', 'BUILD_WITH_AI', :origin)
            """)
        .param("id", id)
        .param("url", url)
        .param("title", title)
        .param("description", description)
        .param("origin", dataOrigin)
        .update();
    created.add(id);
    return id;
  }

  /** A unit vector on axis [axis], leaning [lean] towards axis+1: distances are known exactly. */
  static float[] axis(int axis, float lean) {
    float[] v = new float[DIM];
    v[axis] = 1;
    v[axis + 1] = lean;
    return v;
  }

  /** Embeds a text by its first word: "a…" → axis 0, "b…" → axis 2, anything else → axis 4. */
  class FakeEmbedder implements Embedder {
    final List<List<String>> batches = new ArrayList<>();

    @Override
    public String model() {
      return model;
    }

    @Override
    public int dimensions() {
      return DIM;
    }

    @Override
    public List<float[]> embed(List<String> texts, Kind kind) {
      batches.add(texts);
      return vectorsOf(texts);
    }

    static List<float[]> vectorsOf(List<String> texts) {
      return texts.stream()
          .map(t -> t.startsWith("a") ? axis(0, 0) : t.startsWith("b") ? axis(2, 0) : axis(4, 0))
          .toList();
    }
  }

  private List<EmbeddingTask> pendingOfThisRun() {
    return store.pendingEmbeddings(model, 100_000).tasks().stream()
        .filter(t -> created.contains(t.resourceId()))
        .toList();
  }

  @Test
  void theIndexerEmbedsNewRowsAndReembedsOnlyChangedText() {
    UUID alpha = insert("alpha", "first");
    UUID beta = insert("beta", "second");
    FakeEmbedder embedder = new FakeEmbedder();
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    EmbeddingIndexer indexer =
        new EmbeddingIndexer(
            store, embedder, 100_000, 0, Duration.ofMinutes(1), Clock.systemUTC(), registry);

    assertThat(pendingOfThisRun())
        .extracting(EmbeddingTask::resourceId)
        .containsExactlyInAnyOrder(alpha, beta);
    assertThat(pendingOfThisRun()).extracting(EmbeddingTask::text).contains("alpha\nfirst");
    indexer.tick();
    assertThat(pendingOfThisRun()).isEmpty();
    assertThat(registry.get("helper2.embedding.last.success").gauge().value()).isPositive();

    jdbc.sql("UPDATE resource SET description = 'changed' WHERE id = :id")
        .param("id", beta)
        .update();

    assertThat(pendingOfThisRun()).extracting(EmbeddingTask::resourceId).containsExactly(beta);
  }

  /**
   * A row whose text changed after it was embedded is still backlog: it has a vector, but not a
   * current one. The model is unique to this run, so the first tick embeds every row in the
   * database and the backlog then holds only what this test changes.
   */
  @Test
  void theBacklogCountsRowsWhoseTextChangedAfterTheyWereEmbedded() {
    UUID alpha = insert("alpha", "first");
    UUID beta = insert("beta", "second");
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    new EmbeddingIndexer(
            store,
            new FakeEmbedder(),
            100_000,
            0,
            Duration.ofMinutes(1),
            Clock.systemUTC(),
            registry)
        .tick();
    assertThat(registry.get("helper2.embedding.backlog").gauge().value()).isZero();
    assertThat(store.pendingEmbeddings(model, 1).backlog()).isZero();

    jdbc.sql("UPDATE resource SET description = 'changed' WHERE id = :id")
        .param("id", beta)
        .update();

    assertThat(store.pendingEmbeddings(model, 1).backlog()).isEqualTo(1);

    jdbc.sql("UPDATE resource SET description = 'changed too' WHERE id = :id")
        .param("id", alpha)
        .update();
    SimpleMeterRegistry oneAtATime = new SimpleMeterRegistry();
    new EmbeddingIndexer(
            store, new FakeEmbedder(), 1, 0, Duration.ofMinutes(1), Clock.systemUTC(), oneAtATime)
        .tick();

    assertThat(oneAtATime.get("helper2.embedding.backlog").gauge().value())
        .as("one of the two changed rows is left")
        .isEqualTo(1);
  }

  @Test
  void failuresAreCountedByReason() {
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    Embedder failing =
        new FakeEmbedder() {
          @Override
          public List<float[]> embed(List<String> texts, Kind kind) {
            throw new EmbeddingFailure("rate limited", FailureReason.RATE_LIMITED);
          }
        };
    insert("pending", "x");
    EmbeddingIndexer indexer =
        new EmbeddingIndexer(
            store, failing, 10, 0, Duration.ofMinutes(1), Clock.systemUTC(), registry);

    indexer.tick();

    assertThat(
            registry
                .get("helper2.embedding.failures")
                .tag("reason", "rate_limited")
                .counter()
                .count())
        .isEqualTo(1);
    assertThat(registry.get("helper2.embedding.last.success").gauge().value()).isZero();
  }

  /**
   * Fails every call of more than one text with [manyTexts]; one text at a time works, except the
   * texts in [failingSingles], which fail with [singleFailure].
   */
  class FailsOnBatches extends FakeEmbedder {
    final FailureReason manyTexts;
    final Set<String> failingSingles;
    final FailureReason singleFailure;

    FailsOnBatches(FailureReason manyTexts) {
      this(manyTexts, Set.of(), FailureReason.PROVIDER_ERROR);
    }

    FailsOnBatches(
        FailureReason manyTexts, Set<String> failingSingles, FailureReason singleFailure) {
      this.manyTexts = manyTexts;
      this.failingSingles = failingSingles;
      this.singleFailure = singleFailure;
    }

    @Override
    public List<float[]> embed(List<String> texts, Kind kind) {
      batches.add(texts);
      if (texts.size() > 1) {
        throw new EmbeddingFailure("embedding call failed: HTTP 500", manyTexts);
      }
      if (failingSingles.contains(texts.getFirst())) {
        throw new EmbeddingFailure("single failed", singleFailure);
      }
      return vectorsOf(texts);
    }
  }

  /** Embeds every row in the database under this run's model, so only rows added after count. */
  private void embedEverythingSoFar() {
    new EmbeddingIndexer(
            store,
            new FakeEmbedder(),
            100_000,
            0,
            Duration.ofMinutes(1),
            Clock.systemUTC(),
            new SimpleMeterRegistry())
        .tick();
  }

  private double failures(SimpleMeterRegistry registry, String reason) {
    return registry.get("helper2.embedding.failures").tag("reason", reason).counter().count();
  }

  /**
   * NVIDIA's API failed most calls of two or more texts with 500 while every single text went
   * through (staging, 5 Nov 2026). A batch the provider rejects is embedded one text at a time, so
   * the backlog moves instead of failing the same batch every tick.
   */
  @Test
  void aBatchTheProviderRejectsIsEmbeddedOneTextAtATime() {
    embedEverythingSoFar();
    UUID alpha = insert("alpha", "x");
    UUID beta = insert("beta", "y");
    UUID other = insert("other", "z");
    FailsOnBatches embedder = new FailsOnBatches(FailureReason.PROVIDER_ERROR);
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    EmbeddingIndexer indexer =
        new EmbeddingIndexer(
            store, embedder, 10, 5, Duration.ofMinutes(1), Clock.systemUTC(), registry);

    int embedded = indexer.tick();

    assertThat(embedded).isEqualTo(3);
    assertThat(pendingOfThisRun()).isEmpty();
    assertThat(embedder.batches).extracting(List::size).containsExactly(3, 1, 1, 1);
    assertThat(failures(registry, "embedding")).isEqualTo(1);
    assertThat(registry.get("helper2.embedding.backlog").gauge().value()).isZero();
    assertThat(registry.get("helper2.embedding.last.success").gauge().value()).isPositive();
    assertThat(store.pendingEmbeddings(model, 10).backlog()).isZero();
    assertThat(List.of(alpha, beta, other)).hasSize(3);
  }

  /** At most [singlesAfterFailure] texts one at a time per tick: the rest waits for later ticks. */
  @Test
  void theFallbackEmbedsAtMostTheConfiguredNumberOfSingleTexts() {
    embedEverythingSoFar();
    insert("alpha", "x");
    insert("beta", "y");
    insert("other", "z");
    FailsOnBatches embedder = new FailsOnBatches(FailureReason.PROVIDER_ERROR);
    SimpleMeterRegistry registry = new SimpleMeterRegistry();

    new EmbeddingIndexer(store, embedder, 10, 2, Duration.ofMinutes(1), Clock.systemUTC(), registry)
        .tick();

    assertThat(embedder.batches).extracting(List::size).containsExactly(3, 1, 1);
    assertThat(pendingOfThisRun()).hasSize(1);
    assertThat(registry.get("helper2.embedding.backlog").gauge().value()).isEqualTo(1);
  }

  /** A text that fails on its own is skipped for this tick; the texts after it still go through. */
  @Test
  void aSingleTextThatFailsDoesNotBlockTheOthers() {
    embedEverythingSoFar();
    UUID alpha = insert("alpha", "x");
    UUID beta = insert("beta", "y");
    FailsOnBatches embedder =
        new FailsOnBatches(
            FailureReason.PROVIDER_ERROR, Set.of("alpha\nx"), FailureReason.PROVIDER_ERROR);
    SimpleMeterRegistry registry = new SimpleMeterRegistry();

    new EmbeddingIndexer(store, embedder, 10, 5, Duration.ofMinutes(1), Clock.systemUTC(), registry)
        .tick();

    assertThat(pendingIds()).contains(alpha).doesNotContain(beta);
    assertThat(failures(registry, "embedding")).isEqualTo(2);
  }

  /** A refused call never reached the API: trying again smaller would only be refused again. */
  @Test
  void aRefusedBatchIsNotSplit() {
    embedEverythingSoFar();
    insert("alpha", "x");
    insert("beta", "y");
    FailsOnBatches embedder = new FailsOnBatches(FailureReason.REFUSED);
    SimpleMeterRegistry registry = new SimpleMeterRegistry();

    new EmbeddingIndexer(store, embedder, 10, 5, Duration.ofMinutes(1), Clock.systemUTC(), registry)
        .tick();

    assertThat(embedder.batches).hasSize(1);
    assertThat(pendingOfThisRun()).hasSize(2);
  }

  /** A rate limit during the fallback keeps what was embedded and pauses the indexer. */
  @Test
  void aRateLimitDuringTheFallbackKeepsTheVectorsAndPauses() {
    embedEverythingSoFar();
    UUID alpha = insert("alpha", "x");
    UUID beta = insert("beta", "y");
    UUID other = insert("other", "z");
    FailsOnBatches embedder =
        new FailsOnBatches(
            FailureReason.PROVIDER_ERROR, Set.of("beta\ny"), FailureReason.RATE_LIMITED);
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    EmbeddingIndexer indexer =
        new EmbeddingIndexer(
            store, embedder, 10, 5, Duration.ofMinutes(1), Clock.systemUTC(), registry);
    List<UUID> order = pendingOfThisRun().stream().map(EmbeddingTask::resourceId).toList();

    indexer.tick();

    assertThat(order).containsExactlyInAnyOrder(alpha, beta, other);
    assertThat(pendingIds())
        .as("the texts before beta are embedded; beta and the rest wait")
        .containsExactlyInAnyOrderElementsOf(order.subList(order.indexOf(beta), order.size()));
    assertThat(failures(registry, "rate_limited")).isEqualTo(1);
    int callsSoFar = embedder.batches.size();
    assertThat(indexer.tick()).as("paused").isZero();
    assertThat(embedder.batches).hasSize(callsSoFar);
  }

  private List<UUID> pendingIds() {
    return pendingOfThisRun().stream().map(EmbeddingTask::resourceId).toList();
  }

  @Test
  void nearestFirstWithinTheDistanceLimit() {
    UUID near = insert("near", "x");
    UUID far = insert("far", "x");
    store.saveEmbeddings(
        model,
        List.of(new EmbeddingTask(near, "near\nx", "h1"), new EmbeddingTask(far, "far\nx", "h2")),
        List.of(axis(0, 0.1f), axis(1, 0)));
    Set<DataOrigin> origins = Set.of(DataOrigin.INDEXED);

    List<UUID> all =
        store.semanticSearch(axis(0, 0), model, origins, 10, 2).stream()
            .map(ResourceRow::id)
            .toList();
    List<UUID> close =
        store.semanticSearch(axis(0, 0), model, origins, 10, 0.2).stream()
            .map(ResourceRow::id)
            .toList();

    assertThat(all).containsExactly(near, far);
    assertThat(close).containsExactly(near);
  }

  @Test
  void reembeddingUnderTheConfiguredModelDropsTheVectorsOfOtherModels() {
    UUID id = insert("switched", "x");
    String oldModel = "old-model-" + run;
    store.saveEmbeddings(
        oldModel, List.of(new EmbeddingTask(id, "switched\nx", "h")), List.of(axis(0, 0)));

    store.saveEmbeddings(
        model, List.of(new EmbeddingTask(id, "switched\nx", "h")), List.of(axis(2, 0)));

    assertThat(
            jdbc.sql("SELECT model FROM resource_embedding WHERE resource_id = :id")
                .param("id", id)
                .query(String.class)
                .list())
        .containsExactly(model);
  }

  /**
   * Observed rows closer to the query than the one indexed row, more of them than the HNSW
   * candidate list holds: a filtered scan that stops after one list finds no indexed row. The test
   * transaction makes the planner read through the HNSW index (no sort, small candidate list) as it
   * does on a large table; the search's own transaction setting must keep scanning until the limit
   * is met.
   */
  @Test
  @Transactional
  void theNearestRowsOfTheWantedOriginsAreFoundBehindCloserRowsOfOtherOrigins() {
    jdbc.sql("SET LOCAL enable_sort = off").update();
    jdbc.sql("SET LOCAL enable_seqscan = off").update();
    jdbc.sql("SET LOCAL hnsw.ef_search = 10").update();
    List<EmbeddingTask> crowd = new ArrayList<>();
    List<float[]> crowdVectors = new ArrayList<>();
    for (int i = 0; i < 60; i++) {
      crowd.add(new EmbeddingTask(insert("crowd " + i, "x", "OBSERVED"), "crowd", "h"));
      crowdVectors.add(axis(0, 0.001f * i));
    }
    store.saveEmbeddings(model, crowd, crowdVectors);
    UUID wanted = insert("wanted", "x");
    store.saveEmbeddings(
        model, List.of(new EmbeddingTask(wanted, "wanted\nx", "h")), List.of(axis(0, 0.5f)));

    List<UUID> found =
        store.semanticSearch(axis(0, 0), model, Set.of(DataOrigin.INDEXED), 10, 2).stream()
            .map(ResourceRow::id)
            .toList();

    assertThat(found).containsExactly(wanted);
  }

  @Test
  void aDeletedResourceTakesItsEmbeddingWithIt() {
    UUID id = insert("gone", "x");
    store.saveEmbeddings(
        model, List.of(new EmbeddingTask(id, "gone\nx", "h")), List.of(axis(0, 0)));

    jdbc.sql("DELETE FROM resource WHERE id = :id").param("id", id).update();

    assertThat(
            jdbc.sql("SELECT count(*) FROM resource_embedding WHERE model = :m")
                .param("m", model)
                .query(Long.class)
                .single())
        .isZero();
  }
}
