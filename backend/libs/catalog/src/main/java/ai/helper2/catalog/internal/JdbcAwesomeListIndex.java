package ai.helper2.catalog.internal;

import ai.helper2.catalog.AwesomeListIndex;
import ai.helper2.catalog.AwesomeListText;
import ai.helper2.shared.ResourceId;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;

class JdbcAwesomeListIndex implements AwesomeListIndex {

  private final JdbcClient jdbc;
  private final ObjectMapper json;

  JdbcAwesomeListIndex(JdbcClient jdbc, ObjectMapper json) {
    this.jdbc = jdbc;
    this.json = json;
  }

  @Transactional
  @Override
  public Replaced replace(AwesomeListText list) {
    UUID listId = ResourceId.of(list.url()).getValue();
    if (!exists(listId)) {
      return Replaced.NONE;
    }
    List<UUID> previous =
        jdbc.sql("DELETE FROM awesome_list_entry WHERE list_id = :list RETURNING resource_id")
            .param("list", listId)
            .query(UUID.class)
            .list();
    List<UUID> written =
        jdbc.sql(
                """
                INSERT INTO awesome_list_entry (list_id, resource_id, heading_path, search_vector, updated_at)
                SELECT :list, i.resource_id, i.heading,
                       setweight(to_tsvector('english', h2_unaccent(translate(i.text, '/', ' '))), 'A')
                       || setweight(to_tsvector('simple', h2_unaccent(h2_name_words(i.text))), 'A')
                       || setweight(to_tsvector('english', h2_unaccent(translate(i.heading, '/', ' '))), 'B'),
                       now()
                FROM jsonb_to_recordset(CAST(:entries AS jsonb)) AS i(resource_id uuid, text text, heading text)
                JOIN resource r ON r.id = i.resource_id
                WHERE i.resource_id <> :list
                ON CONFLICT (list_id, resource_id) DO NOTHING
                RETURNING resource_id
                """)
            .param("list", listId)
            .param("entries", entries(list.entries()))
            .query(UUID.class)
            .list();
    jdbc.sql(
            """
            INSERT INTO awesome_list (list_id, search_vector, updated_at)
            VALUES (:list, to_tsvector('english', h2_unaccent(translate(:headings, '/', ' '))), now())
            ON CONFLICT (list_id) DO UPDATE SET search_vector = EXCLUDED.search_vector, updated_at = now()
            """)
        .param("list", listId)
        .param("headings", list.headings())
        .update();
    List<UUID> removed = previous.stream().filter(id -> !written.contains(id)).toList();
    return new Replaced(written, removed);
  }

  private boolean exists(UUID id) {
    return jdbc.sql("SELECT EXISTS (SELECT 1 FROM resource WHERE id = :id)")
        .param("id", id)
        .query(Boolean.class)
        .single();
  }

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
