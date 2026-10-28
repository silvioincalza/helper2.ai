package ai.helper2.guide.internal;

import ai.helper2.guide.GuideAnswer;
import ai.helper2.guide.GuideAnswerStore;
import ai.helper2.platform.CircuitBreaker;
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
 * Redis adapter for the guide answer cache. Each answer is stored as JSON in a versioned envelope,
 * with the cache TTL as the Redis TTL.
 *
 * Why plain Jackson and not an explicit codec like the search cache: an answer embeds a whole search
 * outcome, and an entry that no longer reads (new field types after a deploy) is only a cache miss.
 * {@link #VERSION} is bumped when the answer model changes shape in a way old entries would misread.
 *
 * Failure behaviour: any Redis error opens a {@link CircuitBreaker} for {@code openFor}; while open, get
 * answers null and put does nothing, so an outage never adds latency to a guide question.
 */
class RedisGuideAnswerStore implements GuideAnswerStore {

    static final int VERSION = 2;
    private static final Logger log = LoggerFactory.getLogger(RedisGuideAnswerStore.class);

    private final StringRedisTemplate redis;
    private final ObjectMapper json;
    private final CircuitBreaker circuit;

    RedisGuideAnswerStore(StringRedisTemplate redis, ObjectMapper json, Clock clock, Duration openFor) {
        this.redis = redis;
        this.json = json.rebuild()
            .addModule(new KotlinModule.Builder().build())
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();
        this.circuit = new CircuitBreaker("guide answer store", clock, openFor);
    }

    /** The cache key holds the user's question, so Redis sees only its hash. */
    static String redisKey(String cacheKey) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(cacheKey.getBytes(StandardCharsets.UTF_8));
            return "h2:v" + VERSION + ":guide-answer:" + HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public GuideAnswer get(String key) {
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
    public void put(String key, GuideAnswer answer, Duration ttl) {
        if (circuit.isOpen()) {
            return;
        }
        String encoded;
        try {
            encoded = encode(answer);
        } catch (RuntimeException e) {
            log.warn("guide answer not stored, could not encode it: {}", e.getClass().getSimpleName());
            return;
        }
        try {
            redis.opsForValue().set(redisKey(key), encoded, ttl);
        } catch (RuntimeException e) {
            circuit.trip(e);
        }
    }

    String encode(GuideAnswer answer) {
        ObjectNode root = json.createObjectNode();
        root.put("v", VERSION);
        root.set("answer", json.valueToTree(answer));
        return json.writeValueAsString(root);
    }

    /** Null for another version or an entry that no longer reads: both are a cache miss. */
    GuideAnswer decode(String raw) {
        try {
            JsonNode root = json.readTree(raw);
            if (root.path("v").asInt() != VERSION) {
                return null;
            }
            return json.treeToValue(root.get("answer"), GuideAnswer.class);
        } catch (RuntimeException e) {
            log.debug("unreadable guide answer in Redis, treated as a miss: {}", e.getClass().getSimpleName());
            return null;
        }
    }
}
