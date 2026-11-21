package ai.helper2.catalog.internal;

import ai.helper2.catalog.DirectoryRatingIndex;
import ai.helper2.shared.CanonicalUrl;
import ai.helper2.shared.ResourceId;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;

class JdbcDirectoryRatingIndex implements DirectoryRatingIndex {

  private final JdbcClient jdbc;
  private final ObjectMapper json;

  JdbcDirectoryRatingIndex(JdbcClient jdbc, ObjectMapper json) {
    this.jdbc = jdbc;
    this.json = json;
  }

  private static UUID idOf(CanonicalUrl resource) {
    return ResourceId.of(resource).getValue();
  }

  @Override
  public List<ItemPage> due(List<ItemPage> pages, Instant since) {
    if (pages.isEmpty()) {
      return List.of();
    }
    ArrayNode rows = json.createArrayNode();
    for (int n = 0; n < pages.size(); n++) {
      ItemPage page = pages.get(n);
      rows.addObject()
          .put("n", n)
          .put("list", page.list().getValue())
          .put("resource_id", idOf(page.resource()).toString())
          .put("page", page.page().getValue());
    }
    return jdbc
        .sql(
            """
            SELECT i.n
            FROM jsonb_to_recordset(CAST(:pages AS jsonb))
                 AS i(n int, list text, resource_id uuid, page text)
            JOIN resource r ON r.id = i.resource_id
            LEFT JOIN directory_rating d
                   ON d.list_url = i.list AND d.resource_id = i.resource_id AND d.page_url = i.page
            WHERE d.tried_at IS NULL OR d.tried_at <= :since
            ORDER BY d.tried_at NULLS FIRST, i.n
            """)
        .param("pages", json.writeValueAsString(rows))
        .param("since", Timestamp.from(since))
        .query(Integer.class)
        .list()
        .stream()
        .map(pages::get)
        .toList();
  }

  // UC-030 BR-011: a try is a record of a page of an item, tried on or after the given time.
  @Override
  public int itemPageTriesSince(Instant since) {
    return jdbc.sql(
            "SELECT count(*) FROM directory_rating WHERE page_url <> list_url AND tried_at >= :since")
        .param("since", Timestamp.from(since))
        .query(Integer.class)
        .single();
  }

  @Override
  public void tried(ItemPage page, String host, Instant at) {
    jdbc.sql(
            """
            INSERT INTO directory_rating (list_url, resource_id, host, page_url, tried_at)
            SELECT :list, r.id, :host, :page, :at FROM resource r WHERE r.id = :resource
            ON CONFLICT (list_url, resource_id) DO UPDATE SET
                host = EXCLUDED.host,
                tried_at = EXCLUDED.tried_at,
                average = CASE WHEN directory_rating.page_url = EXCLUDED.page_url THEN directory_rating.average END,
                scale = CASE WHEN directory_rating.page_url = EXCLUDED.page_url THEN directory_rating.scale END,
                votes = CASE WHEN directory_rating.page_url = EXCLUDED.page_url THEN directory_rating.votes END,
                read_at = CASE WHEN directory_rating.page_url = EXCLUDED.page_url THEN directory_rating.read_at END,
                page_url = EXCLUDED.page_url
            """)
        .param("list", page.list().getValue())
        .param("resource", idOf(page.resource()))
        .param("host", host)
        .param("page", page.page().getValue())
        .param("at", Timestamp.from(at))
        .update();
  }

  @Transactional
  @Override
  public void replace(ListRatings list) {
    String url = list.list().getValue();
    // UC-030 BR-010: a record goes when the list no longer names the resource, and the figures of
    // the list page go when the page no longer states them.
    jdbc.sql(
            """
            DELETE FROM directory_rating
            WHERE list_url = :list
              AND (resource_id <> ALL(:named) OR (page_url = :list AND resource_id <> ALL(:stated)))
            """)
        .param("list", url)
        .param("named", ids(list.named()))
        .param("stated", ids(list.onTheList().stream().map(Reading::resource).toList()))
        .update();
    jdbc.sql(
            """
            INSERT INTO directory_rating
                (list_url, resource_id, host, page_url, average, scale, votes, read_at, tried_at)
            SELECT DISTINCT ON (r.id)
                   :list, r.id, :host, i.page, i.average, i.scale, i.votes, i.read_at, i.read_at
            FROM jsonb_to_recordset(CAST(:readings AS jsonb))
                 AS i(n int, resource_id uuid, page text, average numeric, scale int, votes int, read_at timestamptz)
            JOIN resource r ON r.id = i.resource_id
            WHERE i.average IS NOT NULL
            ORDER BY r.id, i.n
            ON CONFLICT (list_url, resource_id) DO UPDATE SET
                host = EXCLUDED.host, page_url = EXCLUDED.page_url, average = EXCLUDED.average,
                scale = EXCLUDED.scale, votes = EXCLUDED.votes, read_at = EXCLUDED.read_at,
                tried_at = GREATEST(directory_rating.tried_at, EXCLUDED.tried_at)
            """)
        .param("list", url)
        .param("host", list.host())
        .param("readings", readings(list))
        .update();
    jdbc.sql(
            """
            UPDATE directory_rating d
            SET average = NULL, scale = NULL, votes = NULL, read_at = NULL
            FROM jsonb_to_recordset(CAST(:readings AS jsonb))
                 AS i(resource_id uuid, page text, average numeric)
            WHERE d.list_url = :list AND d.resource_id = i.resource_id AND d.page_url = i.page
              AND i.average IS NULL
            """)
        .param("list", url)
        .param("readings", readings(list))
        .update();
  }

  private static UUID[] ids(List<CanonicalUrl> resources) {
    return resources.stream().map(JdbcDirectoryRatingIndex::idOf).toArray(UUID[]::new);
  }

  private String readings(ListRatings list) {
    ArrayNode rows = json.createArrayNode();
    list.onTheList().forEach(reading -> add(rows, reading));
    list.onItemPages().forEach(reading -> add(rows, reading));
    return json.writeValueAsString(rows);
  }

  private static void add(ArrayNode rows, Reading reading) {
    var row =
        rows.addObject()
            .put("n", rows.size())
            .put("resource_id", idOf(reading.resource()).toString())
            .put("page", reading.page().getValue())
            .put("read_at", reading.readAt().toString());
    if (reading.figures() != null) {
      row.put("average", reading.figures().average())
          .put("scale", reading.figures().scale())
          .put("votes", reading.figures().votes());
    }
  }
}
