package ai.helper2.catalog.internal;

import ai.helper2.catalog.KnownTarget;
import ai.helper2.catalog.KnownTargetCandidates;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;

class JdbcKnownTargetCandidates implements KnownTargetCandidates {

  private static final String OWNER = "split_part(s.title, '/', 1)";

  private static final String OWNER_KEY = KnownTarget.KEY_OF_TITLE_SQL.formatted(OWNER);

  private static final String OWNED =
      """
      FROM source_record s JOIN resource r ON r.id = s.resource_id
      WHERE s.source_id = ANY(:sources) AND r.data_origin IN ('INDEXED', 'CURATED')
        AND s.title ~ '^[A-Za-z0-9][A-Za-z0-9._-]*/[^/[:space:]]+$'
      """;

  private static final String OPENED_TODAY =
      """
      (SELECT count(*) FROM review_item t
       WHERE t.kind = 'KNOWN_TARGET_CANDIDATE'
         AND t.created_at >= date_trunc('day', now() AT TIME ZONE 'UTC') AT TIME ZONE 'UTC')
      """;

  private static final String PROPOSE_OWNERS =
      """
      WITH owners AS (
             SELECT %1$s AS key, mode() WITHIN GROUP (ORDER BY %2$s) AS name, count(DISTINCT r.id) AS resources
             %3$s
             GROUP BY 1 HAVING count(DISTINCT r.id) >= :min),
           fresh AS (
             SELECT o.key, o.name, o.resources, row_number() OVER (ORDER BY o.resources DESC, o.key) AS position
             FROM owners o
             WHERE length(o.key) BETWEEN 2 AND 60 AND length(o.name) BETWEEN 2 AND 60
               AND NOT EXISTS (SELECT 1 FROM known_target_name n WHERE n.name_key = o.key)
               AND NOT EXISTS (SELECT 1 FROM review_item i
                               WHERE i.kind = 'KNOWN_TARGET_CANDIDATE' AND i.details ->> 'key' = o.key)
               AND NOT EXISTS (SELECT 1 FROM candidate_decision d
                               WHERE d.kind = 'KNOWN_TARGET_CANDIDATE' AND d.candidate_key = %4$s)),
           opened AS (
             INSERT INTO review_item (id, kind, reason, details)
             SELECT gen_random_uuid(), 'KNOWN_TARGET_CANDIDATE',
                    'Owner of ' || f.resources || ' GitHub or Hugging Face resources: ' || f.name,
                    jsonb_build_object(
                      'key', f.key, 'name', f.name, 'kind', 'VENDOR', 'rule', 'OWNER', 'count', f.resources,
                      'otherNames', '[]'::jsonb,
                      'examples', (
                        SELECT coalesce(jsonb_agg(jsonb_build_object('id', e.id, 'title', e.title)
                                                  ORDER BY e.popularity DESC, e.id), '[]'::jsonb)
                        FROM (SELECT DISTINCT r.id, r.title, r.popularity
                              %3$s
                                AND %1$s = f.key
                              ORDER BY r.popularity DESC, r.id LIMIT 5) e))
             FROM fresh f
             WHERE f.position <= :perDay - %5$s
             ON CONFLICT DO NOTHING
             RETURNING 1)
      SELECT (SELECT count(*) FROM opened) AS opened, (SELECT count(*) FROM fresh) AS found
      """
          .formatted(OWNER_KEY, OWNER, OWNED, UrlKeys.keyOf("o.key"), OPENED_TODAY);

  private final JdbcClient jdbc;

  JdbcKnownTargetCandidates(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public Proposed proposeOwners(Collection<String> sourceIds, int minResources, int perDay) {
    return jdbc.sql(PROPOSE_OWNERS)
        .param("sources", sourceIds.toArray(String[]::new))
        .param("min", minResources)
        .param("perDay", perDay)
        .query(
            (rs, i) -> new Proposed(rs.getInt("opened"), rs.getInt("found") - rs.getInt("opened")))
        .single();
  }

  @Override
  public Optional<Candidate> of(UUID reviewItemId) {
    return jdbc.sql(
            """
            SELECT details ->> 'name' AS name, details ->> 'kind' AS kind,
                   ARRAY(SELECT jsonb_array_elements_text(CASE
                           WHEN jsonb_typeof(details -> 'otherNames') = 'array'
                           THEN details -> 'otherNames' ELSE '[]'::jsonb END)) AS other_names
            FROM review_item WHERE id = :id AND kind = 'KNOWN_TARGET_CANDIDATE'
            """)
        .param("id", reviewItemId)
        .query((rs, i) ->
            new Candidate(
                rs.getString("name"),
                KnownTarget.Kind.valueOf(rs.getString("kind")),
                Arrays.asList((String[]) rs.getArray("other_names").getArray())))
        .optional();
  }

  @Override
  public boolean change(UUID reviewItemId, KnownTarget.Kind kind, List<String> names) {
    return jdbc.sql(
                """
                UPDATE review_item
                SET details = details || jsonb_build_object(
                      'name', CAST(:name AS text), 'kind', CAST(:kind AS text),
                      'otherNames', to_jsonb(CAST(:others AS text[])), 'edited', true)
                WHERE id = :id AND kind = 'KNOWN_TARGET_CANDIDATE' AND status = 'OPEN'
                """)
            .param("id", reviewItemId)
            .param("name", names.getFirst())
            .param("kind", kind.name())
            .param("others", names.subList(1, names.size()).toArray(String[]::new))
            .update()
        == 1;
  }
}
