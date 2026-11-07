package ai.helper2.catalog.internal;

import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * The target of the relationship an open review item is about (ADR 0026): a person points it at a
 * catalog resource, or gives it another name. The item row is locked first, so a decision made at
 * the same time waits for the change or makes it fail.
 */
@Component
public class RelationshipTargets {

  /** What a change did, or why it did nothing. */
  public sealed interface Outcome {
    record Changed(@Nullable UUID targetId, String title) implements Outcome {}

    record NotFound() implements Outcome {}

    record NotOpen() implements Outcome {}

    record NotARelationship() implements Outcome {}

    record UnknownTarget() implements Outcome {}

    record SelfLink() implements Outcome {}

    record TitleTaken() implements Outcome {}
  }

  /** The locked item, and the relationship it is about when it has one. */
  private record Locked(
      String status,
      String kind,
      @Nullable UUID relationshipId,
      @Nullable UUID from,
      @Nullable String relKind) {}

  private final JdbcClient jdbc;

  RelationshipTargets(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  /**
   * Points the relationship of open item [itemId] at resource [targetId] under its catalog title,
   * or, with no target, renames it to [title]. Either way the target becomes CURATED: a person
   * chose it. The relationship's origin stays, since the kind is still the model's. Call inside a
   * transaction.
   */
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
    return new Outcome.Changed(targetId, newTitle);
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

  /** Another relationship of the same resource and kind has this target (one per target). */
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
