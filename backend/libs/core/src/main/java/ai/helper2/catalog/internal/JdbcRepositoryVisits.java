package ai.helper2.catalog.internal;

import ai.helper2.catalog.enrichment.RepositoryVisits;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

class JdbcRepositoryVisits implements RepositoryVisits {

  private static final String GONE_CONFIRMED_AFTER = "24 hours";
  private static final String STARS_OF_THE_VISIT =
      """
      jsonb_build_array(jsonb_build_object('kind', 'GITHUB_STARS', 'value', CAST(:stars AS bigint), \
      'asOf', CAST(:visitedAt AS text)))\
      """;

  private final JdbcClient jdbc;

  JdbcRepositoryVisits(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public List<Due> due(int limit) {
    return jdbc.sql(
            """
            SELECT id,
                   substring(coalesce(repository_url, canonical_url) FROM '^https://github\\.com/(.+)$')
                       AS repository
            FROM source_record
            WHERE coalesce(repository_url, canonical_url)
                  ~ '^https://github\\.com/[A-Za-z0-9-]+/[A-Za-z0-9_.-]+$'
            ORDER BY CAST(extra -> 'vitality' ->> 'visitedAt' AS timestamptz) NULLS FIRST, id
            LIMIT :limit
            """)
        .param("limit", limit)
        .query((rs, i) -> new Due(rs.getObject("id", UUID.class), rs.getString("repository")))
        .list();
  }

  @Override
  public void record(UUID recordId, Visit visit, Instant visitedAt) {
    switch (visit) {
      case Found found -> {
        recordFound(recordId, found, visitedAt);
        if (found.stars() != null) {
          recordStars(recordId, found.stars(), visitedAt);
        }
      }
      case Gone gone -> recordNotFound(recordId, visitedAt);
      case Unanswered unanswered -> recordVisited(recordId, visitedAt);
    }
  }

  private void recordFound(UUID recordId, Found found, Instant visitedAt) {
    jdbc.sql(
            """
            UPDATE source_record
            SET extra = jsonb_set(extra, '{vitality}',
                (CASE WHEN jsonb_exists(extra -> 'vitality', 'archived')
                           AND NOT jsonb_exists(extra -> 'vitality', 'checkedAt')
                           AND CAST(extra -> 'vitality' ->> 'statedAt' AS timestamptz)
                               > CAST(:visitedAt AS timestamptz)
                      THEN extra -> 'vitality'
                      ELSE coalesce(extra -> 'vitality', '{}'::jsonb)
                           || jsonb_build_object('archived', :archived, 'checkedAt', CAST(:visitedAt AS text))
                 END - 'gone' - 'notFoundAt' - 'notFoundVisits' - 'parent' - 'parentArchived'
                     - 'lastReleaseAt')
                || CAST(:facts AS jsonb))
            WHERE id = :id
            """)
        .param("archived", found.archived())
        .param("visitedAt", visitedAt.toString())
        .param("facts", factsOf(found, visitedAt).toString())
        .param("id", recordId)
        .update();
  }

  // ADR-0020 §7: the stars go only on the record that is the repository; a record that links it
  // (a package) would borrow them. They are stored by the rule of StoredPopularity, and the trigger
  // source_record_popularity computes the popularity of the resource again (ADR-0025 §3).
  private void recordStars(UUID recordId, long stars, Instant visitedAt) {
    jdbc.sql(
            """
            UPDATE source_record
            SET extra = jsonb_set(extra, '{popularity}', %s)
            WHERE id = :id AND repository_url IS NULL
            """
                .formatted(StoredPopularity.merged("extra -> 'popularity'", STARS_OF_THE_VISIT)))
        .param("stars", stars)
        .param("visitedAt", visitedAt.toString())
        .param("id", recordId)
        .update();
  }

  private static ObjectNode factsOf(Found found, Instant visitedAt) {
    ObjectNode facts = JsonNodeFactory.instance.objectNode().put("visitedAt", visitedAt.toString());
    if (found.parent() != null) {
      facts.put("parent", found.parent().repository());
      facts.put("parentArchived", found.parent().archived());
    }
    if (found.lastReleaseAt() != null) {
      facts.put("lastReleaseAt", found.lastReleaseAt().toString());
    }
    return facts;
  }

  // UC-030 BR-012: the visits in a row that did not find the repository are counted; a visit that
  // finds it, or a source that states it again, starts the count again.
  private void recordNotFound(UUID recordId, Instant visitedAt) {
    jdbc.sql(
            """
            UPDATE source_record
            SET extra = jsonb_set(extra, '{vitality}',
                coalesce(extra -> 'vitality', '{}'::jsonb)
                || jsonb_build_object('visitedAt', CAST(:visitedAt AS text),
                       'notFoundVisits',
                       coalesce(CAST(extra -> 'vitality' ->> 'notFoundVisits' AS integer), 0) + 1)
                || CASE WHEN NOT coalesce(jsonb_exists(extra -> 'vitality', 'notFoundAt'), false)
                        THEN jsonb_build_object('notFoundAt', CAST(:visitedAt AS text))
                        WHEN repository_url IS NULL
                             AND CAST(extra -> 'vitality' ->> 'notFoundAt' AS timestamptz)
                                 <= CAST(:visitedAt AS timestamptz) - CAST(:confirmedAfter AS interval)
                        THEN jsonb_build_object('gone', true, 'checkedAt', CAST(:visitedAt AS text))
                        ELSE '{}'::jsonb
                   END)
            WHERE id = :id
            """)
        .param("visitedAt", visitedAt.toString())
        .param("confirmedAfter", GONE_CONFIRMED_AFTER)
        .param("id", recordId)
        .update();
  }

  private void recordVisited(UUID recordId, Instant visitedAt) {
    jdbc.sql(
            """
            UPDATE source_record
            SET extra = jsonb_set(extra, '{vitality}',
                coalesce(extra -> 'vitality', '{}'::jsonb)
                || jsonb_build_object('visitedAt', CAST(:visitedAt AS text)))
            WHERE id = :id
            """)
        .param("visitedAt", visitedAt.toString())
        .param("id", recordId)
        .update();
  }
}
