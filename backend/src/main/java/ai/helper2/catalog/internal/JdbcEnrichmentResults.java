package ai.helper2.catalog.internal;

import ai.helper2.catalog.EnrichmentResults;
import ai.helper2.catalog.ResourceDetail;
import ai.helper2.catalog.ReviewQueue;
import ai.helper2.shared.ValueOrigin;
import ai.helper2.shared.Verification;
import java.net.URI;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * Enrichment results in Postgres, with full history. Every field and relationship the validator did not reject is
 * written with its verification state (VERIFIED, UNVERIFIED or CONFLICTING), together with the validated summary
 * claims; readers decide what to show by that state. Every problem opens a review item. A new run with model output
 * replaces the AI-extracted fields and the unreviewed AI-extracted relationships of earlier runs, and supersedes
 * their open review items, so a page and the review queue never mix two runs. Relationships a reviewer decided stay.
 * Runs themselves are kept for audit. Also reads them back for the resource detail page.
 */
@Component
public class JdbcEnrichmentResults implements EnrichmentResults {

    private final JdbcClient jdbc;
    private final ReviewQueue reviewQueue;
    private final ObjectMapper json;
    private final Clock clock;

    JdbcEnrichmentResults(JdbcClient jdbc, ReviewQueue reviewQueue, ObjectMapper json, Clock clock) {
        this.jdbc = jdbc;
        this.reviewQueue = reviewQueue;
        this.json = json;
        this.clock = clock;
    }

    /** A FAILED run leaves the resource REJECTED: there is nothing to show, and the backlog retries it. */
    @Override
    @Transactional
    public UUID record(Run run, @Nullable Checked checked) {
        UUID runId = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO enrichment_run (id, resource_id, provider, model, prompt_version, input_fingerprint,
                                            started_at, finished_at, outcome, validation_errors)
                VALUES (:id, :rid, :provider, :model, :pv, :fp, :started, :finished, :outcome, CAST(:errors AS jsonb))
                """)
            .param("id", runId).param("rid", run.resourceId()).param("provider", run.provider()).param("model", run.model())
            .param("pv", run.promptVersion()).param("fp", run.inputFingerprint()).param("started", Timestamp.from(run.startedAt()))
            .param("finished", Timestamp.from(clock.instant())).param("outcome", run.outcome())
            .param("errors", json.writeValueAsString(run.errors()))
            .update();
        jdbc.sql("UPDATE resource SET enrichment_status = :s WHERE id = :id")
            .param("s", "FAILED".equals(run.outcome()) ? "REJECTED" : run.outcome()).param("id", run.resourceId()).update();
        if (checked == null) {
            return runId;
        }
        UUID resourceId = run.resourceId();
        reviewQueue.supersedeEnrichmentItems(resourceId, runId);
        deleteAiFields(resourceId);
        deleteUnreviewedAiRelationships(resourceId);
        Map<String, UUID> evidence = new HashMap<>();
        for (Field f : checked.fields()) {
            if (f.verification() == Verification.REJECTED) {
                continue;
            }
            UUID id = UUID.randomUUID();
            jdbc.sql("""
                    INSERT INTO field_assertion (id, resource_id, field, value, origin, verification, enrichment_run_id)
                    VALUES (:id, :rid, :f, :v, 'AI_EXTRACTED', :ver, :run)
                    """).param("id", id).param("rid", resourceId).param("f", f.field()).param("v", f.value())
                .param("ver", f.verification().name()).param("run", runId).update();
            link("field_assertion_evidence", "assertion_id", id, f.citations(), evidence);
        }
        for (Relationship r : checked.relationships()) {
            if (r.verification() == Verification.REJECTED) {
                continue;
            }
            UUID id = UUID.randomUUID();
            int inserted = jdbc.sql("""
                    INSERT INTO relationship (id, from_resource_id, to_resource_id, to_title, kind, verification, origin, enrichment_run_id)
                    VALUES (:id, :from, :to, :title, :kind, :ver, 'AI_EXTRACTED', :run)
                    ON CONFLICT (from_resource_id, kind, to_title) DO NOTHING
                    """).param("id", id).param("from", resourceId).param("to", r.targetId()).param("title", r.target())
                .param("kind", r.kind()).param("ver", r.verification().name()).param("run", runId).update();
            if (inserted == 0) {
                continue;
            }
            link("relationship_evidence", "relationship_id", id, r.citations(), evidence);
            if (r.verification() != Verification.VERIFIED) {
                reviewQueue.open(ReviewQueue.Kind.RELATIONSHIP_CANDIDATE, resourceId,
                    "Unverified relationship: " + r.kind() + " " + r.target(),
                    "Evidence supports the text, but the target is not a known resource.",
                    new ReviewQueue.Links(runId, id, null));
            }
        }
        int pos = 0;
        for (Claim c : checked.summary()) {
            UUID id = UUID.randomUUID();
            jdbc.sql("INSERT INTO summary_claim (id, resource_id, enrichment_run_id, position, text) VALUES (:id, :rid, :run, :p, :t)")
                .param("id", id).param("rid", resourceId).param("run", runId).param("p", pos++).param("t", c.text()).update();
            link("summary_claim_evidence", "claim_id", id, c.citations(), evidence);
        }
        if (!run.errors().isEmpty() || checked.fields().stream().anyMatch(f -> f.verification() == Verification.CONFLICTING)) {
            reviewQueue.open(ReviewQueue.Kind.ENRICHMENT_VALIDATION, resourceId,
                "Enrichment " + run.outcome().toLowerCase() + ": " + run.errors().size() + " validation issue(s)",
                String.join("\n", run.errors().stream().limit(20).toList()), new ReviewQueue.Links(runId, null, null));
        }
        return runId;
    }

    /** The resource's AI-extracted fields, each with its evidence. */
    public List<ResourceDetail.Assertion> assertions(UUID resourceId) {
        return jdbc.sql("SELECT id, field, value, origin, verification FROM field_assertion WHERE resource_id = :id ORDER BY field")
            .param("id", resourceId)
            .query((rs, n) -> new ResourceDetail.Assertion(rs.getString("field"), rs.getString("value"),
                ValueOrigin.valueOf(rs.getString("origin")), Verification.valueOf(rs.getString("verification")), null,
                evidence("field_assertion_evidence", "assertion_id", rs.getObject("id", UUID.class))))
            .list();
    }

    /** The resource's relationships no reviewer rejected, each with its evidence. */
    public List<ResourceDetail.Relationship> relationships(UUID resourceId) {
        return jdbc.sql("""
                SELECT id, kind, to_resource_id, to_title, verification FROM relationship
                WHERE from_resource_id = :id AND verification <> 'REJECTED' ORDER BY kind, to_title
                """)
            .param("id", resourceId)
            .query((rs, n) -> new ResourceDetail.Relationship(rs.getString("kind"), rs.getObject("to_resource_id", UUID.class),
                rs.getString("to_title"), Verification.valueOf(rs.getString("verification")),
                evidence("relationship_evidence", "relationship_id", rs.getObject("id", UUID.class))))
            .list();
    }

    /** The summary of the latest run that has one, or null. */
    public ResourceDetail.@Nullable Summary summary(UUID resourceId) {
        return jdbc.sql("""
                SELECT r.id, r.model, r.prompt_version, r.finished_at FROM enrichment_run r
                WHERE r.resource_id = :id AND r.outcome IN ('VALIDATED','PARTIAL')
                  AND EXISTS (SELECT 1 FROM summary_claim c WHERE c.enrichment_run_id = r.id)
                ORDER BY r.started_at DESC LIMIT 1
                """)
            .param("id", resourceId)
            .query((rs, n) -> {
                UUID run = rs.getObject("id", UUID.class);
                List<ResourceDetail.SummaryClaim> claims = jdbc.sql("SELECT id, text FROM summary_claim WHERE enrichment_run_id = :run ORDER BY position")
                    .param("run", run)
                    .query((c, m) -> new ResourceDetail.SummaryClaim(c.getString("text"),
                        evidence("summary_claim_evidence", "claim_id", c.getObject("id", UUID.class))))
                    .list();
                return new ResourceDetail.Summary(claims, rs.getString("model"), rs.getString("prompt_version"),
                    rs.getTimestamp("finished_at").toInstant());
            })
            .optional().orElse(null);
    }

    private List<ResourceDetail.Evidence> evidence(String joinTable, String fk, UUID owner) {
        // joinTable/fk are compile-time constants from this class, never user input.
        return jdbc.sql("""
                SELECT e.id, e.source_url, e.retrieved_at, e.excerpt FROM evidence e
                JOIN %s j ON j.evidence_id = e.id WHERE j.%s = :owner ORDER BY e.retrieved_at
                """.formatted(joinTable, fk))
            .param("owner", owner)
            .query((rs, n) -> new ResourceDetail.Evidence(rs.getObject("id", UUID.class), URI.create(rs.getString("source_url")),
                rs.getTimestamp("retrieved_at").toInstant(), rs.getString("excerpt")))
            .list();
    }

    /**
     * Removes the resource's AI-extracted fields, and the evidence rows that only they used (the links go
     * with the fields by cascade, the evidence itself would stay behind).
     */
    private void deleteAiFields(UUID resourceId) {
        // The CTE reads the links before the cascade removes them (one snapshot per statement).
        deleteOrphanEvidence(jdbc.sql("""
                WITH gone AS (DELETE FROM field_assertion WHERE resource_id = :rid AND origin = 'AI_EXTRACTED' RETURNING id)
                SELECT DISTINCT fae.evidence_id FROM field_assertion_evidence fae JOIN gone ON gone.id = fae.assertion_id
                """).param("rid", resourceId).query(UUID.class).list());
    }

    /**
     * Removes the resource's AI-extracted relationships that no reviewer decided (no ACCEPTED or REJECTED
     * review item), with the evidence rows only they used. A decided relationship stays, so the same proposal
     * in the new run does not reopen it.
     */
    private void deleteUnreviewedAiRelationships(UUID resourceId) {
        deleteOrphanEvidence(jdbc.sql("""
                WITH gone AS (
                    DELETE FROM relationship r WHERE r.from_resource_id = :rid AND r.origin = 'AI_EXTRACTED'
                      AND NOT EXISTS (SELECT 1 FROM review_item i
                                      WHERE i.relationship_id = r.id AND i.status IN ('ACCEPTED', 'REJECTED'))
                    RETURNING r.id)
                SELECT DISTINCT re.evidence_id FROM relationship_evidence re JOIN gone ON gone.id = re.relationship_id
                """).param("rid", resourceId).query(UUID.class).list());
    }

    /** Deletes those of [ids] that no field, relationship or summary claim cites any more. */
    private void deleteOrphanEvidence(List<UUID> ids) {
        if (ids.isEmpty()) {
            return;
        }
        jdbc.sql("""
                DELETE FROM evidence e WHERE e.id IN (:ids)
                  AND NOT EXISTS (SELECT 1 FROM field_assertion_evidence x WHERE x.evidence_id = e.id)
                  AND NOT EXISTS (SELECT 1 FROM relationship_evidence x WHERE x.evidence_id = e.id)
                  AND NOT EXISTS (SELECT 1 FROM summary_claim_evidence x WHERE x.evidence_id = e.id)
                """).param("ids", ids).update();
    }

    /** One evidence row per (source record, excerpt), linked to the owner. joinTable/fk are constants. */
    private void link(String joinTable, String fk, UUID owner, List<Citation> citations, Map<String, UUID> cache) {
        for (Citation c : citations) {
            String key = c.sourceRecordId() + "|" + c.excerpt();
            UUID evidenceId = cache.computeIfAbsent(key, k -> {
                UUID id = UUID.randomUUID();
                jdbc.sql("""
                        INSERT INTO evidence (id, source_record_id, source_url, retrieved_at, excerpt)
                        SELECT :id, sr.id, sr.source_url, sr.retrieved_at, :excerpt FROM source_record sr WHERE sr.id = :rec
                        """).param("id", id).param("rec", c.sourceRecordId()).param("excerpt", c.excerpt()).update();
                return id;
            });
            jdbc.sql("INSERT INTO %s (%s, evidence_id) VALUES (:o, :e) ON CONFLICT DO NOTHING".formatted(joinTable, fk))
                .param("o", owner).param("e", evidenceId).update();
        }
    }
}
