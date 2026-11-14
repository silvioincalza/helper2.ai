package ai.helper2.catalog.internal;

import ai.helper2.catalog.CatalogRetention;
import ai.helper2.shared.NewsFeeds;
import ai.helper2.shared.WebSources;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;

class JdbcCatalogRetention implements CatalogRetention {

  private static final String UNREFERENCED =
      """
       AND NOT EXISTS (SELECT 1 FROM issue_report i WHERE i.resource_id = r.id)
       AND NOT EXISTS (SELECT 1 FROM review_item v WHERE v.resource_id = r.id)
      """;

  private final JdbcClient jdbc;

  JdbcCatalogRetention(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public int deleteObservedUnseenSince(Instant cutoff) {
    return jdbc.sql(
            """
            DELETE FROM resource r
            WHERE r.data_origin = 'OBSERVED' AND r.last_seen_at < :cutoff
              AND cardinality(r.curated_fields) = 0
              AND NOT EXISTS (SELECT 1 FROM relationship l JOIN resource f ON f.id = l.from_resource_id
                              WHERE l.to_resource_id = r.id AND f.data_origin <> 'OBSERVED')
            """
                + UNREFERENCED)
        .param("cutoff", Timestamp.from(cutoff))
        .update();
  }

  @Override
  public int deleteWebNewsRetrievedBefore(Instant cutoff) {
    List<UUID> owners =
        jdbc.sql(
                """
                DELETE FROM source_record
                WHERE (source_id IN (:sources) OR source_id LIKE :discovery OR source_id LIKE :feeds)
                  AND type IN ('NEWS', 'ARTICLE') AND retrieved_at < :cutoff
                RETURNING resource_id
                """)
            .param("sources", WebSources.FIXED)
            .param("discovery", WebSources.DISCOVERY_LIKE)
            .param("feeds", NewsFeeds.LIKE)
            .param("cutoff", Timestamp.from(cutoff))
            .query(UUID.class)
            .list();
    if (!owners.isEmpty()) {
      jdbc.sql(
              """
              DELETE FROM resource r
              WHERE r.id = ANY(:ids) AND r.data_origin IN ('OBSERVED', 'INDEXED')
                AND cardinality(r.curated_fields) = 0
                AND NOT EXISTS (SELECT 1 FROM source_record s WHERE s.resource_id = r.id)
              """
                  + UNREFERENCED)
          .param("ids", owners.stream().distinct().toArray(UUID[]::new))
          .update();
    }
    return owners.size();
  }

  @Override
  public int deleteListIndexUpdatedBefore(Instant cutoff) {
    int entries =
        jdbc.sql("DELETE FROM awesome_list_entry WHERE updated_at < :cutoff")
            .param("cutoff", Timestamp.from(cutoff))
            .update();
    int lists =
        jdbc.sql("DELETE FROM awesome_list WHERE updated_at < :cutoff")
            .param("cutoff", Timestamp.from(cutoff))
            .update();
    return entries + lists;
  }

  @Override
  public int deleteEventsEndedBefore(Instant cutoff) {
    return jdbc.sql(
            """
            WITH ended AS (
                DELETE FROM resource r
                WHERE r.type = 'EVENT' AND r.data_origin IN ('OBSERVED', 'INDEXED', 'CURATED')
                  AND coalesce(r.event_ends_at, r.event_starts_at) < :cutoff
            """
                + UNREFERENCED
                + """
                    RETURNING r.id),
                quotes AS (
                    DELETE FROM evidence e
                    WHERE e.id IN (SELECT j.evidence_id FROM field_assertion_evidence j
                                   JOIN field_assertion a ON a.id = j.assertion_id
                                   JOIN ended ON ended.id = a.resource_id))
                SELECT count(*) FROM ended
                """)
        .param("cutoff", Timestamp.from(cutoff))
        .query(Integer.class)
        .single();
  }

  @Override
  public int deleteEnrichmentRunsStartedBefore(Instant cutoff) {
    return jdbc.sql(
            """
            DELETE FROM enrichment_run e
            WHERE e.started_at < :cutoff
              AND EXISTS (SELECT 1 FROM enrichment_run n WHERE n.resource_id = e.resource_id
                          AND (n.started_at, n.id) > (e.started_at, e.id))
              AND NOT EXISTS (SELECT 1 FROM field_assertion x WHERE x.enrichment_run_id = e.id)
              AND NOT EXISTS (SELECT 1 FROM relationship x WHERE x.enrichment_run_id = e.id)
              AND NOT EXISTS (SELECT 1 FROM summary_claim x WHERE x.enrichment_run_id = e.id)
              AND NOT EXISTS (SELECT 1 FROM review_item x WHERE x.enrichment_run_id = e.id)
            """)
        .param("cutoff", Timestamp.from(cutoff))
        .update();
  }

  @Override
  public int deleteReviewItemsResolvedBefore(Instant cutoff) {
    return jdbc.sql(
            """
            DELETE FROM review_item WHERE status <> 'OPEN' AND resolved_at < :cutoff AND report_id IS NULL
            """)
        .param("cutoff", Timestamp.from(cutoff))
        .update();
  }

  @Override
  public int deleteIssueReportsResolvedBefore(Instant cutoff) {
    return jdbc.sql(
            """
            DELETE FROM issue_report i
            WHERE EXISTS (SELECT 1 FROM review_item v WHERE v.report_id = i.id)
              AND NOT EXISTS (SELECT 1 FROM review_item v WHERE v.report_id = i.id
                              AND (v.status = 'OPEN' OR v.resolved_at >= :cutoff))
            """)
        .param("cutoff", Timestamp.from(cutoff))
        .update();
  }
}
