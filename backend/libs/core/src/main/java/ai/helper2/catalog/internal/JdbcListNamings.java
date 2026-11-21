package ai.helper2.catalog.internal;

import ai.helper2.catalog.ListNamings;
import ai.helper2.shared.CanonicalUrl;
import ai.helper2.shared.ResourceId;
import java.util.UUID;
import org.apache.commons.lang3.StringUtils;
import org.springframework.jdbc.core.simple.JdbcClient;

class JdbcListNamings implements ListNamings {

  private final JdbcClient jdbc;

  JdbcListNamings(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public boolean byCuratedList(
      CanonicalUrl list, UUID resourceId, String text, String headingPath) {
    return jdbc.sql(
                """
                INSERT INTO awesome_list_entry (list_id, resource_id, heading_path, search_vector, updated_at)
                SELECT l.id, r.id, :heading,
                       setweight(to_tsvector('english', h2_unaccent(translate(:text, '/', ' '))), 'A')
                       || setweight(to_tsvector('simple', h2_unaccent(h2_name_words(:text))), 'A')
                       || setweight(to_tsvector('english', h2_unaccent(translate(:heading, '/', ' '))), 'B'),
                       now()
                FROM resource l CROSS JOIN resource r
                WHERE l.id = :list AND r.id = :resource AND l.id <> r.id
                ON CONFLICT (list_id, resource_id) DO NOTHING
                """)
            .param("list", ResourceId.of(list).getValue())
            .param("resource", resourceId)
            .param("text", text)
            .param("heading", headingPath)
            .update()
        == 1;
  }

  @Override
  public void byDirectoryList(
      CanonicalUrl list, String title, UUID resourceId, String headingPath) {
    jdbc.sql(
            """
            INSERT INTO directory_list_entry (list_url, resource_id, list_title, heading_path, updated_at)
            SELECT :url, r.id, :title, :heading, now() FROM resource r WHERE r.id = :resource
            ON CONFLICT (list_url, resource_id) DO NOTHING
            """)
        .param("url", list.getValue())
        .param("title", StringUtils.left(title, JdbcDirectoryListIndex.MAX_TITLE))
        .param("resource", resourceId)
        .param("heading", headingPath)
        .update();
  }
}
