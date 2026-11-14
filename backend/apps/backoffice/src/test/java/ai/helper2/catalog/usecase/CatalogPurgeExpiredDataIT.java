package ai.helper2.catalog.usecase;

import static org.assertj.core.api.Assertions.assertThat;

import ai.helper2.TestcontainersConfiguration;
import ai.helper2.catalog.CatalogRetention;
import ai.helper2.catalog.ReviewDecisions;
import ai.helper2.catalog.ReviewQueue;
import ai.helper2.shared.CanonicalUrl;
import ai.helper2.shared.ResourceId;
import ai.helper2.testing.AsBackOffice;
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

@SpringBootTest
@Import(TestcontainersConfiguration.class)
@AsBackOffice
class CatalogPurgeExpiredDataIT {

  static final Instant NOW = Instant.parse("2000-07-01T00:00:00Z");

  @Autowired JdbcClient jdbc;

  @Autowired CatalogRetention retention;

  @Autowired ReviewQueue queue;

  @Autowired ReviewDecisions decisions;

  PurgeExpiredData purge() {
    return new PurgeExpiredData(
        retention,
        Clock.fixed(NOW, ZoneOffset.UTC),
        new PurgeExpiredData.Periods(
            Duration.ofDays(180),
            Duration.ofDays(365),
            Duration.ofDays(90),
            Duration.ofDays(180),
            Duration.ofDays(365),
            Duration.ofDays(180),
            Duration.ofDays(30)));
  }

  static Instant daysAgo(int days) {
    return NOW.minus(Duration.ofDays(days));
  }

  UUID resource(String origin, String type, Instant lastSeen) {
    UUID id = UUID.randomUUID();
    String url = "https://retention.example/" + id;
    jdbc.sql(
            """
            INSERT INTO resource (id, canonical_url, url, title, description_origin, type, audience, data_origin, last_seen_at)
            VALUES (:id, :url, :url, 'Retention', 'SOURCE_PROVIDED', :type, 'BUILD_WITH_AI', :origin, :seen)
            """)
        .param("id", id)
        .param("url", url)
        .param("type", type)
        .param("origin", origin)
        .param("seen", Timestamp.from(lastSeen))
        .update();
    return id;
  }

  UUID observed(Instant lastSeen) {
    return resource("OBSERVED", "LIBRARY", lastSeen);
  }

  boolean exists(String table, UUID id) {
    return jdbc.sql("SELECT EXISTS (SELECT 1 FROM " + table + " WHERE id = :id)")
        .param("id", id)
        .query(Boolean.class)
        .single();
  }

  UUID report(UUID resourceId) {
    UUID id = UUID.randomUUID();
    jdbc.sql(
            "INSERT INTO issue_report (id, resource_id, kind, message) VALUES (:id, :rid, 'WRONG', 'Wrong licence')")
        .param("id", id)
        .param("rid", resourceId)
        .update();
    return id;
  }

  UUID reviewItem(UUID resourceId, UUID reportId, UUID runId, Instant resolvedAt) {
    UUID id = UUID.randomUUID();
    jdbc.sql(
            """
            INSERT INTO review_item (id, kind, status, resource_id, report_id, enrichment_run_id, reason, created_at, resolved_at)
            VALUES (:id, 'ENRICHMENT_VALIDATION', :status, :rid, :rep, :run, 'check', :created, :resolved)
            """)
        .param("id", id)
        .param("status", resolvedAt == null ? "OPEN" : "ACCEPTED")
        .param("rid", resourceId)
        .param("rep", reportId)
        .param("run", runId)
        .param("created", Timestamp.from(daysAgo(1000)))
        .param("resolved", resolvedAt == null ? null : Timestamp.from(resolvedAt))
        .update();
    return id;
  }

  UUID run(UUID resourceId, Instant startedAt) {
    UUID id = UUID.randomUUID();
    jdbc.sql(
            """
            INSERT INTO enrichment_run (id, resource_id, provider, model, prompt_version, input_fingerprint, started_at, outcome)
            VALUES (:id, :rid, 'test', 'm', 'v1', 'f', :started, 'VALIDATED')
            """)
        .param("id", id)
        .param("rid", resourceId)
        .param("started", Timestamp.from(startedAt))
        .update();
    return id;
  }

  UUID record(UUID resourceId, String sourceId, String type, Instant retrievedAt) {
    UUID id = UUID.randomUUID();
    jdbc.sql(
            """
            INSERT INTO source (id, mode) VALUES (:src, 'LIVE')
            ON CONFLICT (id) DO NOTHING
            """)
        .param("src", sourceId)
        .update();
    jdbc.sql(
            """
            INSERT INTO source_record (id, source_id, external_id, resource_id, source_url, canonical_url, title, type,
                                       retrieved_at, fingerprint)
            VALUES (:id, :src, :ext, :rid, 'https://example.com', 'https://example.com', 'T', :type, :at, 'f')
            """)
        .param("id", id)
        .param("src", sourceId)
        .param("ext", id.toString())
        .param("rid", resourceId)
        .param("type", type)
        .param("at", Timestamp.from(retrievedAt))
        .update();
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
  void anObservedResourceAnAdminEditedStaysHoweverLongUnseen() {
    UUID edited = observed(daysAgo(400));
    jdbc.sql("UPDATE resource SET curated_fields = ARRAY['license'] WHERE id = :id")
        .param("id", edited)
        .update();

    purge().purge();

    assertThat(exists("resource", edited)).isTrue();
  }

  @Test
  void aCandidateDecidedLongAgoIsNotProposedAgainOnceItsReviewItemIsGone() {
    String url =
        CanonicalUrl.of("https://retention.example/candidate-" + UUID.randomUUID()).getValue();
    ReviewQueue.Kind kind = ReviewQueue.Kind.RESOURCE_CANDIDATE;
    assertThat(queue.proposeCandidate(kind, url, "New PRODUCT", "{}")).isTrue();
    UUID item = queue.openCandidate(kind, url).orElseThrow().id();
    assertThat(decisions.decide(item, ReviewQueue.Status.REJECTED, "admin", null)).isTrue();
    jdbc.sql("UPDATE review_item SET resolved_at = :at WHERE id = :id")
        .param("at", Timestamp.from(daysAgo(181)))
        .param("id", item)
        .update();

    purge().purge();

    assertThat(exists("review_item", item)).isFalse();
    assertThat(queue.candidateKnown(kind, url)).isTrue();
    assertThat(queue.proposeCandidate(kind, url, "New PRODUCT", "{}")).isFalse();
    assertThat(
            jdbc.sql("SELECT candidate_key FROM candidate_decision WHERE candidate_key = :key")
                .param("key", ResourceId.of(CanonicalUrl.of(url)).getValue())
                .query(UUID.class)
                .list())
        .as("the key of the address, the way a resource id is made, never the address")
        .hasSize(1);
  }

  @Test
  void anObservedResourceStaysWhileAReportAReviewItemOrAnIndexedResourcePointsAtIt() {
    UUID reported = observed(daysAgo(400));
    report(reported);
    UUID underReview = observed(daysAgo(400));
    reviewItem(underReview, null, null, null);
    UUID linked = observed(daysAgo(400));
    UUID from = resource("INDEXED", "LIBRARY", NOW);
    jdbc.sql(
            """
            INSERT INTO relationship (id, from_resource_id, to_resource_id, to_title, kind, verification, origin)
            VALUES (gen_random_uuid(), :from, :to, 'Linked', 'USES', 'VERIFIED', 'AI_EXTRACTED')
            """)
        .param("from", from)
        .param("to", linked)
        .update();

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
    jdbc.sql(
            """
            INSERT INTO field_assertion (id, resource_id, field, value, origin, verification, enrichment_run_id)
            VALUES (gen_random_uuid(), :rid, 'license', 'MIT', 'AI_EXTRACTED', 'VERIFIED', :run)
            """)
        .param("rid", resource)
        .param("run", cited)
        .update();

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
    assertThat(exists("review_item", recentItem)).isTrue();
    assertThat(exists("issue_report", open)).isTrue();
  }

  @Test
  void discoveryListPagesGoAfterHalfAYearButAcceptedProductsStay() {
    UUID list = resource("INDEXED", "ARTICLE", NOW);
    UUID oldList = record(list, "discovery-self-hosted", "ARTICLE", daysAgo(181));
    UUID product = resource("INDEXED", "PRODUCT", NOW);
    UUID accepted = record(product, "discovery-self-hosted", "PRODUCT", daysAgo(400));

    purge().purge();

    assertThat(exists("source_record", oldList)).isFalse();
    assertThat(exists("resource", list)).isFalse();
    assertThat(exists("source_record", accepted)).isTrue();
    assertThat(exists("resource", product)).isTrue();
  }

  @Test
  void linkedInPostsAndArticlesGoAfterHalfAYearButAcceptedCompanyPagesStay() {
    UUID post = resource("INDEXED", "NEWS", NOW);
    UUID oldPost = record(post, "linkedin-scout", "NEWS", daysAgo(181));
    UUID article = resource("INDEXED", "ARTICLE", NOW);
    UUID oldArticle = record(article, "linkedin-scout", "ARTICLE", daysAgo(181));
    UUID company = resource("INDEXED", "PRODUCT", NOW);
    UUID accepted = record(company, "linkedin-scout", "PRODUCT", daysAgo(400));

    purge().purge();

    assertThat(exists("source_record", oldPost)).isFalse();
    assertThat(exists("resource", post)).isFalse();
    assertThat(exists("source_record", oldArticle)).isFalse();
    assertThat(exists("source_record", accepted)).isTrue();
    assertThat(exists("resource", company)).isTrue();
  }

  @Test
  void everyDiscoveryCategoryIsAWebSourceButNoOtherSourceIs() {
    UUID legal = resource("INDEXED", "ARTICLE", NOW);
    UUID legalList = record(legal, "discovery-legal", "ARTICLE", daysAgo(181));
    UUID other = resource("INDEXED", "ARTICLE", NOW);
    UUID otherList = record(other, "discoverer", "ARTICLE", daysAgo(181));

    purge().purge();

    assertThat(exists("source_record", legalList)).isFalse();
    assertThat(exists("source_record", otherList)).isTrue();
  }

  @Test
  void newsFeedPostsGoHalfAYearAfterTheFeedLastListedThem() {
    UUID post = resource("INDEXED", "NEWS", NOW);
    UUID oldPost = record(post, "feed-openai", "NEWS", daysAgo(181));
    UUID listed = resource("INDEXED", "NEWS", NOW);
    UUID listedPost = record(listed, "feed-openai", "NEWS", daysAgo(1));
    UUID other = resource("INDEXED", "NEWS", NOW);
    UUID otherPost = record(other, "feeder", "NEWS", daysAgo(181));

    purge().purge();

    assertThat(exists("source_record", oldPost)).isFalse();
    assertThat(exists("resource", post)).isFalse();
    assertThat(exists("source_record", listedPost)).isTrue();
    assertThat(exists("source_record", otherPost)).isTrue();
  }

  @Test
  void tavilyNewsRecordsGoAfterHalfAYearWithTheResourcesTheyLeaveEmpty() {
    UUID news = resource("INDEXED", "NEWS", NOW);
    UUID oldRecord = record(news, "live-web-scout", "NEWS", daysAgo(181));
    UUID shared = resource("INDEXED", "NEWS", NOW);
    UUID sharedOld = record(shared, "live-web", "ARTICLE", daysAgo(181));
    UUID sharedOther = record(shared, "github", "LIBRARY", daysAgo(181));
    UUID fresh = resource("INDEXED", "NEWS", NOW);
    UUID freshRecord = record(fresh, "live-web", "NEWS", daysAgo(179));

    purge().purge();

    assertThat(exists("source_record", oldRecord)).isFalse();
    assertThat(exists("resource", news)).isFalse();
    assertThat(exists("source_record", sharedOld)).isFalse();
    assertThat(exists("source_record", sharedOther)).isTrue();
    assertThat(exists("resource", shared)).isTrue();
    assertThat(exists("source_record", freshRecord)).isTrue();
  }

  @Test
  void eventsGoAYearAfterTheyEndedWhateverTheirOrigin() {
    UUID ended = resource("INDEXED", "EVENT", NOW);
    UUID endedRecently = resource("OBSERVED", "EVENT", NOW);
    UUID startOnly = resource("INDEXED", "EVENT", NOW);
    UUID curated = resource("CURATED", "EVENT", NOW);
    setEvent(ended, daysAgo(380), daysAgo(366));
    setEvent(endedRecently, daysAgo(380), daysAgo(364));
    setEvent(startOnly, daysAgo(366), null);
    setEvent(curated, daysAgo(380), daysAgo(366));

    purge().purge();

    assertThat(exists("resource", ended)).isFalse();
    assertThat(exists("resource", endedRecently)).isTrue();
    assertThat(exists("resource", startOnly)).isFalse();
    assertThat(exists("resource", curated)).isFalse();
  }

  @Test
  void aCuratedEventStaysUntilAYearAfterItsLastDay() {
    UUID endedWithinTheYear = resource("CURATED", "EVENT", NOW);
    UUID endsToday = resource("CURATED", "EVENT", NOW);
    UUID upcoming = resource("CURATED", "EVENT", NOW);
    UUID pastCourse = resource("CURATED", "COURSE", NOW);
    setEvent(endedWithinTheYear, daysAgo(380), daysAgo(364));
    setEvent(endsToday, daysAgo(2), NOW);
    setEvent(upcoming, NOW.plus(Duration.ofDays(30)), NOW.plus(Duration.ofDays(31)));
    setEvent(pastCourse, daysAgo(500), daysAgo(500));

    purge().purge();

    assertThat(exists("resource", endedWithinTheYear)).isTrue();
    assertThat(exists("resource", endsToday)).isTrue();
    assertThat(exists("resource", upcoming)).isTrue();
    assertThat(exists("resource", pastCourse)).isTrue();
  }

  @Test
  void aPastCuratedEventGoesWithItsRecordItsStatedDaysAndTheirQuote() {
    UUID past = resource("CURATED", "EVENT", NOW);
    setEvent(past, daysAgo(380), daysAgo(366));
    UUID pastRecord = record(past, "curated", "EVENT", daysAgo(400));
    UUID pastDays = statedDays(past);
    UUID pastQuote = quote(pastDays);
    UUID upcoming = resource("CURATED", "EVENT", NOW);
    setEvent(upcoming, NOW.plus(Duration.ofDays(30)), null);
    UUID upcomingDays = statedDays(upcoming);
    UUID upcomingQuote = quote(upcomingDays);

    purge().purge();

    assertThat(exists("resource", past)).isFalse();
    assertThat(exists("source_record", pastRecord)).isFalse();
    assertThat(exists("field_assertion", pastDays)).isFalse();
    assertThat(exists("evidence", pastQuote)).isFalse();
    assertThat(exists("resource", upcoming)).isTrue();
    assertThat(exists("field_assertion", upcomingDays)).isTrue();
    assertThat(exists("evidence", upcomingQuote)).isTrue();
  }

  @Test
  void aPastCuratedEventStaysWhileAReportPointsAtIt() {
    UUID reported = resource("CURATED", "EVENT", NOW);
    setEvent(reported, daysAgo(380), daysAgo(366));
    UUID quote = quote(statedDays(reported));
    report(reported);

    purge().purge();

    assertThat(exists("resource", reported)).isTrue();
    assertThat(exists("evidence", quote)).isTrue();
  }

  UUID statedDays(UUID resourceId) {
    UUID id = UUID.randomUUID();
    jdbc.sql(
            """
            INSERT INTO field_assertion (id, resource_id, field, value, origin, verification)
            VALUES (:id, :rid, 'eventDates', '1999-06-20', 'CURATED', 'VERIFIED')
            """)
        .param("id", id)
        .param("rid", resourceId)
        .update();
    return id;
  }

  UUID quote(UUID assertionId) {
    UUID id = UUID.randomUUID();
    jdbc.sql(
            """
            INSERT INTO evidence (id, source_url, retrieved_at, excerpt)
            VALUES (:id, 'https://retention.example/dates', now(), 'The summit is on 20 June 1999')
            """)
        .param("id", id)
        .update();
    jdbc.sql("INSERT INTO field_assertion_evidence (assertion_id, evidence_id) VALUES (:a, :e)")
        .param("a", assertionId)
        .param("e", id)
        .update();
    return id;
  }

  void setEvent(UUID id, Instant startsAt, Instant endsAt) {
    jdbc.sql("UPDATE resource SET event_starts_at = :s, event_ends_at = :e WHERE id = :id")
        .param("s", Timestamp.from(startsAt))
        .param("e", endsAt == null ? null : Timestamp.from(endsAt))
        .param("id", id)
        .update();
  }
}
