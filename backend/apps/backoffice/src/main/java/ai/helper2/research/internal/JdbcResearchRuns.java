package ai.helper2.research.internal;

import ai.helper2.research.ResearchResult;
import ai.helper2.research.ResearchRun;
import ai.helper2.research.ResearchRuns;
import ai.helper2.research.ResearchStep;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.json.JsonMapper;

class JdbcResearchRuns implements ResearchRuns {

  private static final int CHUNK = 5_000;

  private static final String STALE_ERROR = "the worker stopped: no step for 5 minutes";

  private final JdbcClient jdbc;
  private final JsonMapper json;

  JdbcResearchRuns(JdbcClient jdbc, JsonMapper json) {
    this.jdbc = jdbc;
    this.json = json;
  }

  @Override
  public List<ResearchRun> enqueue(Collection<UUID> itemIds, String model, String by) {
    List<UUID> ids = List.copyOf(new java.util.LinkedHashSet<>(itemIds));
    List<ResearchRun> queued = new ArrayList<>();
    for (int from = 0; from < ids.size(); from += CHUNK) {
      queued.addAll(
          jdbc.sql(
                  """
                  INSERT INTO review_research (id, review_item_id, status, model, requested_by, requested_at)
                  SELECT gen_random_uuid(), item, 'QUEUED', :model, :by, now()
                    FROM unnest(ARRAY[:ids]::uuid[]) AS item
                  ON CONFLICT (review_item_id) WHERE status IN ('QUEUED', 'RUNNING') DO NOTHING
                  RETURNING *
                  """)
              .param("ids", ids.subList(from, Math.min(ids.size(), from + CHUNK)))
              .param("model", model)
              .param("by", by)
              .query(this::run)
              .list());
    }
    return queued;
  }

  @Override
  public Set<UUID> activeItems() {
    return Set.copyOf(
        jdbc.sql("SELECT review_item_id FROM review_research WHERE status IN ('QUEUED', 'RUNNING')")
            .query(UUID.class)
            .list());
  }

  @Override
  public Optional<ResearchRun> claimNext(Instant now) {
    return jdbc.sql(
            """
            UPDATE review_research r SET status = 'RUNNING', started_at = :now, heartbeat_at = :now
             WHERE r.id = (SELECT q.id FROM review_research q WHERE q.status = 'QUEUED'
                            ORDER BY q.requested_at, q.id LIMIT 1 FOR UPDATE SKIP LOCKED)
            RETURNING *
            """)
        .param("now", Timestamp.from(now))
        .query(this::run)
        .optional();
  }

  @Override
  public void step(ResearchStep s) {
    jdbc.sql(
            """
            INSERT INTO review_research_step (research_id, seq, kind, name, request, response, error,
                                              duration_ms, prompt_tokens, completion_tokens, created_at)
            VALUES (:run, :seq, :kind, :name, :request, :response, :error, :ms, :prompt, :completion, :at)
            """)
        .param("run", s.researchId())
        .param("seq", s.seq())
        .param("kind", s.kind().name())
        .param("name", clip(s.name(), 200))
        .param("request", s.request())
        .param("response", s.response())
        .param("error", s.error() == null ? null : clip(s.error(), 2000))
        .param("ms", (int) Math.min(Integer.MAX_VALUE, s.durationMs()))
        .param("prompt", s.promptTokens())
        .param("completion", s.completionTokens())
        .param("at", Timestamp.from(s.at()))
        .update();
    jdbc.sql("UPDATE review_research SET steps = steps + 1, heartbeat_at = now() WHERE id = :run")
        .param("run", s.researchId())
        .update();
  }

  @Override
  public void done(
      UUID runId, ResearchResult result, int promptTokens, int completionTokens, Instant at) {
    jdbc.sql(
            """
            UPDATE review_research SET status = 'DONE', result = CAST(:result AS jsonb), finished_at = :at,
                   prompt_tokens = :prompt, completion_tokens = :completion
             WHERE id = :id AND status = 'RUNNING'
            """)
        .param("id", runId)
        .param("result", json.writeValueAsString(result))
        .param("at", Timestamp.from(at))
        .param("prompt", promptTokens)
        .param("completion", completionTokens)
        .update();
  }

  @Override
  public void failed(UUID runId, String error, int promptTokens, int completionTokens, Instant at) {
    jdbc.sql(
            """
            UPDATE review_research SET status = 'FAILED', error = :error, finished_at = :at,
                   prompt_tokens = :prompt, completion_tokens = :completion
             WHERE id = :id AND status = 'RUNNING'
            """)
        .param("id", runId)
        .param("error", clip(error, 2000))
        .param("at", Timestamp.from(at))
        .param("prompt", promptTokens)
        .param("completion", completionTokens)
        .update();
  }

  @Override
  public boolean cancel(UUID runId, Instant at) {
    return jdbc.sql(
                """
                UPDATE review_research SET status = 'CANCELLED', finished_at = :at
                 WHERE id = :id AND status IN ('QUEUED', 'RUNNING')
                """)
            .param("id", runId)
            .param("at", Timestamp.from(at))
            .update()
        == 1;
  }

  @Override
  public boolean isCancelled(UUID runId) {
    return jdbc.sql("SELECT status = 'CANCELLED' FROM review_research WHERE id = :id")
        .param("id", runId)
        .query(Boolean.class)
        .optional()
        .orElse(true);
  }

  @Override
  public int failStale(Instant before, Instant at) {
    return jdbc.sql(
            """
            UPDATE review_research SET status = 'FAILED', error = :error, finished_at = :at
             WHERE status = 'RUNNING' AND heartbeat_at < :before
            """)
        .param("error", STALE_ERROR)
        .param("at", Timestamp.from(at))
        .param("before", Timestamp.from(before))
        .update();
  }

  @Override
  public Optional<ResearchRun> find(UUID runId) {
    return jdbc.sql("SELECT * FROM review_research WHERE id = :id")
        .param("id", runId)
        .query(this::run)
        .optional();
  }

  @Override
  public List<ResearchRun> latest(Collection<UUID> itemIds) {
    if (itemIds.isEmpty()) {
      return List.of();
    }
    return jdbc.sql(
            """
            SELECT DISTINCT ON (review_item_id) * FROM review_research
             WHERE review_item_id IN (:ids)
             ORDER BY review_item_id, requested_at DESC, id DESC
            """)
        .param("ids", List.copyOf(itemIds))
        .query(this::run)
        .list();
  }

  @Override
  public List<ResearchStep> steps(UUID runId, int afterSeq) {
    return jdbc.sql(
            "SELECT * FROM review_research_step WHERE research_id = :id AND seq > :after ORDER BY seq")
        .param("id", runId)
        .param("after", afterSeq)
        .query(JdbcResearchRuns::step)
        .list();
  }

  @Override
  public int purge(Instant before) {
    return jdbc.sql("DELETE FROM review_research WHERE finished_at < :before")
        .param("before", Timestamp.from(before))
        .update();
  }

  private ResearchRun run(ResultSet rs, int i) throws SQLException {
    String result = rs.getString("result");
    return new ResearchRun(
        rs.getObject("id", UUID.class),
        rs.getObject("review_item_id", UUID.class),
        ResearchRun.Status.valueOf(rs.getString("status")),
        rs.getString("model"),
        rs.getString("requested_by"),
        rs.getTimestamp("requested_at").toInstant(),
        instant(rs.getTimestamp("started_at")),
        instant(rs.getTimestamp("finished_at")),
        rs.getInt("steps"),
        rs.getInt("prompt_tokens"),
        rs.getInt("completion_tokens"),
        result == null ? null : json.readValue(result, ResearchResult.class),
        rs.getString("error"));
  }

  private static ResearchStep step(ResultSet rs, int i) throws SQLException {
    return new ResearchStep(
        rs.getObject("research_id", UUID.class),
        rs.getInt("seq"),
        ResearchStep.Kind.valueOf(rs.getString("kind")),
        rs.getString("name"),
        rs.getString("request"),
        rs.getString("response"),
        rs.getString("error"),
        rs.getInt("duration_ms"),
        (Integer) rs.getObject("prompt_tokens"),
        (Integer) rs.getObject("completion_tokens"),
        rs.getTimestamp("created_at").toInstant());
  }

  private static @Nullable Instant instant(@Nullable Timestamp t) {
    return t == null ? null : t.toInstant();
  }

  private static String clip(String s, int max) {
    return s.length() <= max ? s : s.substring(0, max);
  }
}
