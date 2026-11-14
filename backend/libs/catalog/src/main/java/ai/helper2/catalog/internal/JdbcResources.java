package ai.helper2.catalog.internal;

import ai.helper2.catalog.DataOrigin;
import ai.helper2.catalog.RecordRow;
import ai.helper2.catalog.ResourceRow;
import ai.helper2.catalog.Resources;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcResources implements Resources {

  private final JdbcClient jdbc;
  private final ResourceRows rows;

  JdbcResources(JdbcClient jdbc, ResourceRows rows) {
    this.jdbc = jdbc;
    this.rows = rows;
  }

  @Override
  public List<WellKnown> wellKnown(int limit, Collection<UUID> excluding) {
    return jdbc.sql(
            """
            SELECT id, title, type, audience, description FROM resource
            WHERE data_origin IN ('CURATED', 'INDEXED')
              AND type IN ('PRODUCT', 'MCP_SERVER', 'LIBRARY', 'MODEL', 'AGENT')
              AND (data_origin = 'CURATED' OR description IS NOT NULL)
              AND NOT (id = ANY(:excluding))
            ORDER BY (data_origin = 'CURATED') DESC, random()
            LIMIT :limit
            """)
        .param("excluding", excluding.toArray(UUID[]::new))
        .param("limit", limit)
        .query((rs, n) ->
            new WellKnown(
                rs.getObject("id", UUID.class),
                rs.getString("title"),
                rs.getString("type"),
                rs.getString("audience"),
                rs.getString("description")))
        .list();
  }

  @Override
  public Optional<Titled> atUrl(String canonicalUrl) {
    return jdbc.sql(
            """
            SELECT r.id, r.title FROM resource r
            WHERE r.canonical_url = :url
               OR r.id IN (SELECT resource_id FROM source_record WHERE canonical_url = :url)
            ORDER BY (r.canonical_url = :url) DESC LIMIT 1
            """)
        .param("url", canonicalUrl)
        .query((rs, n) -> new Titled(rs.getObject("id", UUID.class), rs.getString("title")))
        .optional();
  }

  @Override
  public List<Indexable> indexable(Collection<String> types, int promptMin, int limit) {
    record Row(
        UUID id, String title, String canonicalUrl, String dataOrigin, Instant lastModified) {}
    List<Row> rows =
        jdbc.sql(
                """
                SELECT r.id, r.title, r.canonical_url, r.data_origin,
                       coalesce(r.updated_at, r.modified_at) AS last_modified
                FROM resource r
                WHERE r.description_origin IN ('SOURCE_PROVIDED', 'CURATED', 'COMMUNITY')
                  AND length(btrim(coalesce(r.description, ''))) > 0
                  AND (
                    (r.type = 'PROMPT' AND length(btrim(r.description)) >= :promptMin)
                    OR (r.type = ANY(:types)
                        AND (r.license IS NOT NULL OR r.pricing IS NOT NULL OR r.published_at IS NOT NULL
                             OR r.event_starts_at IS NOT NULL
                             OR EXISTS (SELECT 1 FROM relationship rel
                                        WHERE rel.from_resource_id = r.id AND rel.to_resource_id IS NOT NULL
                                          AND rel.verification <> 'REJECTED'))))
                ORDER BY last_modified DESC, r.id
                LIMIT :limit
                """)
            .param("types", types.toArray(String[]::new))
            .param("promptMin", promptMin)
            .param("limit", limit)
            .query((rs, n) ->
                new Row(
                    rs.getObject("id", UUID.class),
                    rs.getString("title"),
                    rs.getString("canonical_url"),
                    rs.getString("data_origin"),
                    rs.getTimestamp("last_modified").toInstant()))
            .list();
    if (rows.isEmpty()) {
      return List.of();
    }
    Set<UUID> pageTitled = this.rows.pageTitled(rows.stream().map(Row::id).toArray(UUID[]::new));
    return rows.stream()
        .map(r ->
            new Indexable(
                r.id(),
                pageTitled.contains(r.id()) && !DataOrigin.CURATED.name().equals(r.dataOrigin())
                    ? ResourceRow.pageTitle(r.title(), r.canonicalUrl())
                    : r.title(),
                r.lastModified()))
        .toList();
  }

  @Override
  public Map<UUID, String> titles(Collection<UUID> ids) {
    if (ids.isEmpty()) {
      return Map.of();
    }
    Map<UUID, String> titles = new LinkedHashMap<>();
    jdbc.sql("SELECT id, title FROM resource WHERE id = ANY(:ids)")
        .param("ids", ids.toArray(UUID[]::new))
        .query((rs, n) -> titles.put(rs.getObject("id", UUID.class), rs.getString("title")))
        .list();
    return titles;
  }

  private static final Instant NO_END = Instant.parse("9999-12-31T00:00:00Z");

  @Override
  public List<UUID> upcomingEvents(Instant today, @Nullable Instant until, int limit) {
    return jdbc.sql(
            """
            SELECT id FROM resource
            WHERE type = 'EVENT' AND event_starts_at IS NOT NULL
              AND coalesce(event_ends_at, event_starts_at) >= :today
              AND event_starts_at < :until
            ORDER BY event_starts_at, title
            LIMIT :limit
            """)
        .param("today", Timestamp.from(today))
        .param("until", Timestamp.from(until == null ? NO_END : until))
        .param("limit", limit)
        .query((rs, i) -> rs.getObject(1, UUID.class))
        .list();
  }

  @Override
  public int countUpcomingEvents(Instant today, @Nullable Instant until) {
    return jdbc.sql(
            """
            SELECT count(*) FROM resource
            WHERE type = 'EVENT' AND event_starts_at IS NOT NULL
              AND coalesce(event_ends_at, event_starts_at) >= :today
              AND event_starts_at < :until
            """)
        .param("today", Timestamp.from(today))
        .param("until", Timestamp.from(until == null ? NO_END : until))
        .query(Integer.class)
        .single();
  }

  @Override
  public long discoveredInIndexSince(Instant since, Set<DataOrigin> origins) {
    return jdbc.sql(
            """
            SELECT count(*) FROM resource
            WHERE first_discovered_at >= :since AND data_origin = ANY(:origins)
            """)
        .param("since", Timestamp.from(since))
        .param("origins", origins.stream().map(Enum::name).toArray(String[]::new))
        .query(Long.class)
        .single();
  }

  @Override
  public Map<String, Long> discoveredSince(Instant since) {
    Map<String, Long> out = new HashMap<>();
    jdbc.sql(
            """
            SELECT sr.source_id, count(DISTINCT r.id)
            FROM resource r JOIN source_record sr ON sr.resource_id = r.id
            WHERE r.first_discovered_at >= :since
            GROUP BY sr.source_id
            """)
        .param("since", Timestamp.from(since))
        .query((rs, i) -> out.put(rs.getString(1), rs.getLong(2)))
        .list();
    return out;
  }

  @Override
  public Map<UUID, Instant> firstDiscovered(Collection<UUID> ids) {
    if (ids.isEmpty()) {
      return Map.of();
    }
    Map<UUID, Instant> out = new HashMap<>();
    jdbc.sql("SELECT id, first_discovered_at FROM resource WHERE id = ANY(:ids)")
        .param("ids", ids.toArray(UUID[]::new))
        .query((rs, i) -> out.put(rs.getObject(1, UUID.class), rs.getTimestamp(2).toInstant()))
        .list();
    return out;
  }

  @Override
  public int countRecentlyPublished(
      Instant since, Instant now, Set<String> types, Set<DataOrigin> origins) {
    return jdbc.sql(
            """
            SELECT count(*) FROM resource
            WHERE published_at IS NOT NULL AND published_at >= :since AND published_at <= :now
              AND data_origin = ANY(:origins)
              AND (cardinality(:types) = 0 OR type = ANY(:types))
            """)
        .param("since", Timestamp.from(since))
        .param("now", Timestamp.from(now))
        .param("origins", origins.stream().map(Enum::name).toArray(String[]::new))
        .param("types", types.toArray(String[]::new))
        .query(Integer.class)
        .single();
  }

  @Override
  public List<UUID> recentlyPublished(
      Instant since, Instant now, Set<String> types, Set<DataOrigin> origins, int limit) {
    return jdbc.sql(
            """
            SELECT id FROM resource
            WHERE published_at IS NOT NULL AND published_at >= :since AND published_at <= :now
              AND data_origin = ANY(:origins)
              AND (cardinality(:types) = 0 OR type = ANY(:types))
            ORDER BY published_at DESC, title
            LIMIT :limit
            """)
        .param("since", Timestamp.from(since))
        .param("now", Timestamp.from(now))
        .param("origins", origins.stream().map(Enum::name).toArray(String[]::new))
        .param("types", types.toArray(String[]::new))
        .param("limit", limit)
        .query((rs, i) -> rs.getObject(1, UUID.class))
        .list();
  }

  @Override
  public Optional<ResourceRow> findResource(UUID id) {
    return jdbc.sql("SELECT r.*, 0 AS score FROM resource r WHERE id = :id")
        .param("id", id)
        .query(rows::mapResource)
        .optional()
        .map(r -> rows.withFacts(List.of(r)).getFirst());
  }

  @Override
  public List<ResourceRow> findResources(Collection<UUID> ids) {
    if (ids.isEmpty()) {
      return List.of();
    }
    return rows.withFacts(
        jdbc.sql("SELECT r.*, 0 AS score FROM resource r WHERE id = ANY(:ids)")
            .param("ids", ids.stream().distinct().toArray(UUID[]::new))
            .query(rows::mapResource)
            .list());
  }

  @Override
  public Optional<UUID> ownerOfRecordUrl(UUID id) {
    return jdbc.sql(
            """
            SELECT resource_id FROM source_record
            WHERE uuid_generate_v5('6f1c2a8e-4b1d-5c3e-9a7f-2d4e6b8c0a12'::uuid, canonical_url) = :id
            ORDER BY retrieved_at DESC LIMIT 1
            """)
        .param("id", id)
        .query(UUID.class)
        .optional();
  }

  @Override
  public List<RecordRow> recordsOf(UUID resourceId) {
    return jdbc.sql(
            "SELECT * FROM source_record WHERE resource_id = :id ORDER BY source_id, retrieved_at DESC")
        .param("id", resourceId)
        .query(rows::mapRecord)
        .list();
  }

  @Override
  public Map<UUID, List<RecordRow>> recordsOf(Collection<UUID> resourceIds) {
    if (resourceIds.isEmpty()) {
      return Map.of();
    }
    Map<UUID, List<RecordRow>> byResource = new HashMap<>();
    jdbc.sql(
            """
            SELECT * FROM source_record WHERE resource_id = ANY(:ids)
            ORDER BY resource_id, source_id, retrieved_at DESC
            """)
        .param("ids", resourceIds.stream().distinct().toArray(UUID[]::new))
        .query(rows::mapRecord)
        .list()
        .forEach(r -> byResource.computeIfAbsent(r.resourceId(), k -> new ArrayList<>()).add(r));
    return byResource;
  }

  @Override
  public List<UUID> resourcesOfRecords(Collection<UUID> recordIds) {
    if (recordIds.isEmpty()) {
      return List.of();
    }
    return jdbc.sql("SELECT DISTINCT resource_id FROM source_record WHERE id = ANY(:ids)")
        .param("ids", recordIds.toArray(UUID[]::new))
        .query(UUID.class)
        .list();
  }
}
