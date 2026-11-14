package ai.helper2.catalog.internal;

import ai.helper2.catalog.EmbeddingStore;
import ai.helper2.catalog.EmbeddingStore.EmbeddingCoverage;
import ai.helper2.catalog.EmbeddingStore.EmbeddingTask;
import ai.helper2.catalog.EmbeddingStore.PendingEmbeddings;
import ai.helper2.platform.PgVectors;
import java.util.List;
import java.util.UUID;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

class JdbcEmbeddingStore implements EmbeddingStore {

  private final JdbcClient jdbc;
  private final TransactionTemplate tx;

  JdbcEmbeddingStore(JdbcClient jdbc, TransactionTemplate tx) {
    this.jdbc = jdbc;
    this.tx = tx;
  }

  private static <T> T unavailableOnFailure(java.util.function.Supplier<T> action) {
    try {
      return action.get();
    } catch (DataAccessException e) {
      throw new EmbeddingStore.Unavailable(e);
    }
  }

  private static final String EMBEDDED_TEXT = "r.title || E'\\n' || coalesce(r.description, '')";

  private static final String EMBEDDING_PENDING =
      "r.data_origin <> 'DEMO' AND (e.resource_id IS NULL OR e.text_hash <> md5("
          + EMBEDDED_TEXT
          + "))";

  @Override
  public PendingEmbeddings pendingEmbeddings(String model, int limit) {
    return unavailableOnFailure(() -> pending(model, limit));
  }

  private PendingEmbeddings pending(String model, int limit) {
    long[] backlog = {0};
    List<EmbeddingTask> tasks =
        jdbc.sql(
                """
                SELECT r.id, %1$s AS text, md5(%1$s) AS hash, count(*) OVER () AS backlog
                FROM resource r
                LEFT JOIN resource_embedding e ON e.resource_id = r.id AND e.model = :model
                WHERE %2$s
                ORDER BY r.popularity DESC, r.id
                LIMIT :limit
                """
                    .formatted(EMBEDDED_TEXT, EMBEDDING_PENDING))
            .param("model", model)
            .param("limit", limit)
            .query((rs, i) -> {
              backlog[0] = rs.getLong("backlog");
              return new EmbeddingTask(
                  rs.getObject("id", UUID.class), rs.getString("text"), rs.getString("hash"));
            })
            .list();
    return new PendingEmbeddings(tasks, backlog[0]);
  }

  @Override
  public void saveEmbeddings(String model, List<EmbeddingTask> tasks, List<float[]> vectors) {
    unavailableOnFailure(() -> {
      tx.executeWithoutResult(status -> save(model, tasks, vectors));
      return null;
    });
  }

  private void save(String model, List<EmbeddingTask> tasks, List<float[]> vectors) {
    jdbc.sql("DELETE FROM resource_embedding WHERE resource_id = ANY(:ids) AND model <> :model")
        .param("ids", tasks.stream().map(EmbeddingTask::resourceId).toArray(UUID[]::new))
        .param("model", model)
        .update();
    for (int i = 0; i < tasks.size(); i++) {
      EmbeddingTask t = tasks.get(i);
      jdbc.sql(
              """
              INSERT INTO resource_embedding (resource_id, model, text_hash, embedding, embedded_at)
              SELECT :id, :model, :hash, CAST(:v AS vector), now()
              WHERE EXISTS (SELECT 1 FROM resource WHERE id = :id)
              ON CONFLICT (resource_id, model)
              DO UPDATE SET text_hash = EXCLUDED.text_hash, embedding = EXCLUDED.embedding, embedded_at = now()
              """)
          .param("id", t.resourceId())
          .param("model", model)
          .param("hash", t.textHash())
          .param("v", PgVectors.literal(vectors.get(i)))
          .update();
    }
  }

  @Override
  public EmbeddingCoverage embeddingCoverage(String model) {
    return unavailableOnFailure(() -> coverage(model));
  }

  private EmbeddingCoverage coverage(String model) {
    return jdbc.sql(
            """
            SELECT (SELECT count(*) FROM resource_embedding WHERE model = :model) AS embedded,
                   (SELECT count(*) FROM resource WHERE data_origin <> 'DEMO') AS total
            """)
        .param("model", model)
        .query((rs, i) -> new EmbeddingCoverage(rs.getLong("embedded"), rs.getLong("total")))
        .single();
  }
}
