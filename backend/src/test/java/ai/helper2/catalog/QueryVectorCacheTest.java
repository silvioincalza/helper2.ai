package ai.helper2.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import ai.helper2.testing.InMemorySharedCache;
import java.time.Duration;
import org.junit.jupiter.api.Test;

/** Query vectors shared by the replicas: exact floats back, no query text stored, a vector of another size is a miss. */
class QueryVectorCacheTest {

    private static final Duration TTL = Duration.ofDays(7);
    private static final float[] VECTOR = {0.25f, -1.5f, Float.MIN_VALUE, 3.0e-7f};

    private final InMemorySharedCache shared = new InMemorySharedCache("query-vector");

    @Test
    void aVectorOneReplicaStoredIsReadExactlyByAnother() {
        new QueryVectorCache(shared, "nvidia/embed", 4, TTL).put("MCP  Postgres", VECTOR);

        assertThat(new QueryVectorCache(shared, "nvidia/embed", 4, TTL).get("mcp postgres")).containsExactly(VECTOR);
        assertThat(shared.values).hasSize(1).allSatisfy((key, value) -> {
            assertThat(key).doesNotContain("postgres");
            assertThat(value).doesNotContain("postgres");
        });
        assertThat(shared.ttls.values()).containsOnly(TTL);
    }

    @Test
    void anotherModelHasItsOwnVectors() {
        new QueryVectorCache(shared, "nvidia/embed", 4, TTL).put("mcp postgres", VECTOR);

        assertThat(new QueryVectorCache(shared, "other/embed", 4, TTL).get("mcp postgres")).isNull();
    }

    @Test
    void aVectorOfAnotherSizeOrUnreadableIsAMiss() {
        var fourDims = new QueryVectorCache(shared, "nvidia/embed", 4, TTL);
        fourDims.put("mcp postgres", VECTOR);

        assertThat(new QueryVectorCache(shared, "nvidia/embed", 8, TTL).get("mcp postgres")).isNull();

        shared.values.replaceAll((key, value) -> "not base64!");
        assertThat(fourDims.get("mcp postgres")).isNull();
    }

    @Test
    void anUnavailableCacheIsAMiss() {
        shared.down = true;
        var vectors = new QueryVectorCache(shared, "nvidia/embed", 4, TTL);
        vectors.put("mcp postgres", VECTOR);

        assertThat(vectors.get("mcp postgres")).isNull();
    }
}
