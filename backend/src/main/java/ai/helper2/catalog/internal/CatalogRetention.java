package ai.helper2.catalog.internal;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * The catalog's retention deletes (ADR 0034). Each method deletes what its rule names, older than the cutoff it is
 * given, and returns how many rows it deleted. Child rows go by their foreign keys (ON DELETE CASCADE or SET NULL).
 *
 * <p>A resource is deleted only while nothing a person must still see points at it: no issue report, no review
 * item. Reports and review items have their own periods, so such a resource goes in a later purge.
 */
@Repository
public class CatalogRetention {

    /** Sources whose web results (news, articles) age fastest: Tavily's live search and the Scout's Tavily queries. */
    static final List<String> WEB_NEWS_SOURCES = List.of("tavily", "tavily-scout");

    private static final String UNREFERENCED = """
             AND NOT EXISTS (SELECT 1 FROM issue_report i WHERE i.resource_id = r.id)
             AND NOT EXISTS (SELECT 1 FROM review_item v WHERE v.resource_id = r.id)
            """;

    private final JdbcClient jdbc;

    CatalogRetention(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Resources only searches saw (OBSERVED) and no search has seen since [cutoff], unless a resource of a stronger
     * origin links to them.
     */
    public int deleteObservedUnseenSince(Instant cutoff) {
        return jdbc.sql("""
                DELETE FROM resource r
                WHERE r.data_origin = 'OBSERVED' AND r.last_seen_at < :cutoff
                  AND NOT EXISTS (SELECT 1 FROM relationship l JOIN resource f ON f.id = l.from_resource_id
                                  WHERE l.to_resource_id = r.id AND f.data_origin <> 'OBSERVED')
                """ + UNREFERENCED).param("cutoff", Timestamp.from(cutoff)).update();
    }

    /**
     * Tavily's NEWS and ARTICLE records last retrieved before [cutoff], then the observed or indexed resources they
     * leave without any record. Returns the records deleted.
     */
    public int deleteWebNewsRetrievedBefore(Instant cutoff) {
        List<UUID> owners = jdbc.sql("""
                DELETE FROM source_record
                WHERE source_id IN (:sources) AND type IN ('NEWS', 'ARTICLE') AND retrieved_at < :cutoff
                RETURNING resource_id
                """).param("sources", WEB_NEWS_SOURCES).param("cutoff", Timestamp.from(cutoff))
            .query(UUID.class).list();
        if (!owners.isEmpty()) {
            jdbc.sql("""
                    DELETE FROM resource r
                    WHERE r.id = ANY(:ids) AND r.data_origin IN ('OBSERVED', 'INDEXED')
                      AND NOT EXISTS (SELECT 1 FROM source_record s WHERE s.resource_id = r.id)
                    """ + UNREFERENCED).param("ids", owners.stream().distinct().toArray(UUID[]::new)).update();
        }
        return owners.size();
    }

    /** Observed and indexed events that ended (or, without an end, started) before [cutoff]. */
    public int deleteEventsEndedBefore(Instant cutoff) {
        return jdbc.sql("""
                DELETE FROM resource r
                WHERE r.type = 'EVENT' AND r.data_origin IN ('OBSERVED', 'INDEXED')
                  AND coalesce(r.event_ends_at, r.event_starts_at) < :cutoff
                """ + UNREFERENCED).param("cutoff", Timestamp.from(cutoff)).update();
    }

    /**
     * Enrichment runs started before [cutoff], except each resource's latest run and any run a current assertion,
     * relationship, summary claim or review item still cites.
     */
    public int deleteEnrichmentRunsStartedBefore(Instant cutoff) {
        return jdbc.sql("""
                DELETE FROM enrichment_run e
                WHERE e.started_at < :cutoff
                  AND EXISTS (SELECT 1 FROM enrichment_run n WHERE n.resource_id = e.resource_id
                              AND (n.started_at, n.id) > (e.started_at, e.id))
                  AND NOT EXISTS (SELECT 1 FROM field_assertion x WHERE x.enrichment_run_id = e.id)
                  AND NOT EXISTS (SELECT 1 FROM relationship x WHERE x.enrichment_run_id = e.id)
                  AND NOT EXISTS (SELECT 1 FROM summary_claim x WHERE x.enrichment_run_id = e.id)
                  AND NOT EXISTS (SELECT 1 FROM review_item x WHERE x.enrichment_run_id = e.id)
                """).param("cutoff", Timestamp.from(cutoff)).update();
    }

    /** Review items decided (or superseded) before [cutoff]; an item about a user report goes with its report instead. */
    public int deleteReviewItemsResolvedBefore(Instant cutoff) {
        return jdbc.sql("""
                DELETE FROM review_item WHERE status <> 'OPEN' AND resolved_at < :cutoff AND report_id IS NULL
                """).param("cutoff", Timestamp.from(cutoff)).update();
    }

    /** Issue reports whose review items were all resolved before [cutoff]; their review items go with them. */
    public int deleteIssueReportsResolvedBefore(Instant cutoff) {
        return jdbc.sql("""
                DELETE FROM issue_report i
                WHERE EXISTS (SELECT 1 FROM review_item v WHERE v.report_id = i.id)
                  AND NOT EXISTS (SELECT 1 FROM review_item v WHERE v.report_id = i.id
                                  AND (v.status = 'OPEN' OR v.resolved_at >= :cutoff))
                """).param("cutoff", Timestamp.from(cutoff)).update();
    }
}
