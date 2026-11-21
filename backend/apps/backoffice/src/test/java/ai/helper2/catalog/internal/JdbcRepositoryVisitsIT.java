package ai.helper2.catalog.internal;

import static org.assertj.core.api.Assertions.assertThat;

import ai.helper2.TestcontainersConfiguration;
import ai.helper2.catalog.enrichment.RepositoryVisits.Due;
import ai.helper2.catalog.enrichment.RepositoryVisits.Found;
import ai.helper2.catalog.enrichment.RepositoryVisits.Gone;
import ai.helper2.catalog.enrichment.RepositoryVisits.Parent;
import ai.helper2.catalog.enrichment.RepositoryVisits.Unanswered;
import ai.helper2.testing.AsBackOffice;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
@AsBackOffice
class JdbcRepositoryVisitsIT {

  static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");
  static final Instant T1 = Instant.parse("2026-01-02T00:00:00Z");

  @Autowired JdbcRepositoryVisits visits;

  @Autowired JdbcClient jdbc;

  @Autowired ResourceRows rows;

  final List<UUID> created = new ArrayList<>();

  @AfterEach
  void clean() {
    created.forEach(id -> jdbc.sql("DELETE FROM resource WHERE id = :id").param("id", id).update());
    jdbc.sql("DELETE FROM source WHERE id = 'aa-visits'").update();
  }

  private UUID record(String canonicalUrl, @Nullable String repositoryUrl) {
    UUID resource = UUID.randomUUID();
    jdbc.sql(
            """
            INSERT INTO resource (id, canonical_url, url, title, description_origin, type, audience, data_origin)
            VALUES (:id, :url, :url, 'Visited', 'SOURCE_PROVIDED', 'LIBRARY', 'BUILD_WITH_AI', 'INDEXED')
            """)
        .param("id", resource)
        .param("url", "https://repository-visits.example/" + resource)
        .update();
    created.add(resource);
    jdbc.sql("INSERT INTO source (id, mode) VALUES ('aa-visits', 'INDEXED') ON CONFLICT DO NOTHING")
        .update();
    UUID id = UUID.randomUUID();
    jdbc.sql(
            """
            INSERT INTO source_record (id, source_id, external_id, resource_id, source_url, canonical_url,
                                       repository_url, title, type, retrieved_at, fingerprint)
            VALUES (:id, 'aa-visits', :id::text, :rid, :url, :url, :repository, 'Visited', 'LIBRARY', now(), 'fp')
            """)
        .param("id", id)
        .param("rid", resource)
        .param("url", canonicalUrl)
        .param("repository", repositoryUrl)
        .update();
    return id;
  }

  private void stored(UUID record, String vitality) {
    jdbc.sql(
            "UPDATE source_record SET extra = jsonb_set(extra, '{vitality}', CAST(:v AS jsonb)) WHERE id = :id")
        .param("v", vitality)
        .param("id", record)
        .update();
  }

  private String vitalityOf(UUID record) {
    return jdbc.sql("SELECT CAST(extra -> 'vitality' AS text) FROM source_record WHERE id = :id")
        .param("id", record)
        .query(String.class)
        .single();
  }

  private List<Due> dueOfThisTest(List<UUID> mine) {
    return visits.due(100_000).stream().filter(d -> mine.contains(d.recordId())).toList();
  }

  @Test
  void aRecordThatLinksAGitHubRepositoryAndARepositoryRecordAreDueAndOtherAddressesAreNot() {
    UUID pkg =
        record("https://www.npmjs.com/package/aa-visits-tool", "https://github.com/aa-visits/tool");
    UUID repo = record("https://github.com/aa-visits/own", null);
    UUID gitlab =
        record("https://www.npmjs.com/package/aa-visits-lab", "https://gitlab.com/aa-visits/lab");
    UUID page = record("https://github.com/aa-visits/own/tree/main/docs", null);

    assertThat(dueOfThisTest(List.of(pkg, repo, gitlab, page)))
        .containsExactlyInAnyOrder(new Due(pkg, "aa-visits/tool"), new Due(repo, "aa-visits/own"));
  }

  @Test
  void theRecordNeverVisitedComesFirstThenTheOldestVisit() {
    UUID lately = record("https://github.com/aa-visits/lately", null);
    UUID longAgo = record("https://github.com/aa-visits/long-ago", null);
    UUID never = record("https://github.com/aa-visits/never", null);
    visits.record(lately, new Unanswered(), T1);
    visits.record(longAgo, new Unanswered(), T0);

    assertThat(dueOfThisTest(List.of(lately, longAgo, never)).stream().map(Due::recordId))
        .containsExactly(never, longAgo, lately);
  }

  @Test
  void aFoundRepositoryStoresItsFactsAndKeepsWhatTheSourceStated() {
    UUID fork = record("https://github.com/aa-visits/fork", null);
    stored(fork, "{\"archived\":false,\"fork\":true,\"statedAt\":\"2025-12-31T00:00:00Z\"}");

    visits.record(fork, new Found(true, new Parent("acme/origin", true), T0, null), T1);

    assertThat(vitalityOf(fork))
        .contains("\"archived\": true")
        .contains("\"fork\": true")
        .contains("\"checkedAt\": \"" + T1 + "\"")
        .contains("\"visitedAt\": \"" + T1 + "\"")
        .contains("\"parent\": \"acme/origin\"")
        .contains("\"parentArchived\": true")
        .contains("\"lastReleaseAt\": \"" + T0 + "\"");
  }

  @Test
  void aVisitOlderThanWhatTheSourceStatesOfArchivedIsDatedAndDoesNotReplaceIt() {
    UUID restated = record("https://github.com/aa-visits/restated", null);
    stored(restated, "{\"archived\":false,\"statedAt\":\"2026-01-03T00:00:00Z\"}");

    visits.record(restated, new Found(true, null, null, null), T1);

    assertThat(vitalityOf(restated))
        .contains("\"archived\": false")
        .contains("\"visitedAt\": \"" + T1 + "\"")
        .doesNotContain("checkedAt");
  }

  @Test
  void aRepositoryNotFoundOnceIsNotGoneYet() {
    UUID repo = record("https://github.com/aa-visits/once", null);

    visits.record(repo, new Gone(), T0);

    assertThat(vitalityOf(repo))
        .contains("\"notFoundAt\": \"" + T0 + "\"")
        .contains("\"visitedAt\": \"" + T0 + "\"")
        .doesNotContain("gone");
  }

  @Test
  void aRepositoryNotFoundAgainInsideADayIsNotGoneYet() {
    UUID repo = record("https://github.com/aa-visits/twice-soon", null);
    visits.record(repo, new Gone(), T0);

    visits.record(repo, new Gone(), T0.plusSeconds(3600));

    assertThat(vitalityOf(repo)).contains("\"notFoundAt\": \"" + T0 + "\"").doesNotContain("gone");
  }

  @Test
  void aRepositoryNotFoundAgainADayLaterIsGone() {
    UUID repo = record("https://github.com/aa-visits/twice", null);
    visits.record(repo, new Gone(), T0);

    visits.record(repo, new Gone(), T1);

    assertThat(vitalityOf(repo))
        .contains("\"gone\": true")
        .contains("\"checkedAt\": \"" + T1 + "\"")
        .contains("\"notFoundAt\": \"" + T0 + "\"");
  }

  @Test
  void aRepositoryFoundAgainIsNoLongerGoneAndDropsTheParentItNoLongerHas() {
    UUID back = record("https://github.com/aa-visits/back", null);
    visits.record(back, new Found(false, new Parent("acme/old", true), null, null), T0);
    visits.record(back, new Gone(), T0);
    visits.record(back, new Gone(), T1);
    assertThat(vitalityOf(back)).contains("\"gone\": true").contains("\"parent\": \"acme/old\"");

    visits.record(back, new Found(false, null, null, null), T1.plusSeconds(60));

    assertThat(vitalityOf(back))
        .contains("\"archived\": false")
        .doesNotContain("gone")
        .doesNotContain("notFoundAt")
        .doesNotContain("parent");
  }

  @Test
  void aFoundRepositoryOnARecordWithNoStoredFactsStoresThem() {
    UUID fresh = record("https://github.com/aa-visits/fresh", null);

    visits.record(fresh, new Found(false, null, null, null), T0);

    assertThat(vitalityOf(fresh))
        .contains("\"archived\": false")
        .contains("\"checkedAt\": \"" + T0 + "\"")
        .contains("\"visitedAt\": \"" + T0 + "\"");
  }

  private void storedPopularity(UUID record, String popularity) {
    jdbc.sql(
            "UPDATE source_record SET extra = jsonb_set(extra, '{popularity}', CAST(:p AS jsonb)) WHERE id = :id")
        .param("p", popularity)
        .param("id", record)
        .update();
  }

  private String popularityOf(UUID record) {
    return jdbc.sql(
            "SELECT coalesce(CAST(extra -> 'popularity' AS text), 'none') FROM source_record WHERE id = :id")
        .param("id", record)
        .query(String.class)
        .single();
  }

  private BigDecimal resourcePopularityOf(UUID record) {
    return jdbc.sql(
            """
            SELECT r.popularity FROM resource r JOIN source_record sr ON sr.resource_id = r.id
            WHERE sr.id = :id
            """)
        .param("id", record)
        .query(BigDecimal.class)
        .single();
  }

  // S2.11.6, ADR-0020 §7: the sweep stores the stars it read, with the time of the visit, and
  // leaves the
  // counts of another kind. ADR-0025 §3: the trigger then computes the popularity of the resource
  // again.
  @Test
  void theStarsOfAFoundRepositoryReplaceTheStoredCountAndMoveThePopularityOfTheResource() {
    UUID repo = record("https://github.com/aa-visits/starred", null);
    storedPopularity(
        repo,
        """
        [{"kind": "GITHUB_STARS", "value": 10, "asOf": "2025-06-01T00:00:00Z"},
         {"kind": "NPM_DOWNLOADS_WEEKLY", "value": 7, "asOf": "2025-06-01T00:00:00Z"}]
        """);
    BigDecimal before = resourcePopularityOf(repo);

    visits.record(repo, new Found(false, null, null, 50_000L), T1);

    assertThat(popularityOf(repo))
        .contains("{\"asOf\": \"" + T1 + "\", \"kind\": \"GITHUB_STARS\", \"value\": 50000}")
        .contains("\"kind\": \"NPM_DOWNLOADS_WEEKLY\", \"value\": 7")
        .doesNotContain("\"value\": 10");
    assertThat(before).isLessThan(BigDecimal.ONE);
    assertThat(resourcePopularityOf(repo)).isEqualByComparingTo(BigDecimal.ONE);
    assertThat(vitalityOf(repo)).contains("\"visitedAt\": \"" + T1 + "\"");
  }

  // S2.11.6, ADR-0020 §7: a repository record that never stated stars gets them at its first visit.
  @Test
  void theStarsOfAFoundRepositoryAreStoredOnARecordThatHadNoCount() {
    UUID repo = record("https://github.com/aa-visits/first-count", null);

    visits.record(repo, new Found(false, null, null, 3_400L), T0);

    assertThat(popularityOf(repo))
        .isEqualTo("[{\"asOf\": \"" + T0 + "\", \"kind\": \"GITHUB_STARS\", \"value\": 3400}]");
    assertThat(resourcePopularityOf(repo)).isGreaterThan(BigDecimal.ZERO);
  }

  // S2.11.6, ADR-0020 §7: a package that links a repository does not borrow its stars.
  @Test
  void aRecordThatOnlyLinksTheRepositoryGetsNoStars() {
    UUID pkg =
        record(
            "https://www.npmjs.com/package/aa-visits-borrower",
            "https://github.com/aa-visits/famous");

    visits.record(pkg, new Found(false, null, null, 50_000L), T0);

    assertThat(popularityOf(pkg)).isEqualTo("none");
    assertThat(resourcePopularityOf(pkg)).isEqualByComparingTo(BigDecimal.ZERO);
    assertThat(vitalityOf(pkg)).contains("\"visitedAt\": \"" + T0 + "\"");
  }

  // S2.11.6, ADR-0020 §7: a visit that reads no stars (the repository is gone, private, renamed
  // away, gave
  // no answer, or its answer has no count) keeps the stored count.
  @Test
  void aVisitThatReadsNoStarsKeepsTheStoredCount() {
    String stored =
        "[{\"asOf\": \"2025-06-01T00:00:00Z\", \"kind\": \"GITHUB_STARS\", \"value\": 1200}]";
    UUID noCount = record("https://github.com/aa-visits/no-count", null);
    UUID gone = record("https://github.com/aa-visits/went-private", null);
    UUID silent = record("https://github.com/aa-visits/no-answer", null);
    List.of(noCount, gone, silent).forEach(r -> storedPopularity(r, stored));
    BigDecimal before = resourcePopularityOf(gone);

    visits.record(noCount, new Found(false, null, null, null), T0);
    visits.record(gone, new Gone(), T0);
    visits.record(gone, new Gone(), T1);
    visits.record(silent, new Unanswered(), T0);

    assertThat(List.of(noCount, gone, silent))
        .allSatisfy(r -> assertThat(popularityOf(r)).isEqualTo(stored));
    assertThat(before).isGreaterThan(BigDecimal.ZERO);
    assertThat(resourcePopularityOf(gone)).isEqualByComparingTo(before);
  }

  @Test
  void aRepositoryGoneForARecordThatOnlyLinksItNeverMakesThatRecordGone() {
    UUID pkg =
        record(
            "https://www.npmjs.com/package/aa-visits-deleted",
            "https://github.com/aa-visits/deleted");
    visits.record(pkg, new Gone(), T0);

    visits.record(pkg, new Gone(), T1);

    assertThat(vitalityOf(pkg)).doesNotContain("gone").contains("\"visitedAt\": \"" + T1 + "\"");
  }

  @Test
  void aGoneRepositoryIsReadAsArchivedByTheCatalogSearch() {
    UUID repo = record("https://github.com/aa-visits/vanished", null);
    UUID resource =
        jdbc.sql("SELECT resource_id FROM source_record WHERE id = :id")
            .param("id", repo)
            .query(UUID.class)
            .single();

    visits.record(repo, new Gone(), T0);
    assertThat(rows.statedVitality(new UUID[] {resource}).get(resource).getArchived()).isNull();
    visits.record(repo, new Gone(), T1);

    assertThat(rows.statedVitality(new UUID[] {resource}).get(resource).getArchived()).isTrue();
  }

  @Test
  void aRepositoryWithoutAnAnswerIsOnlyDated() {
    UUID silent = record("https://github.com/aa-visits/silent", null);
    stored(silent, "{\"archived\":true,\"checkedAt\":\"" + T0 + "\"}");

    visits.record(silent, new Unanswered(), T1);

    assertThat(vitalityOf(silent))
        .contains("\"archived\": true")
        .contains("\"checkedAt\": \"" + T0 + "\"")
        .contains("\"visitedAt\": \"" + T1 + "\"");
  }
}
