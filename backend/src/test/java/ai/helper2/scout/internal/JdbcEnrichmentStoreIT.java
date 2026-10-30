package ai.helper2.scout.internal;

import static org.assertj.core.api.Assertions.assertThat;

import ai.helper2.TestcontainersConfiguration;
import ai.helper2.catalog.ReviewQueue;
import ai.helper2.catalog.usecase.ResolveReviewItem;
import ai.helper2.scout.CheckedCitation;
import ai.helper2.scout.CheckedRelationship;
import ai.helper2.scout.EnrichmentInput;
import ai.helper2.scout.EnrichmentModel;
import ai.helper2.scout.EnrichmentOutcome;
import ai.helper2.scout.EnrichmentProposal;
import ai.helper2.scout.ValidationResult;
import ai.helper2.shared.Verification;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

/** What one saved enrichment run leaves in PostgreSQL: fingerprints, relationships, evidence and review items. */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class JdbcEnrichmentStoreIT {

    static final EnrichmentModel MODEL = new EnrichmentModel() {
        @Override
        public @NotNull String getProvider() {
            return "fake";
        }

        @Override
        public @NotNull String getModel() {
            return "m";
        }

        @Override
        public @NotNull String getPromptVersion() {
            return "enrich-v3";
        }

        @Override
        public @NotNull EnrichmentProposal propose(@NotNull EnrichmentInput input) {
            throw new UnsupportedOperationException();
        }
    };

    @Autowired
    JdbcEnrichmentStore store;

    @Autowired
    ResolveReviewItem resolve;

    @Autowired
    JdbcClient jdbc;

    UUID resource;
    UUID record;

    @BeforeEach
    void resourceWithOneSourceRecord() {
        resource = UUID.randomUUID();
        record = UUID.randomUUID();
        String url = "https://enrichment-store.example/" + resource;
        jdbc.sql("""
                INSERT INTO resource (id, canonical_url, url, title, description_origin, type, audience, data_origin)
                VALUES (:id, :url, :url, 'Store test', 'SOURCE_PROVIDED', 'MCP_SERVER', 'BUILD_WITH_AI', 'INDEXED')
                """).param("id", resource).param("url", url).update();
        jdbc.sql("""
                INSERT INTO source_record (id, source_id, external_id, resource_id, source_url, canonical_url, title,
                                           description, type, retrieved_at, fingerprint)
                VALUES (:id, 'curated', :id::text, :rid, :url, :url, 'Store test',
                        'Works with Claude Desktop and with Cursor.', 'MCP_SERVER', now(), 'fp')
                """).param("id", record).param("rid", resource).param("url", url).update();
    }

    @AfterEach
    void clean() {
        jdbc.sql("DELETE FROM review_item WHERE resource_id = :id").param("id", resource).update();
        jdbc.sql("DELETE FROM relationship WHERE from_resource_id = :id").param("id", resource).update();
        jdbc.sql("DELETE FROM summary_claim WHERE resource_id = :id").param("id", resource).update();
        jdbc.sql("DELETE FROM field_assertion WHERE resource_id = :id").param("id", resource).update();
        jdbc.sql("DELETE FROM enrichment_run WHERE resource_id = :id").param("id", resource).update();
        jdbc.sql("DELETE FROM resource WHERE id = :id").param("id", resource).update();
    }

    private void save(String fingerprint, EnrichmentOutcome outcome, ValidationResult result) {
        store.save(resource, MODEL, fingerprint, Instant.now(), result, outcome, result == null ? "boom" : null);
    }

    private CheckedRelationship relationship(String target, Verification verification) {
        return new CheckedRelationship("COMPATIBLE_WITH", target, null, verification,
            List.of(new CheckedCitation(record, "Works with " + target)));
    }

    private ValidationResult partial(List<CheckedRelationship> relationships, String... errors) {
        return new ValidationResult(EnrichmentOutcome.PARTIAL, List.of(), relationships, List.of(), List.of(errors));
    }

    private List<String> relationships() {
        return jdbc.sql("SELECT to_title || ':' || verification FROM relationship WHERE from_resource_id = :id ORDER BY to_title")
            .param("id", resource).query(String.class).list();
    }

    private List<String> reviewStatuses(ReviewQueue.Kind kind) {
        return jdbc.sql("SELECT status FROM review_item WHERE resource_id = :id AND kind = :k ORDER BY created_at, status")
            .param("id", resource).param("k", kind.name()).query(String.class).list();
    }

    @Test
    void theLastJudgedRunGivesTheFingerprintRunsWithoutAJudgedAnswerArePassedOver() {
        assertThat(store.lastFingerprint(resource)).isNull();

        save("rejected-input", EnrichmentOutcome.REJECTED,
            new ValidationResult(EnrichmentOutcome.REJECTED, List.of(), List.of(), List.of(), List.of("nothing cited")));
        assertThat(store.lastFingerprint(resource)).isEqualTo("rejected-input");

        save("failed-input", EnrichmentOutcome.FAILED, null);
        save("unavailable-input", EnrichmentOutcome.LLM_UNAVAILABLE, null);
        assertThat(store.lastFingerprint(resource)).isEqualTo("rejected-input");
    }

    @Test
    void aNewRunReplacesUnreviewedAiRelationshipsAndKeepsReviewedOnes() {
        save("run-1", EnrichmentOutcome.PARTIAL, partial(List.of(
            relationship("Claude Desktop", Verification.UNVERIFIED), relationship("Cursor", Verification.UNVERIFIED))));
        UUID accepted = jdbc.sql("""
                SELECT i.id FROM review_item i JOIN relationship r ON r.id = i.relationship_id
                WHERE r.from_resource_id = :id AND r.to_title = 'Cursor'
                """).param("id", resource).query(UUID.class).single();
        resolve.resolve(accepted, ReviewQueue.Status.ACCEPTED, "admin", null);

        // The second run no longer proposes Claude Desktop, and proposes Cursor again.
        save("run-2", EnrichmentOutcome.PARTIAL, partial(List.of(relationship("Cursor", Verification.UNVERIFIED))));

        assertThat(relationships()).containsExactly("Cursor:VERIFIED");
        assertThat(jdbc.sql("SELECT count(*) FROM evidence WHERE source_record_id = :rec AND excerpt = 'Works with Claude Desktop'")
            .param("rec", record).query(Long.class).single()).isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM evidence WHERE source_record_id = :rec AND excerpt = 'Works with Cursor'")
            .param("rec", record).query(Long.class).single()).isOne();
    }

    @Test
    void aNewRunSupersedesTheOpenItemsOfEarlierRuns() {
        save("run-1", EnrichmentOutcome.PARTIAL, partial(List.of(relationship("Claude Desktop", Verification.UNVERIFIED)), "first issue"));
        save("run-2", EnrichmentOutcome.PARTIAL, partial(List.of(relationship("Claude Desktop", Verification.UNVERIFIED)), "second issue"));

        assertThat(reviewStatuses(ReviewQueue.Kind.ENRICHMENT_VALIDATION)).containsExactly("SUPERSEDED", "OPEN");
        assertThat(reviewStatuses(ReviewQueue.Kind.RELATIONSHIP_CANDIDATE)).containsExactly("SUPERSEDED", "OPEN");
        assertThat(jdbc.sql("""
                SELECT count(*) FROM review_item WHERE resource_id = :id AND status = 'SUPERSEDED' AND resolved_at IS NOT NULL
                """).param("id", resource).query(Long.class).single()).isEqualTo(2);
    }

    @Test
    void aRunWithoutModelOutputLeavesTheLastOutputAndItsItemsAlone() {
        save("run-1", EnrichmentOutcome.PARTIAL, partial(List.of(relationship("Claude Desktop", Verification.UNVERIFIED)), "issue"));

        save("run-1", EnrichmentOutcome.LLM_UNAVAILABLE, null);

        assertThat(relationships()).containsExactly("Claude Desktop:UNVERIFIED");
        assertThat(reviewStatuses(ReviewQueue.Kind.ENRICHMENT_VALIDATION)).containsExactly("OPEN");
    }
}
