package ai.helper2.setup.internal;

import ai.helper2.platform.resilience.Resilience;
import ai.helper2.setup.ToolGuideAnswer;
import ai.helper2.setup.ToolGuideCache;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import tools.jackson.module.kotlin.KotlinModule;

/**
 * Tool guide answers in Redis, shared by every replica and kept across deploys (same envelope and {@code redis} policy
 * as the guide's answer store, ADR 0033). An answer holds the checked steps and links to the documents, never the
 * question or a document's text. A Redis error, or the open circuit refusing the call, makes get miss and put do nothing.
 */
class RedisToolGuideCache implements ToolGuideCache {

    static final int VERSION = 2;
    private static final Logger log = LoggerFactory.getLogger(RedisToolGuideCache.class);

    private final StringRedisTemplate redis;
    private final ObjectMapper json;
    private final Resilience resilience;
    private final Duration ttl;

    RedisToolGuideCache(StringRedisTemplate redis, ObjectMapper json, Resilience resilience, Duration ttl) {
        this.redis = redis;
        this.ttl = ttl;
        this.json = json.rebuild()
            .addModule(new KotlinModule.Builder().build())
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();
        this.resilience = resilience;
    }

    /** A fixed-length Redis key under this cache's prefix, whatever the length of the domain's key. */
    static String redisKey(String key) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.UTF_8));
            return "h2:v" + VERSION + ":tool-guide:" + HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public ToolGuideAnswer get(String key) {
        String raw;
        try {
            raw = resilience.call("redis", () -> redis.opsForValue().get(redisKey(key)));
        } catch (RuntimeException e) {
            log.debug("tool guide cache unavailable: {}", e.getClass().getSimpleName());
            return null;
        }
        return raw == null ? null : decode(raw);
    }

    @Override
    public void put(String key, ToolGuideAnswer answer) {
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
        try {
            resilience.run("redis", () -> redis.opsForValue().set(redisKey(key), encoded, ttl));
        } catch (RuntimeException e) {
            log.debug("tool guide not stored, Redis unavailable: {}", e.getClass().getSimpleName());
        }
    }

    /** Null for another version or an entry that no longer reads: both are a miss. */
    ToolGuideAnswer decode(String raw) {
        try {
            JsonNode root = json.readTree(raw);
            if (root.path("v").asInt() != VERSION) {
                return null;
            }
            return json.treeToValue(root.get("answer"), ToolGuideAnswer.class);
        } catch (RuntimeException e) {
            log.debug("unreadable tool guide in Redis, treated as a miss: {}", e.getClass().getSimpleName());
            return null;
        }
    }
}
