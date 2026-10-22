package ai.helper2.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import ai.helper2.TestcontainersConfiguration;
import ai.helper2.shared.CanonicalUrl;
import ai.helper2.shared.ResourceId;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class CuratedSeedTest {

    @Autowired
    JdbcClient jdbc;

    @Autowired
    JdbcTemplate template;

    @Autowired
    TransactionTemplate tx;

    @Test
    void seedIdsMatchTheDomainResourceIdAlgorithm() {
        List<String> urls = jdbc.sql("SELECT canonical_url FROM resource WHERE data_origin = 'CURATED'").query(String.class).list();
        assertThat(urls).hasSizeGreaterThan(15);
        for (String url : urls) {
            UUID dbId = jdbc.sql("SELECT id FROM resource WHERE canonical_url = :u").param("u", url).query(UUID.class).single();
            CanonicalUrl canonical = CanonicalUrl.of(url);
            assertThat(canonical).as(url).isNotNull();
            assertThat(canonical.getValue()).as("seed urls must already be canonical").isEqualTo(url);
            assertThat(ResourceId.of(canonical).getValue()).as(url).isEqualTo(dbId);
        }
    }

    /** Repeatable migrations run again whenever their file changes: a second run must change nothing. */
    @Test
    void seedsCanRunAgainWithoutDuplicatingAnything() throws Exception {
        String counts = """
            SELECT (SELECT count(*) FROM resource WHERE data_origin = 'CURATED') || '/' ||
                   (SELECT count(*) FROM source_record WHERE source_id = 'curated') || '/' ||
                   (SELECT count(*) FROM resource_label l JOIN resource r ON r.id = l.resource_id WHERE r.data_origin = 'CURATED') || '/' ||
                   (SELECT count(*) FROM field_assertion WHERE origin = 'CURATED') || '/' ||
                   (SELECT count(*) FROM evidence) || '/' ||
                   (SELECT count(*) FROM model_family)
            """;
        String before = jdbc.sql(counts).query(String.class).single();

        for (String seed : List.of("R__curated_seed.sql", "R__industry_events.sql", "R__community_events.sql", "R__model_families.sql")) {
            String sql = new String(new ClassPathResource("db/migration/" + seed).getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            tx.executeWithoutResult(status -> template.execute(sql));
        }

        assertThat(jdbc.sql(counts).query(String.class).single()).isEqualTo(before);
        assertThat(jdbc.sql("SELECT count(*) FROM field_assertion WHERE field = 'eventDates'").query(Long.class).single()).isPositive();
        // Community events carry the kind their page states; a stated format comes with its excerpt as evidence.
        assertThat(jdbc.sql("SELECT count(DISTINCT extra->>'eventKind') FROM source_record WHERE fingerprint = 'community-events-v1'")
            .query(Long.class).single()).isEqualTo(4);
        assertThat(jdbc.sql("""
            SELECT count(*) FROM field_assertion a JOIN field_assertion_evidence j ON j.assertion_id = a.id
            WHERE a.field = 'eventFormat'""").query(Long.class).single()).isPositive();
    }

    /** An event dropped from the research file goes away on the next run; other seeds' curated rows stay. */
    @Test
    void theEventsSeedRemovesEventsNoLongerInTheResearchFile() throws Exception {
        String sql = new String(new ClassPathResource("db/migration/R__industry_events.sql").getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        String url = "https://example.org/summit?event=dropped-summit-2026";
        UUID dropped = ResourceId.of(CanonicalUrl.of(url)).getValue();
        tx.executeWithoutResult(status -> {
            jdbc.sql("""
                    INSERT INTO resource (id, canonical_url, url, title, description_origin, type, audience, data_origin)
                    VALUES (:id, :u, :u, 'Dropped Summit 2026', 'CURATED', 'EVENT', 'BOTH', 'CURATED')
                    """).param("id", dropped).param("u", url).update();
            jdbc.sql("""
                    INSERT INTO source_record (id, source_id, external_id, resource_id, source_url, canonical_url, title, type, retrieved_at, fingerprint, extra)
                    VALUES (gen_random_uuid(), 'curated', :u, :id, :u, :u, 'Dropped Summit 2026', 'EVENT', now(), 'industry-events-v1', '{}'::jsonb)
                    """).param("id", dropped).param("u", url).update();
            jdbc.sql("""
                    WITH a AS (INSERT INTO field_assertion (id, resource_id, field, value, origin, verification)
                               VALUES (gen_random_uuid(), :id, 'eventDates', '2026-01-01', 'CURATED', 'VERIFIED') RETURNING id),
                         e AS (INSERT INTO evidence (id, source_url, retrieved_at, excerpt)
                               VALUES (gen_random_uuid(), :u, now(), 'Dropped Summit, 1 January 2026') RETURNING id)
                    INSERT INTO field_assertion_evidence (assertion_id, evidence_id) SELECT a.id, e.id FROM a, e
                    """).param("id", dropped).param("u", url).update();
            long curatedBefore = curatedResources();
            long evidenceBefore = jdbc.sql("SELECT count(*) FROM evidence").query(Long.class).single();

            template.execute(sql);

            assertThat(jdbc.sql("SELECT count(*) FROM resource WHERE id = :id").param("id", dropped).query(Long.class).single()).isZero();
            assertThat(jdbc.sql("SELECT count(*) FROM evidence WHERE source_url = :u").param("u", url).query(Long.class).single()).isZero();
            assertThat(curatedResources()).isEqualTo(curatedBefore - 1);
            assertThat(jdbc.sql("SELECT count(*) FROM evidence").query(Long.class).single()).isEqualTo(evidenceBefore - 1);
            assertThat(jdbc.sql("SELECT count(*) FROM resource WHERE canonical_url = 'https://claude.ai'").query(Long.class).single()).isOne();
            status.setRollbackOnly();
        });
    }

    private long curatedResources() {
        return jdbc.sql("SELECT count(*) FROM resource WHERE data_origin = 'CURATED'").query(Long.class).single();
    }

    @Test
    void seedAssertsNoDatesAndNoPricing() {
        long withDates = jdbc.sql("""
                SELECT count(*) FROM resource WHERE data_origin = 'CURATED'
                AND (published_at IS NOT NULL OR updated_at IS NOT NULL OR pricing IS NOT NULL)
                """).query(Long.class).single();
        assertThat(withDates).isZero();
    }
}
