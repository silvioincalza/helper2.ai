package ai.helper2.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import ai.helper2.TestcontainersConfiguration;
import ai.helper2.catalog.internal.EmbeddingIndexer;
import ai.helper2.catalog.internal.ResourceStore;
import ai.helper2.catalog.internal.ResourceStore.DataOrigin;
import ai.helper2.catalog.internal.ResourceStore.EmbeddingTask;
import ai.helper2.catalog.internal.ResourceStore.ResourceRow;
import ai.helper2.catalog.Embedder.EmbeddingFailure;
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

/** resource_embedding against real Postgres + pgvector: the indexer loop and nearest-neighbour search. */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class ResourceEmbeddingTest {

    static final int DIM = 1024;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    ResourceStore store;

    final String run = UUID.randomUUID().toString();
    final String model = "test-model-" + run;
    final List<UUID> created = new ArrayList<>();

    @AfterEach
    void clean() {
        jdbc.sql("DELETE FROM resource WHERE id = ANY(:ids)").param("ids", created.toArray(UUID[]::new)).update();
    }

    private UUID insert(String title, String description) {
        return insert(title, description, "INDEXED");
    }

    private UUID insert(String title, String description, String dataOrigin) {
        UUID id = UUID.randomUUID();
        String url = "https://embedding.example/" + run + "/" + id;
        jdbc.sql("""
                INSERT INTO resource (id, canonical_url, url, title, description, description_origin, type, audience, data_origin)
                VALUES (:id, :url, :url, :title, :description, 'SOURCE_PROVIDED', 'LIBRARY', 'BUILD_WITH_AI', :origin)
                """)
            .param("id", id).param("url", url).param("title", title).param("description", description)
            .param("origin", dataOrigin).update();
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
            return texts.stream().map(t -> t.startsWith("a") ? axis(0, 0) : t.startsWith("b") ? axis(2, 0) : axis(4, 0)).toList();
        }
    }

    private List<EmbeddingTask> pendingOfThisRun() {
        return store.pendingEmbeddings(model, 100_000).stream().filter(t -> created.contains(t.resourceId())).toList();
    }

    @Test
    void theIndexerEmbedsNewRowsAndReembedsOnlyChangedText() {
        UUID alpha = insert("alpha", "first");
        UUID beta = insert("beta", "second");
        FakeEmbedder embedder = new FakeEmbedder();
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        EmbeddingIndexer indexer = new EmbeddingIndexer(store, embedder, 100_000, Duration.ofMinutes(1), Clock.systemUTC(), registry);

        assertThat(pendingOfThisRun()).extracting(EmbeddingTask::resourceId).containsExactlyInAnyOrder(alpha, beta);
        assertThat(pendingOfThisRun()).extracting(EmbeddingTask::text).contains("alpha\nfirst");
        indexer.tick();
        assertThat(pendingOfThisRun()).isEmpty();
        assertThat(registry.get("helper2.embedding.last.success").gauge().value()).isPositive();

        jdbc.sql("UPDATE resource SET description = 'changed' WHERE id = :id").param("id", beta).update();

        assertThat(pendingOfThisRun()).extracting(EmbeddingTask::resourceId).containsExactly(beta);
    }

    @Test
    void failuresAreCountedByReason() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        Embedder failing = new FakeEmbedder() {
            @Override
            public List<float[]> embed(List<String> texts, Kind kind) {
                throw new EmbeddingFailure("rate limited", true);
            }
        };
        insert("pending", "x");
        EmbeddingIndexer indexer = new EmbeddingIndexer(store, failing, 10, Duration.ofMinutes(1), Clock.systemUTC(), registry);

        indexer.tick();

        assertThat(registry.get("helper2.embedding.failures").tag("reason", "rate_limited").counter().count()).isEqualTo(1);
        assertThat(registry.get("helper2.embedding.last.success").gauge().value()).isZero();
    }

    @Test
    void nearestFirstWithinTheDistanceLimit() {
        UUID near = insert("near", "x");
        UUID far = insert("far", "x");
        store.saveEmbeddings(model, List.of(new EmbeddingTask(near, "near\nx", "h1"), new EmbeddingTask(far, "far\nx", "h2")),
            List.of(axis(0, 0.1f), axis(1, 0)));
        Set<DataOrigin> origins = Set.of(DataOrigin.INDEXED);

        List<UUID> all = store.semanticSearch(axis(0, 0), model, origins, 10, 2).stream().map(ResourceRow::id).toList();
        List<UUID> close = store.semanticSearch(axis(0, 0), model, origins, 10, 0.2).stream().map(ResourceRow::id).toList();

        assertThat(all).containsExactly(near, far);
        assertThat(close).containsExactly(near);
    }

    @Test
    void reembeddingUnderTheConfiguredModelDropsTheVectorsOfOtherModels() {
        UUID id = insert("switched", "x");
        String oldModel = "old-model-" + run;
        store.saveEmbeddings(oldModel, List.of(new EmbeddingTask(id, "switched\nx", "h")), List.of(axis(0, 0)));

        store.saveEmbeddings(model, List.of(new EmbeddingTask(id, "switched\nx", "h")), List.of(axis(2, 0)));

        assertThat(jdbc.sql("SELECT model FROM resource_embedding WHERE resource_id = :id").param("id", id).query(String.class).list())
            .containsExactly(model);
    }

    /**
     * Observed rows closer to the query than the one indexed row, more of them than the HNSW candidate list holds:
     * a filtered scan that stops after one list finds no indexed row. The test transaction makes the planner read
     * through the HNSW index (no sort, small candidate list) as it does on a large table; the search's own
     * transaction setting must keep scanning until the limit is met.
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
        store.saveEmbeddings(model, List.of(new EmbeddingTask(wanted, "wanted\nx", "h")), List.of(axis(0, 0.5f)));

        List<UUID> found = store.semanticSearch(axis(0, 0), model, Set.of(DataOrigin.INDEXED), 10, 2).stream()
            .map(ResourceRow::id).toList();

        assertThat(found).containsExactly(wanted);
    }

    @Test
    void aDeletedResourceTakesItsEmbeddingWithIt() {
        UUID id = insert("gone", "x");
        store.saveEmbeddings(model, List.of(new EmbeddingTask(id, "gone\nx", "h")), List.of(axis(0, 0)));

        jdbc.sql("DELETE FROM resource WHERE id = :id").param("id", id).update();

        assertThat(jdbc.sql("SELECT count(*) FROM resource_embedding WHERE model = :m").param("m", model).query(Long.class).single())
            .isZero();
    }
}
