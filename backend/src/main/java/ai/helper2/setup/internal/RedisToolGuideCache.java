package ai.helper2.setup.internal;

import ai.helper2.platform.CircuitBreaker;
import ai.helper2.setup.ToolGuideAnswer;
import ai.helper2.setup.ToolGuideCache;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
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
 * Tool guide answers in Redis, shared by every replica and kept across deploys (same envelope and circuit breaker
 * as the guide's answer store). Any Redis error opens the circuit; while open, get misses and put does nothing.
 */
class RedisToolGuideCache implements ToolGuideCache {

    static final int VERSION = 1;
    private static final Logger log = LoggerFactory.getLogger(RedisToolGuideCache.class);

    private final StringRedisTemplate redis;
    private final ObjectMapper json;
    private final CircuitBreaker circuit;
    private final Duration ttl;

    RedisToolGuideCache(StringRedisTemplate redis, ObjectMapper json, Clock clock, Duration ttl) {
        this.redis = redis;
        this.ttl = ttl;
        this.json = json.rebuild()
            .addModule(new KotlinModule.Builder().build())
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();
        this.circuit = new CircuitBreaker("tool guide cache", clock, Duration.ofSeconds(30));
    }

    /** The key holds the user's question, so Redis sees only its hash. */
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
        if (circuit.isOpen()) {
            return null;
        }
        String raw;
        try {
            raw = redis.opsForValue().get(redisKey(key));
        } catch (RuntimeException e) {
            circuit.trip(e);
            return null;
        }
        return raw == null ? null : decode(raw);
    }

    @Override
    public void put(String key, ToolGuideAnswer answer) {
        if (circuit.isOpen()) {
            return;
        }
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
            redis.opsForValue().set(redisKey(key), encoded, ttl);
        } catch (RuntimeException e) {
            circuit.trip(e);
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
