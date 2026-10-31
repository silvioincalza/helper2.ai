package ai.helper2.guide.internal;

import ai.helper2.guide.GuideAnswer;
import ai.helper2.guide.GuideAnswerStore;
import ai.helper2.platform.cache.SharedCache;
import java.time.Duration;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import tools.jackson.module.kotlin.KotlinModule;

/**
 * Guide answers in the shared cache {@code guide-answer} (ADR 0041), on disk and shared by every replica. The guide's
 * key is already a hash of the question; the stored key also holds the running build ({@code H2_BUILD_VERSION}), so a
 * release reads only its own answers and a deploy that changes how the guide reads or answers is seen at once.
 *
 * <p>Each answer is JSON in a versioned envelope. Why plain Jackson and not an explicit codec like the search cache: an
 * answer embeds a whole search outcome, and an entry that no longer reads (new field types after a deploy) is only a
 * miss. {@link #VERSION} is bumped when the answer model changes shape in a way old entries would misread.
 */
class SharedGuideAnswerStore implements GuideAnswerStore {

    static final int VERSION = 2;
    private static final Logger log = LoggerFactory.getLogger(SharedGuideAnswerStore.class);

    /** A build id as the chart passes it (the image tag); anything else is "unknown". */
    private static final Pattern BUILD = Pattern.compile("[A-Za-z0-9._-]{1,40}");

    private final SharedCache cache;
    private final ObjectMapper json;
    private final String build;

    SharedGuideAnswerStore(SharedCache cache, ObjectMapper json, String build) {
        this.cache = cache;
        this.json = json.rebuild()
            .addModule(new KotlinModule.Builder().build())
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();
        this.build = build != null && BUILD.matcher(build).matches() ? build : "unknown";
    }

    @Override
    public GuideAnswer get(String key) {
        String raw = cache.get(cache.key(build, key));
        return raw == null ? null : decode(raw);
    }

    @Override
    public void put(String key, GuideAnswer answer, Duration ttl) {
        String encoded;
        try {
            encoded = encode(answer);
        } catch (RuntimeException e) {
            log.warn("guide answer not stored, could not encode it: {}", e.getClass().getSimpleName());
            return;
        }
        cache.put(cache.key(build, key), encoded, ttl);
    }

    String encode(GuideAnswer answer) {
        ObjectNode root = json.createObjectNode();
        root.put("v", VERSION);
        root.set("answer", json.valueToTree(answer));
        return json.writeValueAsString(root);
    }

    /** Null for another version or an entry that no longer reads: both are a miss. */
    GuideAnswer decode(String raw) {
        try {
            JsonNode root = json.readTree(raw);
            if (root.path("v").asInt() != VERSION) {
                return null;
            }
            return json.treeToValue(root.get("answer"), GuideAnswer.class);
        } catch (RuntimeException e) {
            log.debug("unreadable guide answer, treated as a miss: {}", e.getClass().getSimpleName());
            return null;
        }
    }
}
