package ai.helper2.guide.internal;

import ai.helper2.guide.GuideAnswer;
import ai.helper2.guide.GuideAnswerRetention;
import ai.helper2.guide.GuideAnswerStore;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import tools.jackson.module.kotlin.KotlinModule;

/**
 * {@link GuideAnswerStore} in {@code guide_answer} (ADR 0041): on disk, shared by every replica. The key is already a
 * hash of the question, so no question text is stored. Each answer is JSON in a versioned envelope.
 *
 * <p>Why plain Jackson and not an explicit codec like the search cache: an answer embeds a whole search outcome, and
 * an entry that no longer reads (new field types after a deploy) is only a cache miss. {@link #VERSION} is bumped when
 * the answer model changes shape in a way old entries would misread. Each build reads only its own answers
 * ({@code H2_BUILD_VERSION}), so a deploy that changes how the guide reads or answers is seen at once.
 *
 * <p>A database error is a miss on read and nothing stored on write, so it never breaks a guide question.
 */
class JdbcGuideAnswerStore implements GuideAnswerStore, GuideAnswerRetention {

    static final int VERSION = 2;
    private static final Logger log = LoggerFactory.getLogger(JdbcGuideAnswerStore.class);

    /** A build id as the chart passes it (the image tag); anything else is "unknown". */
    private static final Pattern BUILD = Pattern.compile("[A-Za-z0-9._-]{1,40}");

    private final JdbcClient jdbc;
    private final ObjectMapper json;
    private final String build;

    JdbcGuideAnswerStore(JdbcClient jdbc, ObjectMapper json, String build) {
        this.jdbc = jdbc;
        this.json = json.rebuild()
            .addModule(new KotlinModule.Builder().build())
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();
        this.build = build != null && BUILD.matcher(build).matches() ? build : "unknown";
    }

    @Override
    public GuideAnswer get(String key) {
        String raw;
        try {
            raw = jdbc.sql("SELECT answer::text FROM guide_answer WHERE answer_key = :key AND build = :build")
                .param("key", key).param("build", build)
                .query(String.class).optional().orElse(null);
        } catch (DataAccessException e) {
            log.debug("guide answer store unavailable: {}", e.getClass().getSimpleName());
            return null;
        }
        return raw == null ? null : decode(raw);
    }

    @Override
    public void put(String key, GuideAnswer answer, Instant expiresAt) {
        String encoded;
        try {
            encoded = encode(answer);
        } catch (RuntimeException e) {
            log.warn("guide answer not stored, could not encode it: {}", e.getClass().getSimpleName());
            return;
        }
        // A database error reaches the cache, which reports the answer as not cached: nothing indexes it then.
        jdbc.sql("""
                INSERT INTO guide_answer (answer_key, build, answer, expires_at)
                VALUES (:key, :build, CAST(:answer AS jsonb), :expiresAt)
                ON CONFLICT (answer_key) DO UPDATE
                SET build = EXCLUDED.build, answer = EXCLUDED.answer, expires_at = EXCLUDED.expires_at
                """)
            .param("key", key).param("build", build).param("answer", encoded).param("expiresAt", Timestamp.from(expiresAt))
            .update();
    }

    /** Expired answers, their indexed questions with them, and indexed questions that expired on their own. */
    @Override
    public int deleteExpiredBefore(Instant cutoff) {
        Timestamp at = Timestamp.from(cutoff);
        jdbc.sql("DELETE FROM guide_similar_question WHERE expires_at < :cutoff").param("cutoff", at).update();
        return jdbc.sql("DELETE FROM guide_answer WHERE expires_at < :cutoff").param("cutoff", at).update();
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
            log.debug("unreadable guide answer, treated as a miss: {}", e.getClass().getSimpleName());
            return null;
        }
    }
}
