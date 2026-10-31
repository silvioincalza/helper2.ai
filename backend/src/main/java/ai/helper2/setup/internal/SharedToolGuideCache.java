package ai.helper2.setup.internal;

import ai.helper2.platform.cache.SharedCache;
import ai.helper2.setup.ToolGuideAnswer;
import ai.helper2.setup.ToolGuideCache;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import tools.jackson.module.kotlin.KotlinModule;

/**
 * Tool guide answers in the shared cache {@code tool-guide} (ADR 0031), shared by every replica and kept across deploys.
 * An answer holds the checked steps and links to the documents, never a document's text. The key is a hash of the
 * domain's key. While the store is unavailable, get misses and put does nothing.
 */
class SharedToolGuideCache implements ToolGuideCache {

    static final int VERSION = 2;
    private static final Logger log = LoggerFactory.getLogger(SharedToolGuideCache.class);

    private final SharedCache cache;
    private final ObjectMapper json;

    SharedToolGuideCache(SharedCache cache, ObjectMapper json) {
        this.cache = cache;
        this.json = json.rebuild()
            .addModule(new KotlinModule.Builder().build())
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();
    }

    @Override
    public ToolGuideAnswer get(String key) {
        String raw = cache.get(cache.key(key));
        return raw == null ? null : decode(raw);
    }

    @Override
    public void put(String key, ToolGuideAnswer answer, Duration ttl) {
        String encoded;
        try {
            ObjectNode root = json.createObjectNode();
            root.put("v", VERSION);
            root.set("answer", json.valueToTree(answer));
            encoded = json.writeValueAsString(root);
        } catch (RuntimeException e) {
            log.warn("tool guide not stored, could not encode it: {}", e.getClass().getSimpleName());
            return;
        }
        cache.put(cache.key(key), encoded, ttl);
    }

    /**
     * Null for another version or an entry that no longer reads: both are a miss, and the answer the model writes
     * next replaces the entry. An entry that does not read is logged, since each one costs a model call.
     */
    ToolGuideAnswer decode(String raw) {
        try {
            JsonNode root = json.readTree(raw);
            if (root.path("v").asInt() != VERSION) {
                return null;
            }
            return json.treeToValue(root.get("answer"), ToolGuideAnswer.class);
        } catch (RuntimeException e) {
            log.warn("unreadable tool guide in the shared cache, treated as a miss: {}", e.getClass().getSimpleName());
            return null;
        }
    }
}
