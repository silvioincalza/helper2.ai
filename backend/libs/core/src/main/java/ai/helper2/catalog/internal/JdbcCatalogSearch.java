package ai.helper2.catalog.internal;

import ai.helper2.catalog.DataOrigin;
import ai.helper2.catalog.ResourceRow;
import ai.helper2.platform.PgVectors;
import ai.helper2.search.ListMatch;
import ai.helper2.search.Vitality;
import ai.helper2.shared.ResourceType;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class JdbcCatalogSearch {

  private final JdbcClient jdbc;
  private final ResourceRows rows;

  JdbcCatalogSearch(JdbcClient jdbc, ResourceRows rows) {
    this.jdbc = jdbc;
    this.rows = rows;
  }

  Map<UUID, Vitality> statedVitality(List<ResourceRow> found) {
    return rows.statedVitality(found.stream().map(ResourceRow::id).toArray(UUID[]::new));
  }

  private static boolean mayStateEventFacts(ResourceRow r) {
    return r.type() == ResourceType.EVENT || r.eventStartsAt() != null || r.eventEndsAt() != null;
  }

  Map<UUID, StatedEventFacts> statedEventFacts(List<ResourceRow> found) {
    return rows.statedEventFacts(
        found.stream()
            .filter(JdbcCatalogSearch::mayStateEventFacts)
            .map(ResourceRow::id)
            .toArray(UUID[]::new));
  }

  static final int MAX_VARIANTS = 4;

  static final int PRESELECT = 300;

  static final double TITLE_SIMILARITY = 0.35;

  static String plainWords(String text) {
    return Arrays.stream(text.replace('/', ' ').split("\\s+"))
        .map(w -> w.replace("\"", "").replaceFirst("^-+", ""))
        .filter(w -> !w.isEmpty() && !w.equalsIgnoreCase("or"))
        .collect(Collectors.joining(" "));
  }

  private static final String ANY_TERMS =
      "websearch_to_tsquery('english', h2_unaccent(:anyq))"
          + " || websearch_to_tsquery('simple', h2_unaccent(:anyq))"
          + " || websearch_to_tsquery(h2_ts_config(:lang), h2_unaccent(:anyq))";

  static final String MATCHES =
      "(r.search_vector @@ ("
          + ANY_TERMS
          + ")"
          + " OR (lower(r.title) % lower(:raw) AND similarity(lower(r.title), lower(:raw)) > :similarity))";

  static String searchSql(int variants) {
    StringBuilder allTerms = new StringBuilder();
    for (int i = 0; i < variants; i++) {
      if (i > 0) {
        allTerms.append(" || ");
      }
      String p = ":v" + i;
      allTerms
          .append("websearch_to_tsquery('english', h2_unaccent(")
          .append(p)
          .append(")) || ")
          .append("websearch_to_tsquery('simple', h2_unaccent(")
          .append(p)
          .append(")) || ")
          .append("websearch_to_tsquery(h2_ts_config(:lang), h2_unaccent(")
          .append(p)
          .append("))");
    }
    return """
    WITH q AS (
      SELECT (%1$s) AS all_terms, %2$s AS any_terms
    ), direct AS (
      SELECT r.id, (r.search_vector @@ q.all_terms) AS all_match,
             similarity(lower(r.title), lower(:raw)) AS sim, r.popularity AS pop, false AS via_list
      FROM resource r, q
      WHERE r.data_origin = ANY(:origins)
        AND %3$s
        AND (CAST(:after AS timestamptz) IS NULL OR r.published_at >= CAST(:after AS timestamptz))
    ), listed AS (
      SELECT x.id, bool_or(x.all_match) AS all_match
      FROM (
        SELECT e.resource_id AS id, (e.search_vector @@ q.all_terms) AS all_match
        FROM awesome_list_entry e, q
        WHERE e.search_vector @@ (%2$s)
        UNION ALL
        SELECT l.list_id, (l.search_vector @@ q.all_terms)
        FROM awesome_list l, q
        WHERE l.search_vector @@ (%2$s)
      ) x
      WHERE NOT EXISTS (SELECT 1 FROM direct d WHERE d.id = x.id)
      GROUP BY x.id
    ), matched AS (
      SELECT * FROM direct
      UNION ALL
      SELECT l.id, l.all_match, 0, r.popularity, true
      FROM listed l JOIN resource r ON r.id = l.id
      WHERE r.data_origin = ANY(:origins)
        AND (CAST(:after AS timestamptz) IS NULL OR r.published_at >= CAST(:after AS timestamptz))
    ), pre AS (
      SELECT * FROM matched m
      ORDER BY (CASE WHEN m.via_list THEN 0.5 ELSE 1 END)
               * ((CASE WHEN m.all_match THEN 3 ELSE 0 END) + m.pop + m.sim) DESC
      LIMIT :preselect
    )
    SELECT r.*, f.list_title AS found_list, f.heading_path AS found_heading,
           (CASE WHEN pre.via_list
                 THEN 0.5 * (CASE WHEN pre.all_match THEN 2 ELSE 0 END + pre.pop)
                 ELSE CASE WHEN pre.all_match THEN 2 ELSE 0 END + pre.pop
                      + 0.5 * least((ts_rank_cd(r.search_vector, q.any_terms) * 3 + pre.sim) / 10, 1)
            END) AS score
    FROM pre JOIN resource r ON r.id = pre.id CROSS JOIN q
    LEFT JOIN LATERAL (
      SELECT lr.title AS list_title, e.heading_path
      FROM awesome_list_entry e JOIN resource lr ON lr.id = e.list_id
      WHERE pre.via_list AND e.resource_id = pre.id AND e.search_vector @@ q.any_terms
      ORDER BY (e.search_vector @@ q.all_terms) DESC, lr.popularity DESC, lr.title
      LIMIT 1
    ) f ON true
    ORDER BY score DESC, r.title
    LIMIT :limit
    """
        .formatted(allTerms, ANY_TERMS, MATCHES);
  }

  public List<ResourceRow> search(
      String query,
      List<String> requiredVariants,
      List<String> extraTerms,
      List<String> synonyms,
      String language,
      Set<DataOrigin> origins,
      int limit,
      Instant publishedAfter) {
    return searchFound(
            query, requiredVariants, extraTerms, synonyms, language, origins, limit, publishedAfter)
        .stream()
        .map(Found::row)
        .toList();
  }

  public record Found(ResourceRow row, @Nullable ListMatch foundIn) {}

  public List<Found> searchFound(
      String query,
      List<String> requiredVariants,
      List<String> extraTerms,
      List<String> synonyms,
      String language,
      Set<DataOrigin> origins,
      int limit,
      Instant publishedAfter) {
    List<String> variants =
        requiredVariants.stream()
            .map(String::trim)
            .filter(v -> !v.isEmpty())
            .distinct()
            .limit(MAX_VARIANTS)
            .toList();
    if (variants.isEmpty()) {
      variants = List.of(query);
    }
    String expansions = String.join(" ", extraTerms);
    String anyText = plainWords(String.join(" ", query, expansions, String.join(" ", synonyms)));
    String orQuery = String.join(" OR ", anyText.split(" "));
    var spec =
        jdbc.sql(searchSql(variants.size()))
            .param("anyq", orQuery)
            .param("raw", query)
            .param("lang", language == null ? "simple" : language)
            .param("origins", origins.stream().map(Enum::name).toArray(String[]::new))
            .param("similarity", TITLE_SIMILARITY)
            .param("preselect", Math.max(PRESELECT, limit))
            .param("limit", limit)
            .param(
                "after",
                publishedAfter == null ? null : publishedAfter.atOffset(ZoneOffset.UTC),
                java.sql.Types.TIMESTAMP_WITH_TIMEZONE);
    for (int i = 0; i < variants.size(); i++) {
      spec = spec.param("v" + i, plainWords(variants.get(i)));
    }
    List<Found> found = spec.query(this::mapFound).list();
    List<ResourceRow> withFacts = rows.withFacts(found.stream().map(Found::row).toList());
    List<Found> out = new ArrayList<>(found.size());
    for (int i = 0; i < found.size(); i++) {
      out.add(new Found(withFacts.get(i), found.get(i).foundIn()));
    }
    return out;
  }

  private Found mapFound(ResultSet rs, int i) throws SQLException {
    String list = rs.getString("found_list");
    String heading = rs.getString("found_heading");
    return new Found(
        rows.mapResource(rs, i),
        list == null ? null : new ListMatch(list, heading == null ? "" : heading));
  }

  @Transactional
  public List<ResourceRow> semanticSearch(
      float[] query, String model, Set<DataOrigin> origins, int limit, double maxDistance) {
    jdbc.sql("SET LOCAL hnsw.iterative_scan = relaxed_order").update();
    return rows.withFacts(
        jdbc.sql(
                """
                SELECT * FROM (
                    SELECT r.*, 1 - (e.embedding <=> CAST(:v AS vector)) AS score
                    FROM resource_embedding e JOIN resource r ON r.id = e.resource_id
                    WHERE e.model = :model AND r.data_origin = ANY(:origins)
                      AND (e.embedding <=> CAST(:v AS vector)) <= :maxDistance
                    ORDER BY e.embedding <=> CAST(:v AS vector)
                    LIMIT :limit
                ) nearest
                ORDER BY score DESC
                """)
            .param("v", PgVectors.literal(query))
            .param("model", model)
            .param("origins", origins.stream().map(Enum::name).toArray(String[]::new))
            .param("maxDistance", maxDistance)
            .param("limit", limit)
            .query(rows::mapResource)
            .list());
  }

  public List<ResourceRow> browse(
      Instant publishedAfter, Instant now, Set<String> types, Set<DataOrigin> origins, int limit) {
    return rows.withFacts(
        jdbc.sql(
                """
                SELECT r.*, r.popularity AS score FROM resource r
                WHERE r.published_at IS NOT NULL AND r.published_at >= :after AND r.published_at <= :now
                  AND r.data_origin = ANY(:origins)
                  AND (cardinality(:types) = 0 OR r.type = ANY(:types))
                ORDER BY r.popularity DESC, r.published_at DESC, r.title
                LIMIT :limit
                """)
            .param("after", Timestamp.from(publishedAfter))
            .param("now", Timestamp.from(now))
            .param("origins", origins.stream().map(Enum::name).toArray(String[]::new))
            .param("types", types.toArray(String[]::new))
            .param("limit", limit)
            .query(rows::mapResource)
            .list());
  }
}
