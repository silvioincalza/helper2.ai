package ai.helper2.catalog.internal;

import ai.helper2.catalog.GoneLists.Forgotten;
import ai.helper2.catalog.GoneWebLists;
import ai.helper2.shared.CanonicalUrl;
import ai.helper2.shared.CollectorIds;
import ai.helper2.shared.ResourceId;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

class JdbcGoneWebLists implements GoneWebLists {

  private final JdbcClient jdbc;

  JdbcGoneWebLists(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  // UC-030 BR-012: a web page is a list page of a directory host or a curated list, and its host
  // can change sides between two reads, so the rows of both kinds go by the address of the page.
  @Transactional
  @Override
  public Forgotten forget(CanonicalUrl list, Instant at) {
    String url = list.getValue();
    UUID listId = ResourceId.of(list).getValue();
    Set<UUID> named = new LinkedHashSet<>();
    named.addAll(
        jdbc.sql("DELETE FROM directory_list_entry WHERE list_url = :url RETURNING resource_id")
            .param("url", url)
            .query(UUID.class)
            .list());
    int ratings =
        jdbc.sql("DELETE FROM directory_rating WHERE list_url = :url").param("url", url).update();
    named.addAll(
        jdbc.sql("DELETE FROM awesome_list_entry WHERE list_id = :list RETURNING resource_id")
            .param("list", listId)
            .query(UUID.class)
            .list());
    int index =
        jdbc.sql("DELETE FROM awesome_list WHERE list_id = :list").param("list", listId).update();
    int marked = markGone(url, at);
    boolean held = !named.isEmpty() || ratings + index + marked > 0;
    return new Forgotten(held ? 1 : 0, List.copyOf(named));
  }

  // UC-030 BR-012: the own resource of a curated web page follows the rule of a repository that
  // is gone (UC-018 BR-009): its record is marked gone, search reads that as archived, and the
  // resource is not deleted. A later read that gives the page states it again and removes the mark.
  private int markGone(String url, Instant at) {
    return jdbc.sql(
            """
            UPDATE source_record
            SET extra = jsonb_set(extra, '{vitality}',
                coalesce(extra -> 'vitality', '{}'::jsonb)
                || jsonb_build_object('gone', true, 'checkedAt', CAST(:at AS text)))
            WHERE source_id = :source AND external_id = :url AND repository_url IS NULL
              AND NOT coalesce(CAST(extra -> 'vitality' ->> 'gone' AS boolean), false)
            """)
        .param("at", at.toString())
        .param("source", CollectorIds.CURATED_WEB_LISTS)
        .param("url", url)
        .update();
  }
}
