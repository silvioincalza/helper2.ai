package ai.helper2.catalog.usecase;

import static org.assertj.core.api.Assertions.assertThat;

import ai.helper2.TestcontainersConfiguration;
import ai.helper2.catalog.internal.CatalogRetention;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Each catalog retention rule against the real schema, with the clock fixed in 2000: every row another test writes
 * is newer than any cutoff here, so the purges only ever touch this class's rows.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class CatalogPurgeExpiredDataIT {

    static final Instant NOW = Instant.parse("2000-07-01T00:00:00Z");

    @Autowired
    JdbcClient jdbc;

    @Autowired
    CatalogRetention retention;

    /** The defaults of application.yaml (helper2.retention.*). */
    PurgeExpiredData purge() {
        return new PurgeExpiredData(retention, Clock.fixed(NOW, ZoneOffset.UTC), Duration.ofDays(180), Duration.ofDays(365),
            Duration.ofDays(90), Duration.ofDays(180), Duration.ofDays(365), Duration.ofDays(180));
    }

    static Instant daysAgo(int days) {
        return NOW.minus(Duration.ofDays(days));
    }

    UUID resource(String origin, String type, Instant lastSeen) {
        UUID id = UUID.randomUUID();
        String url = "https://retention.example/" + id;
        jdbc.sql("""
                INSERT INTO resource (id, canonical_url, url, title, description_origin, type, audience, data_origin, last_seen_at)
                VALUES (:id, :url, :url, 'Retention', 'SOURCE_PROVIDED', :type, 'BUILD_WITH_AI', :origin, :seen)
                """).param("id", id).param("url", url).param("type", type).param("origin", origin)
            .param("seen", Timestamp.from(lastSeen)).update();
        return id;
    }

    UUID observed(Instant lastSeen) {
        return resource("OBSERVED", "LIBRARY", lastSeen);
    }

    boolean exists(String table, UUID id) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM " + table + " WHERE id = :id)").param("id", id).query(Boolean.class).single();
    }

    UUID report(UUID resourceId) {
        UUID id = UUID.randomUUID();
        jdbc.sql("INSERT INTO issue_report (id, resource_id, kind, message) VALUES (:id, :rid, 'WRONG', 'Wrong licence')")
            .param("id", id).param("rid", resourceId).update();
        return id;
    }

    UUID reviewItem(UUID resourceId, UUID reportId, UUID runId, Instant resolvedAt) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO review_item (id, kind, status, resource_id, report_id, enrichment_run_id, reason, created_at, resolved_at)
                VALUES (:id, 'ENRICHMENT_VALIDATION', :status, :rid, :rep, :run, 'check', :created, :resolved)
                """).param("id", id).param("status", resolvedAt == null ? "OPEN" : "ACCEPTED").param("rid", resourceId)
            .param("rep", reportId).param("run", runId).param("created", Timestamp.from(daysAgo(1000)))
            .param("resolved", resolvedAt == null ? null : Timestamp.from(resolvedAt)).update();
        return id;
    }

    UUID run(UUID resourceId, Instant startedAt) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO enrichment_run (id, resource_id, provider, model, prompt_version, input_fingerprint, started_at, outcome)
                VALUES (:id, :rid, 'test', 'm', 'v1', 'f', :started, 'VALIDATED')
                """).param("id", id).param("rid", resourceId).param("started", Timestamp.from(startedAt)).update();
        return id;
    }

    UUID record(UUID resourceId, String sourceId, String type, Instant retrievedAt) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO source (id, name, mode, homepage) VALUES (:src, :src, 'LIVE', 'https://example.com')
                ON CONFLICT (id) DO NOTHING
                """).param("src", sourceId).update();
        jdbc.sql("""
                INSERT INTO source_record (id, source_id, external_id, resource_id, source_url, canonical_url, title, type,
                                           retrieved_at, fingerprint)
                VALUES (:id, :src, :ext, :rid, 'https://example.com', 'https://example.com', 'T', :type, :at, 'f')
                """).param("id", id).param("src", sourceId).param("ext", id.toString()).param("rid", resourceId)
            .param("type", type).param("at", Timestamp.from(retrievedAt)).update();
        return id;
    }

    @Test
    void observedResourcesUnseenForHalfAYearAreDeleted() {
        UUID stale = observed(daysAgo(181));
        UUID recent = observed(daysAgo(179));
        UUID indexed = resource("INDEXED", "LIBRARY", daysAgo(400));

        purge().purge();

        assertThat(exists("resource", stale)).isFalse();
        assertThat(exists("resource", recent)).isTrue();
        assertThat(exists("resource", indexed)).isTrue();
    }

    @Test
    void anObservedResourceStaysWhileAReportAReviewItemOrAnIndexedResourcePointsAtIt() {
        UUID reported = observed(daysAgo(400));
        report(reported);
        UUID underReview = observed(daysAgo(400));
        reviewItem(underReview, null, null, null);
        UUID linked = observed(daysAgo(400));
        UUID from = resource("INDEXED", "LIBRARY", NOW);
        jdbc.sql("""
                INSERT INTO relationship (id, from_resource_id, to_resource_id, to_title, kind, verification, origin)
                VALUES (gen_random_uuid(), :from, :to, 'Linked', 'USES', 'VERIFIED', 'AI_EXTRACTED')
                """).param("from", from).param("to", linked).update();

        purge().purge();

        assertThat(exists("resource", reported)).isTrue();
        assertThat(exists("resource", underReview)).isTrue();
        assertThat(exists("resource", linked)).isTrue();
    }

    @Test
    void oldEnrichmentRunsGoExceptTheLatestAndTheCitedOnes() {
        UUID resource = resource("INDEXED", "LIBRARY", NOW);
        UUID older = run(resource, daysAgo(200));
        UUID cited = run(resource, daysAgo(150));
        UUID latest = run(resource, daysAgo(100));
        jdbc.sql("""
                INSERT INTO field_assertion (id, resource_id, field, value, origin, verification, enrichment_run_id)
                VALUES (gen_random_uuid(), :rid, 'license', 'MIT', 'AI_EXTRACTED', 'VERIFIED', :run)
                """).param("rid", resource).param("run", cited).update();

        purge().purge();

        assertThat(exists("enrichment_run", older)).isFalse();
        assertThat(exists("enrichment_run", cited)).isTrue();
        assertThat(exists("enrichment_run", latest)).isTrue();
    }

    @Test
    void aRunNewerThanNinetyDaysStaysEvenWhenANewerOneExists() {
        UUID resource = resource("INDEXED", "LIBRARY", NOW);
        UUID recent = run(resource, daysAgo(80));
        run(resource, daysAgo(10));

        purge().purge();

        assertThat(exists("enrichment_run", recent)).isTrue();
    }

    @Test
    void theOnlyRunOfAResourceStaysHoweverOld() {
        UUID resource = resource("INDEXED", "LIBRARY", NOW);
        UUID only = run(resource, daysAgo(1000));

        purge().purge();

        assertThat(exists("enrichment_run", only)).isTrue();
    }

    @Test
    void reviewItemsResolvedHalfAYearAgoAreDeletedOpenOnesStay() {
        UUID resource = resource("INDEXED", "LIBRARY", NOW);
        UUID old = reviewItem(resource, null, null, daysAgo(181));
        UUID recent = reviewItem(resource, null, null, daysAgo(179));
        UUID open = reviewItem(resource, null, null, null);

        purge().purge();

        assertThat(exists("review_item", old)).isFalse();
        assertThat(exists("review_item", recent)).isTrue();
        assertThat(exists("review_item", open)).isTrue();
    }

    @Test
    void issueReportsGoTwelveMonthsAfterTheirReviewWasResolvedWithTheirReviewItem() {
        UUID resource = resource("INDEXED", "LIBRARY", NOW);
        UUID old = report(resource);
        UUID oldItem = reviewItem(resource, old, null, daysAgo(366));
        UUID recent = report(resource);
        UUID recentItem = reviewItem(resource, recent, null, daysAgo(200));
        UUID open = report(resource);
        reviewItem(resource, open, null, null);

        purge().purge();

        assertThat(exists("issue_report", old)).isFalse();
        assertThat(exists("review_item", oldItem)).isFalse();
        assertThat(exists("issue_report", recent)).isTrue();
        // Resolved more than 180 days ago, but it goes with its report, not before it.
        assertThat(exists("review_item", recentItem)).isTrue();
        assertThat(exists("issue_report", open)).isTrue();
    }

    @Test
    void tavilyNewsRecordsGoAfterHalfAYearWithTheResourcesTheyLeaveEmpty() {
        UUID news = resource("INDEXED", "NEWS", NOW);
        UUID oldRecord = record(news, "tavily-scout", "NEWS", daysAgo(181));
        UUID shared = resource("INDEXED", "NEWS", NOW);
        UUID sharedOld = record(shared, "tavily", "ARTICLE", daysAgo(181));
        UUID sharedOther = record(shared, "github", "LIBRARY", daysAgo(181));
        UUID fresh = resource("INDEXED", "NEWS", NOW);
        UUID freshRecord = record(fresh, "tavily", "NEWS", daysAgo(179));

        purge().purge();

        assertThat(exists("source_record", oldRecord)).isFalse();
        assertThat(exists("resource", news)).isFalse();
        assertThat(exists("source_record", sharedOld)).isFalse();
        assertThat(exists("source_record", sharedOther)).isTrue();
        assertThat(exists("resource", shared)).isTrue();
        assertThat(exists("source_record", freshRecord)).isTrue();
    }

    @Test
    void eventsGoAYearAfterTheyEndedCuratedOnesStay() {
        UUID ended = resource("INDEXED", "EVENT", NOW);
        UUID endedRecently = resource("OBSERVED", "EVENT", NOW);
        UUID startOnly = resource("INDEXED", "EVENT", NOW);
        UUID curated = resource("CURATED", "EVENT", NOW);
        setEvent(ended, daysAgo(380), daysAgo(366));
        setEvent(endedRecently, daysAgo(380), daysAgo(364));
        setEvent(startOnly, daysAgo(366), null);
        setEvent(curated, daysAgo(500), daysAgo(500));

        purge().purge();

        assertThat(exists("resource", ended)).isFalse();
        assertThat(exists("resource", endedRecently)).isTrue();
        assertThat(exists("resource", startOnly)).isFalse();
        assertThat(exists("resource", curated)).isTrue();
    }

    void setEvent(UUID id, Instant startsAt, Instant endsAt) {
        jdbc.sql("UPDATE resource SET event_starts_at = :s, event_ends_at = :e WHERE id = :id")
            .param("s", Timestamp.from(startsAt)).param("e", endsAt == null ? null : Timestamp.from(endsAt)).param("id", id).update();
    }
}
