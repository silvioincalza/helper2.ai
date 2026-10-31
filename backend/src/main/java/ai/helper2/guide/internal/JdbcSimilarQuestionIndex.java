package ai.helper2.guide.internal;

import ai.helper2.guide.IndexedQuestion;
import ai.helper2.guide.Neighbour;
import ai.helper2.guide.SimilarQuestionIndex;
import ai.helper2.guide.SimilarQuestionRetention;
import ai.helper2.platform.PgVectors;
import java.sql.Timestamp;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * {@link SimilarQuestionIndex} in {@code guide_similar_question} (ADR 0041), embeddings in a pgvector column. A group
 * holds a few rows, so the search is an exact scan of the group's live rows by cosine distance ({@code <=>}): no
 * approximate index to tune at this size. A row lives as long as its answer in the shared cache ({@code expires_at});
 * the guide purge deletes it after.
 *
 * <p>A database error finds nothing and stores nothing: it only costs the reuse.
 */
class JdbcSimilarQuestionIndex implements SimilarQuestionIndex, SimilarQuestionRetention {

    private static final Logger log = LoggerFactory.getLogger(JdbcSimilarQuestionIndex.class);

    private final JdbcClient jdbc;

    JdbcSimilarQuestionIndex(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Neighbour nearest(String group, float[] vector, Instant now) {
        try {
            return jdbc.sql("""
                    SELECT answer_key, 1 - (embedding <=> CAST(:v AS vector)) AS similarity
                    FROM guide_similar_question
                    WHERE group_key = :group AND expires_at > :now
                    ORDER BY embedding <=> CAST(:v AS vector)
                    LIMIT 1
                    """)
                .param("v", PgVectors.literal(vector)).param("group", group).param("now", Timestamp.from(now))
                .query((rs, i) -> new Neighbour(rs.getString("answer_key"), rs.getDouble("similarity")))
                .optional().orElse(null);
        } catch (DataAccessException e) {
            log.debug("similar-question index unavailable: {}", e.getClass().getSimpleName());
            return null;
        }
    }

    @Override
    public void add(String group, IndexedQuestion question) {
        try {
            jdbc.sql("""
                    INSERT INTO guide_similar_question (answer_key, group_key, embedding, expires_at)
                    VALUES (:key, :group, CAST(:v AS vector), :expiresAt)
                    ON CONFLICT (answer_key) DO UPDATE
                    SET group_key = EXCLUDED.group_key, embedding = EXCLUDED.embedding, expires_at = EXCLUDED.expires_at
                    """)
                .param("key", question.getAnswerKey()).param("group", group)
                .param("v", PgVectors.literal(question.getVector()))
                .param("expiresAt", Timestamp.from(question.getExpiresAt()))
                .update();
        } catch (DataAccessException e) {
            log.debug("similar question not indexed: {}", e.getClass().getSimpleName());
        }
    }

    @Override
    public int deleteExpiredBefore(Instant cutoff) {
        return jdbc.sql("DELETE FROM guide_similar_question WHERE expires_at < :cutoff").param("cutoff", Timestamp.from(cutoff)).update();
    }
}
