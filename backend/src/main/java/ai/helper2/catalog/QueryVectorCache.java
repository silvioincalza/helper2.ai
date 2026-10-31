package ai.helper2.catalog;

import ai.helper2.platform.cache.SharedCache;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.Base64;
import org.jspecify.annotations.Nullable;

/**
 * Query vectors shared by every replica (ADR 0012): the guide's example questions and pagination would otherwise
 * spend the embedding API's rate limit on the same text, once per replica. Keyed by a hash of the embedding model and
 * the normalised query, kept for {@code ttl}; the value is the vector's floats in Base64. An entry of another size or that
 * does not read is a miss.
 */
final class QueryVectorCache {

    private final SharedCache cache;
    private final String model;
    private final int dimensions;
    private final Duration ttl;

    QueryVectorCache(SharedCache cache, String model, int dimensions, Duration ttl) {
        this.cache = cache;
        this.model = model;
        this.dimensions = dimensions;
        this.ttl = ttl;
    }

    @Nullable float[] get(String query) {
        String raw = cache.get(key(query));
        if (raw == null) {
            return null;
        }
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(raw);
        } catch (IllegalArgumentException e) {
            return null;
        }
        if (bytes.length != dimensions * Float.BYTES) {
            return null;
        }
        float[] vector = new float[dimensions];
        ByteBuffer.wrap(bytes).asFloatBuffer().get(vector);
        return vector;
    }

    void put(String query, float[] vector) {
        ByteBuffer bytes = ByteBuffer.allocate(vector.length * Float.BYTES);
        bytes.asFloatBuffer().put(vector);
        cache.put(key(query), Base64.getEncoder().encodeToString(bytes.array()), ttl);
    }

    private String key(String query) {
        return cache.key(model, SharedCache.normalisedQuery(query));
    }
}
