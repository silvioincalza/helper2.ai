package ai.helper2.catalog.internal;

import ai.helper2.catalog.KnownTarget;
import ai.helper2.catalog.RelationshipTargets;
import ai.helper2.catalog.ScoutPolicy;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

class JdbcRelationshipTargets implements RelationshipTargets {
  private record Locked(
      String status,
      String kind,
      @Nullable UUID relationshipId,
      @Nullable UUID from,
      @Nullable String relKind) {}

  private final JdbcClient jdbc;

  JdbcRelationshipTargets(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public Outcome change(UUID itemId, @Nullable UUID targetId, @Nullable String title) {
    Locked item = lock(itemId);
    if (item == null) {
      return new Outcome.NotFound();
    }
    if (!"OPEN".equals(item.status())) {
      return new Outcome.NotOpen();
    }
    if (!"RELATIONSHIP_CANDIDATE".equals(item.kind()) || item.relationshipId() == null) {
      return new Outcome.NotARelationship();
    }
    String newTitle = targetId == null ? title : titleOf(targetId);
    if (newTitle == null) {
      return new Outcome.UnknownTarget();
    }
    if (targetId != null && targetId.equals(item.from())) {
      return new Outcome.SelfLink();
    }
    if (titleTaken(item, newTitle)) {
      return new Outcome.TitleTaken();
    }
    jdbc.sql(
            """
            UPDATE relationship
            SET to_resource_id = :target, to_title = :title, target_origin = 'CURATED'
            WHERE id = :id
            """)
        .param("target", targetId)
        .param("title", newTitle)
        .param("id", item.relationshipId())
        .update();
    retell(itemId, item.relKind(), newTitle, targetId);
    return new Outcome.Changed(targetId, newTitle);
  }

  @Override
  public int linkOpenCandidates(String by, String note) {
    return jdbc.sql(
            """
            WITH candidate AS (
                   SELECT i.id AS item, rel.id AS relationship, rel.from_resource_id,
                          lower(btrim(rel.to_title)) AS name
                   FROM review_item i JOIN relationship rel ON rel.id = i.relationship_id
                   WHERE i.status = 'OPEN' AND i.kind = 'RELATIONSHIP_CANDIDATE'
                     AND rel.to_resource_id IS NULL AND rel.target_origin IS NULL
                     AND rel.origin = 'AI_EXTRACTED' AND rel.verification = 'UNVERIFIED'
                     AND length(btrim(rel.to_title)) >= :minChars
                   FOR UPDATE OF i),
                 wanted AS (SELECT DISTINCT name FROM candidate),
                 named AS (%s),
                 known AS (
                   SELECT c.item, c.relationship, (array_agg(n.id))[1] AS target
                   FROM candidate c JOIN named n ON n.wanted = c.name AND n.id <> c.from_resource_id
                   GROUP BY c.item, c.relationship
                   HAVING count(*) = 1),
                 linked AS (
                   UPDATE relationship rel SET to_resource_id = k.target, verification = 'VERIFIED'
                   FROM known k WHERE rel.id = k.relationship
                   RETURNING k.item)
            UPDATE review_item
            SET status = 'SUPERSEDED', resolved_at = now(), resolved_by = :by, resolution_note = :note
            WHERE id IN (SELECT item FROM linked)
            """
                .formatted(TargetNames.NAMED))
        .param("minChars", ScoutPolicy.MIN_TARGET_NAME_CHARS)
        .param("byTitle", TargetNames.TYPES_BY_TITLE)
        .param("byRepositoryName", TargetNames.TYPES_BY_REPOSITORY_NAME)
        .param("by", by)
        .param("note", note)
        .update();
  }

  @Override
  @Transactional
  public int confirmOpenKnownTargetCandidates(String by, String vendorNote, String serviceNote) {
    KnownTargetLock.forUse(jdbc);
    return jdbc.sql(
            """
            WITH candidate AS (
                   SELECT i.id AS item, rel.id AS relationship, t.kind
                   FROM review_item i JOIN relationship rel ON rel.id = i.relationship_id
                        JOIN known_target_name n ON n.name_key = %s
                        JOIN known_target t ON t.id = n.target_id
                   WHERE i.status = 'OPEN' AND i.kind = 'RELATIONSHIP_CANDIDATE'
                     AND rel.to_resource_id IS NULL AND rel.target_origin IS NULL
                     AND rel.origin = 'AI_EXTRACTED' AND rel.verification = 'UNVERIFIED'
                   FOR UPDATE OF i),
                 confirmed AS (
                   UPDATE relationship rel SET verification = 'VERIFIED', target_kind = c.kind
                   FROM candidate c WHERE rel.id = c.relationship
                   RETURNING c.item, c.kind)
            UPDATE review_item i
            SET status = 'SUPERSEDED', resolved_at = now(), resolved_by = :by,
                resolution_note = CASE c.kind WHEN 'SERVICE' THEN :serviceNote ELSE :vendorNote END
            FROM confirmed c WHERE i.id = c.item
            """
                .formatted(KnownTarget.KEY_OF_TITLE_SQL.formatted("rel.to_title")))
        .param("by", by)
        .param("vendorNote", vendorNote)
        .param("serviceNote", serviceNote)
        .update();
  }

  private void retell(UUID itemId, String kind, String title, @Nullable UUID targetId) {
    jdbc.sql("UPDATE review_item SET reason = :reason, note = :note WHERE id = :id")
        .param("reason", CandidateText.reason(kind, title))
        .param("note", targetId == null ? CandidateText.CHOSEN_NAME : CandidateText.CHOSEN_RESOURCE)
        .param("id", itemId)
        .update();
  }

  private @Nullable Locked lock(UUID itemId) {
    return jdbc.sql(
            """
            SELECT i.status, i.kind, i.relationship_id, r.from_resource_id, r.kind AS rel_kind
            FROM review_item i LEFT JOIN relationship r ON r.id = i.relationship_id
            WHERE i.id = :id FOR UPDATE OF i
            """)
        .param("id", itemId)
        .query((rs, n) ->
            new Locked(
                rs.getString("status"),
                rs.getString("kind"),
                rs.getObject("relationship_id", UUID.class),
                rs.getObject("from_resource_id", UUID.class),
                rs.getString("rel_kind")))
        .optional()
        .orElse(null);
  }

  private @Nullable String titleOf(UUID resourceId) {
    return jdbc.sql("SELECT title FROM resource WHERE id = :id")
        .param("id", resourceId)
        .query(String.class)
        .optional()
        .orElse(null);
  }

  private boolean titleTaken(Locked item, String title) {
    return jdbc.sql(
            """
            SELECT EXISTS (SELECT 1 FROM relationship
                           WHERE from_resource_id = :from AND kind = :kind AND to_title = :title AND id <> :id)
            """)
        .param("from", item.from())
        .param("kind", item.relKind())
        .param("title", title)
        .param("id", item.relationshipId())
        .query(Boolean.class)
        .single();
  }
}
