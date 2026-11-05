package ai.helper2.catalog.internal;

import ai.helper2.catalog.AwesomeListText;
import ai.helper2.shared.ResourceId;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;

/**
 * The search index of awesome lists (ADR 0055): per list, one tsvector per entry (entry words A,
 * heading words B) with its heading path, and one tsvector of the list's headings. The text comes
 * in as bind parameters and becomes a tsvector inside PostgreSQL; it is never written to a column,
 * never logged. Only the heading path is kept as text. English stemming, like a resource's
 * description; the search query ORs English in (ResourceStore).
 */
@Repository
public class AwesomeListIndex {

  private final JdbcClient jdbc;
  private final ObjectMapper json;

  AwesomeListIndex(JdbcClient jdbc, ObjectMapper json) {
    this.jdbc = jdbc;
    this.json = json;
  }

  /**
   * Replaces what [list] had in the index, in one transaction. Entries whose page is not in the
   * catalog are left out (the next pass adds them once the collector indexed them). Returns the
   * entries written; 0, writing nothing, when the list itself is not in the catalog.
   */
  @Transactional
  public int replace(AwesomeListText list) {
    UUID listId = ResourceId.of(list.url()).getValue();
    if (!exists(listId)) {
      return 0;
    }
    jdbc.sql("DELETE FROM awesome_list_entry WHERE list_id = :list").param("list", listId).update();
    int written =
        jdbc.sql(
                """
                INSERT INTO awesome_list_entry (list_id, resource_id, heading_path, search_vector, updated_at)
                SELECT :list, i.resource_id, i.heading,
                       setweight(to_tsvector('english', h2_unaccent(i.text)), 'A')
                       || setweight(to_tsvector('english', h2_unaccent(i.heading)), 'B'),
                       now()
                FROM jsonb_to_recordset(CAST(:entries AS jsonb)) AS i(resource_id uuid, text text, heading text)
                JOIN resource r ON r.id = i.resource_id
                WHERE i.resource_id <> :list
                ON CONFLICT (list_id, resource_id) DO NOTHING
                """)
            .param("list", listId)
            .param("entries", entries(list.entries()))
            .update();
    jdbc.sql(
            """
            INSERT INTO awesome_list (list_id, search_vector, updated_at)
            VALUES (:list, to_tsvector('english', h2_unaccent(:headings)), now())
            ON CONFLICT (list_id) DO UPDATE SET search_vector = EXCLUDED.search_vector, updated_at = now()
            """)
        .param("list", listId)
        .param("headings", list.headings())
        .update();
    return written;
  }

  private boolean exists(UUID id) {
    return jdbc.sql("SELECT EXISTS (SELECT 1 FROM resource WHERE id = :id)")
        .param("id", id)
        .query(Boolean.class)
        .single();
  }

  /** The entries as one JSON array parameter (jsonb_to_recordset), never spliced into the SQL. */
  private String entries(List<AwesomeListText.Entry> entries) {
    ArrayNode rows = json.createArrayNode();
    for (AwesomeListText.Entry e : entries) {
      rows.addObject()
          .put("resource_id", ResourceId.of(e.url()).getValue().toString())
          .put("text", e.text())
          .put("heading", e.headingPath());
    }
    return json.writeValueAsString(rows);
  }
}
