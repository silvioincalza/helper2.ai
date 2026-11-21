package ai.helper2.catalog.internal;

import ai.helper2.catalog.DirectoryListIndex;
import ai.helper2.shared.ResourceId;
import org.apache.commons.lang3.StringUtils;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;

class JdbcDirectoryListIndex implements DirectoryListIndex {

  private static final int MAX_TITLE = 300;

  private final JdbcClient jdbc;
  private final ObjectMapper json;

  JdbcDirectoryListIndex(JdbcClient jdbc, ObjectMapper json) {
    this.jdbc = jdbc;
    this.json = json;
  }

  @Transactional
  @Override
  public void replace(DirectoryList list) {
    String url = list.url().getValue();
    jdbc.sql("DELETE FROM directory_list_entry WHERE list_url = :url").param("url", url).update();
    jdbc.sql(
            """
            INSERT INTO directory_list_entry (list_url, resource_id, list_title, heading_path, updated_at)
            SELECT :url, i.resource_id, :title, i.heading, now()
            FROM jsonb_to_recordset(CAST(:entries AS jsonb)) AS i(resource_id uuid, heading text)
            JOIN resource r ON r.id = i.resource_id
            ON CONFLICT (list_url, resource_id) DO NOTHING
            """)
        .param("url", url)
        .param("title", StringUtils.left(StringUtils.defaultIfBlank(list.title(), url), MAX_TITLE))
        .param("entries", entries(list))
        .update();
  }

  private String entries(DirectoryList list) {
    ArrayNode rows = json.createArrayNode();
    for (Entry e : list.entries()) {
      rows.addObject()
          .put("resource_id", ResourceId.of(e.resource()).getValue().toString())
          .put("heading", e.headingPath());
    }
    return json.writeValueAsString(rows);
  }
}
