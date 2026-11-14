package ai.helper2.catalog.internal;

import static org.assertj.core.api.Assertions.assertThat;

import ai.helper2.TestcontainersConfiguration;
import ai.helper2.catalog.enrichment.EnrichmentCandidates;
import ai.helper2.catalog.enrichment.EnrichmentCandidates.BacklogEntry;
import ai.helper2.catalog.enrichment.EnrichmentCandidates.Candidate;
import ai.helper2.catalog.enrichment.EnrichmentCandidates.SourceText;
import ai.helper2.catalog.enrichment.EnrichmentCandidates.Standing;
import ai.helper2.catalog.enrichment.EnrichmentCandidates.State;
import ai.helper2.catalog.enrichment.EnrichmentCandidates.View;
import ai.helper2.catalog.enrichment.EnrichmentCandidates.Waiting;
import ai.helper2.testing.AsBackOffice;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
@AsBackOffice
class JdbcEnrichmentCandidatesIT {

  static final int MIN_TEXT = 40;
  static final int MAX_FAILED = 3;
  static final String LONG = "A description long enough for the model to cite something.";
  static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");

  @Autowired JdbcEnrichmentCandidates candidates;

  @Autowired JdbcClient jdbc;

  @Autowired TransactionTemplate tx;

  final List<UUID> created = new ArrayList<>();

  @AfterEach
  void clean() {
    created.forEach(id -> jdbc.sql("DELETE FROM resource WHERE id = :id").param("id", id).update());
    jdbc.sql("DELETE FROM source WHERE id = 'aa-candidates'").update();
  }

  private UUID resource(String title, String origin, String status, Instant discovered) {
    UUID id = UUID.randomUUID();
    String url = "https://enrichment-candidates.example/" + id;
    jdbc.sql(
            """
            INSERT INTO resource (id, canonical_url, url, title, description_origin, type, audience, data_origin,
                                  enrichment_status, first_discovered_at, open_source)
            VALUES (:id, :url, :url, :title, 'SOURCE_PROVIDED', 'OTHER', 'BUILD_WITH_AI', :origin, :status, :at, NULL)
            """)
        .param("id", id)
        .param("url", url)
        .param("title", title)
        .param("origin", origin)
        .param("status", status)
        .param("at", java.sql.Timestamp.from(discovered))
        .update();
    created.add(id);
    return id;
  }

  private UUID record(
      UUID resource, String sourceId, String title, String description, String license) {
    UUID id = UUID.randomUUID();
    String url = "https://enrichment-candidates.example/record/" + id;
    jdbc.sql(
            """
            INSERT INTO source_record (id, source_id, external_id, resource_id, source_url, canonical_url, title,
                                       description, type, license, retrieved_at, fingerprint)
            VALUES (:id, :source, :id::text, :rid, :url, :url, :title, :description, 'MCP_SERVER', :license, now(), 'fp')
            """)
        .param("id", id)
        .param("source", sourceId)
        .param("rid", resource)
        .param("url", url)
        .param("title", title)
        .param("description", description)
        .param("license", license)
        .update();
    return id;
  }

  private UUID withText(String title, String origin, String status, Instant discovered) {
    UUID id = resource(title, origin, status, discovered);
    record(id, "curated", title, LONG, null);
    return id;
  }

  private UUID popular(String popularity, UUID resource) {
    jdbc.sql("UPDATE resource SET popularity = :popularity WHERE id = :id")
        .param("popularity", new BigDecimal(popularity))
        .param("id", resource)
        .update();
    return resource;
  }

  private void run(UUID resource, String outcome, String fingerprint, Instant startedAt) {
    jdbc.sql(
            """
            INSERT INTO enrichment_run (id, resource_id, provider, model, prompt_version, input_fingerprint, started_at, outcome)
            VALUES (:id, :rid, 'fake', 'm', 'v', :fp, :at, :outcome)
            """)
        .param("id", UUID.randomUUID())
        .param("rid", resource)
        .param("fp", fingerprint)
        .param("at", java.sql.Timestamp.from(startedAt))
        .param("outcome", outcome)
        .update();
  }

  private List<UUID> pendingOfThisTest() {
    return candidates.pending(10_000, MIN_TEXT, MAX_FAILED).stream()
        .filter(created::contains)
        .toList();
  }

  private void sourceStated(UUID record, String vitality) {
    jdbc.sql(
            "UPDATE source_record SET extra = jsonb_set(extra, '{vitality}', CAST(:v AS jsonb)) WHERE id = :id")
        .param("v", vitality)
        .param("id", record)
        .update();
  }

  private String vitalityOf(UUID record) {
    return jdbc.sql(
            """
            SELECT concat_ws(' ', 'archived', extra -> 'vitality' ->> 'archived',
                             'fork', extra -> 'vitality' ->> 'fork',
                             'checked', coalesce(extra -> 'vitality' ->> 'checkedAt', 'never'))
            FROM source_record WHERE id = :id
            """)
        .param("id", record)
        .query(String.class)
        .single();
  }

  @Test
  void aVisitKeepsTheFactsTheSourceStatedBeforeIt() {
    UUID resource = resource("Forked", "INDEXED", "NOT_RUN", T0);
    UUID visited = record(resource, "github", "acme/forked", "A forked repository.", "MIT");
    sourceStated(
        visited, "{\"archived\":false,\"fork\":true,\"statedAt\":\"2025-12-31T00:00:00Z\"}");

    candidates.recordArchived(visited, true, T0);

    assertThat(vitalityOf(visited)).isEqualTo("archived true fork true checked " + T0);
  }

  @Test
  void aVisitOlderThanWhatTheSourceStatesOfArchivedDoesNotReplaceIt() {
    UUID resource = resource("Unarchived", "INDEXED", "NOT_RUN", T0);
    UUID visited = record(resource, "github", "acme/unarchived", "A living repository.", "MIT");
    sourceStated(
        visited, "{\"archived\":false,\"fork\":false,\"statedAt\":\"2026-01-02T00:00:00Z\"}");

    candidates.recordArchived(visited, true, T0);

    assertThat(vitalityOf(visited)).isEqualTo("archived false fork false checked never");
  }

  @Test
  void aVisitRecordsOnTheRecordIfItsRepositoryIsArchivedAndKeepsTheOtherFacts() {
    UUID resource = resource("Visited", "INDEXED", "NOT_RUN", T0);
    UUID visited = record(resource, "github", "acme/visited", "A visited repository.", "MIT");
    String before =
        jdbc.sql("SELECT extra ->> 'dataOrigin' FROM source_record WHERE id = :id")
            .param("id", visited)
            .query(String.class)
            .optional()
            .orElse(null);

    candidates.recordArchived(visited, true, T0);

    assertThat(
            jdbc.sql(
                    """
                    SELECT extra -> 'vitality' ->> 'archived' || ' ' || (extra -> 'vitality' ->> 'checkedAt')
                    FROM source_record WHERE id = :id
                    """)
                .param("id", visited)
                .query(String.class)
                .single())
        .isEqualTo("true " + T0);
    assertThat(
            jdbc.sql("SELECT extra ->> 'dataOrigin' FROM source_record WHERE id = :id")
                .param("id", visited)
                .query(String.class)
                .optional()
                .orElse(null))
        .isEqualTo(before);
  }

  @Test
  void aQuarterOfEachBatchGoesToTheOldestNeverRunResources() {
    UUID oldest =
        withText("Waiting a century", "INDEXED", "NOT_RUN", T0.minus(36_500, ChronoUnit.DAYS));
    List<UUID> newest = new ArrayList<>();
    for (int i = 1; i <= 4; i++) {
      newest.add(
          withText("Brand new " + i, "INDEXED", "NOT_RUN", T0.plus(36_500 + i, ChronoUnit.DAYS)));
    }

    List<UUID> batch = candidates.pending(4, MIN_TEXT, MAX_FAILED);

    assertThat(batch).hasSize(4).contains(oldest).doesNotContain(newest.getFirst());
    assertThat(candidates.oldestPending(MIN_TEXT)).isEqualTo(T0.minus(36_500, ChronoUnit.DAYS));
  }

  @Test
  void neverRunResourcesComeFirstThenNewestDiscoveriesAndLlmUnavailableIsIncluded() {
    UUID unavailableNew =
        withText("Unavailable new", "INDEXED", "LLM_UNAVAILABLE", T0.plus(3, ChronoUnit.DAYS));
    UUID notRunOld = withText("Not run old", "OBSERVED", "NOT_RUN", T0);
    UUID notRunNew = withText("Not run new", "CURATED", "NOT_RUN", T0.plus(1, ChronoUnit.DAYS));
    withText("Validated", "INDEXED", "VALIDATED", T0.plus(2, ChronoUnit.DAYS));

    assertThat(pendingOfThisTest()).containsExactly(notRunNew, notRunOld, unavailableNew);
  }

  @Test
  void theMostPopularResourceComesFirstWhateverItsAgeAndNeverRunStillComeBeforeRetries() {
    UUID popularRetry = popular("1", withText("Popular retry", "INDEXED", "LLM_UNAVAILABLE", T0));
    UUID unknownNew = withText("Unknown new", "INDEXED", "NOT_RUN", T0.plus(3, ChronoUnit.DAYS));
    UUID knownOlder =
        popular("0.4", withText("Known older", "INDEXED", "NOT_RUN", T0.plus(1, ChronoUnit.DAYS)));
    UUID popularOld = popular("0.9", withText("Popular old", "INDEXED", "NOT_RUN", T0));

    assertThat(pendingOfThisTest())
        .containsExactly(popularOld, knownOlder, unknownNew, popularRetry);
  }

  @Test
  void theQueuePagesByPopularityAndKeepsItsPlaceAmongEquallyPopularResources() {
    UUID unknownOld = withText("Unknown old", "INDEXED", "NOT_RUN", T0);
    UUID unknownNew = withText("Unknown new", "INDEXED", "NOT_RUN", T0.plus(1, ChronoUnit.DAYS));
    UUID halfOld = popular("0.5", withText("Half old", "INDEXED", "NOT_RUN", T0));
    UUID halfNew =
        popular("0.5", withText("Half new", "INDEXED", "NOT_RUN", T0.plus(2, ChronoUnit.DAYS)));
    UUID top = popular("0.9", withText("Top", "INDEXED", "NOT_RUN", T0));
    UUID retry =
        popular(
            "0.7", withText("Retry", "INDEXED", "LLM_UNAVAILABLE", T0.plus(4, ChronoUnit.DAYS)));

    assertThat(pagedOfThisTest(View.WAITING))
        .containsExactly(top, halfNew, halfOld, unknownNew, unknownOld, retry)
        .containsExactlyElementsOf(pendingOfThisTest());
  }

  @Test
  void aPrioritisedResourceStaysAboveAMorePopularOne() {
    UUID popularOne = popular("1", withText("Popular", "INDEXED", "NOT_RUN", T0));
    UUID moved = withText("Moved", "INDEXED", "NOT_RUN", T0.minus(1, ChronoUnit.DAYS));
    control("prioritised_at = :at", moved, T0.plus(1, ChronoUnit.DAYS));

    assertThat(pendingOfThisTest()).containsExactly(moved, popularOne);
    assertThat(pagedOfThisTest(View.WAITING)).containsExactly(moved, popularOne);
  }

  @Test
  void onlyIndexedObservedAndCuratedResourcesWithEnoughSourceTextArePending() {
    UUID kept = withText("Indexed", "INDEXED", "NOT_RUN", T0);
    withText("Demo", "DEMO", "NOT_RUN", T0);
    UUID bare = resource("Bare title", "INDEXED", "NOT_RUN", T0);
    record(bare, "curated", "Bare title", "Too short.", null);

    assertThat(pendingOfThisTest()).containsExactly(kept);
  }

  @Test
  void theCountIsTheSizeOfTheWholeBacklog() {
    withText("Counted", "INDEXED", "NOT_RUN", T0);
    resource("Bare title", "INDEXED", "NOT_RUN", T0);

    assertThat(candidates.pendingCount(MIN_TEXT, MAX_FAILED))
        .isEqualTo(candidates.pending(Integer.MAX_VALUE, MIN_TEXT, MAX_FAILED).size());
  }

  @Test
  void theQueuePagesTheBacklogInTheScoutsOrderEachResourceOnce() {
    UUID retry = withText("Retry", "INDEXED", "REJECTED", T0.plus(5, ChronoUnit.DAYS));
    run(retry, "FAILED", "f1", T0.plus(6, ChronoUnit.DAYS));
    UUID unavailable =
        withText("Unavailable", "INDEXED", "LLM_UNAVAILABLE", T0.plus(4, ChronoUnit.DAYS));
    run(unavailable, "LLM_UNAVAILABLE", "u1", T0.plus(4, ChronoUnit.DAYS));
    UUID newest = withText("Newest", "INDEXED", "NOT_RUN", T0.plus(3, ChronoUnit.DAYS));
    UUID older = withText("Older", "INDEXED", "NOT_RUN", T0.plus(2, ChronoUnit.DAYS));
    UUID oldest = withText("Oldest", "INDEXED", "NOT_RUN", T0.plus(1, ChronoUnit.DAYS));

    assertThat(pagedOfThisTest(View.WAITING))
        .containsExactly(newest, older, oldest, retry, unavailable)
        .containsExactlyElementsOf(pendingOfThisTest());
  }

  private List<UUID> pagedOfThisTest(View view) {
    List<UUID> paged = new ArrayList<>();
    EnrichmentCandidates.BacklogCursor after = null;
    while (true) {
      List<BacklogEntry> page = candidates.backlog(view, after, 2, MIN_TEXT, MAX_FAILED);
      if (page.isEmpty()) {
        break;
      }
      page.stream().map(BacklogEntry::id).forEach(paged::add);
      after = page.getLast().cursor();
    }
    return paged.stream().filter(created::contains).toList();
  }

  private BacklogEntry entryOf(View view, UUID id) {
    return candidates.backlog(view, null, 10_000, MIN_TEXT, MAX_FAILED).stream()
        .filter(e -> e.id().equals(id))
        .findFirst()
        .orElseThrow();
  }

  private void control(String set, UUID id, Instant at) {
    jdbc.sql(
            "INSERT INTO enrichment_queue_control (resource_id, updated_at) VALUES (:id, now())"
                + " ON CONFLICT (resource_id) DO NOTHING")
        .param("id", id)
        .update();
    jdbc.sql("UPDATE enrichment_queue_control SET " + set + " WHERE resource_id = :id")
        .param("id", id)
        .param("at", java.sql.Timestamp.from(at))
        .update();
  }

  private Standing standing(UUID id) {
    return candidates.standing(id, MIN_TEXT, MAX_FAILED);
  }

  @Test
  void prioritisedComeFirstSkippedAreAbsentAndEveryReadKeepsOneOrder() {
    UUID retry = withText("Retry", "INDEXED", "REJECTED", T0.plus(5, ChronoUnit.DAYS));
    run(retry, "FAILED", "f1", T0.plus(6, ChronoUnit.DAYS));
    UUID newest = withText("Newest", "INDEXED", "NOT_RUN", T0.plus(3, ChronoUnit.DAYS));
    UUID skipped = withText("Skipped", "INDEXED", "NOT_RUN", T0.plus(2, ChronoUnit.DAYS));
    UUID oldest = withText("Oldest", "INDEXED", "NOT_RUN", T0.plus(1, ChronoUnit.DAYS));
    long before = candidates.pendingCount(MIN_TEXT, MAX_FAILED);
    int topBefore = candidates.prioritisedCount(MIN_TEXT, MAX_FAILED);

    control("prioritised_at = :at", retry, T0.plus(7, ChronoUnit.DAYS));
    control("prioritised_at = :at", oldest, T0.plus(7, ChronoUnit.DAYS));
    control("skipped_at = :at, skip_reason = 'spam'", skipped, T0.plus(7, ChronoUnit.DAYS));

    assertThat(pendingOfThisTest()).containsExactly(retry, oldest, newest);
    assertThat(pagedOfThisTest(View.WAITING)).containsExactly(retry, oldest, newest);
    assertThat(candidates.pending(2, MIN_TEXT, MAX_FAILED)).containsExactly(retry, oldest);
    assertThat(candidates.pendingCount(MIN_TEXT, MAX_FAILED)).isEqualTo(before - 1);
    assertThat(candidates.prioritisedCount(MIN_TEXT, MAX_FAILED)).isEqualTo(topBefore + 2);
    assertThat(pagedOfThisTest(View.SKIPPED)).containsExactly(skipped);

    BacklogEntry top = entryOf(View.WAITING, retry);
    assertThat(top.state()).isEqualTo(State.PRIORITISED);
    assertThat(top.waiting()).isEqualTo(Waiting.RETRY);
    BacklogEntry out = entryOf(View.SKIPPED, skipped);
    assertThat(out.state()).isEqualTo(State.SKIPPED);
    assertThat(out.waiting()).isNull();
    assertThat(out.skipReason()).isEqualTo("spam");
    assertThat(standing(skipped)).isEqualTo(new Standing(true, false, true, false));
    assertThat(standing(oldest)).isEqualTo(new Standing(true, false, false, true));
  }

  @Test
  void aMoveToTheTopEndsWithTheNextRun() {
    UUID newest = withText("Newest", "INDEXED", "NOT_RUN", T0.plus(3, ChronoUnit.DAYS));
    UUID moved = withText("Moved", "INDEXED", "LLM_UNAVAILABLE", T0);
    control("prioritised_at = :at", moved, T0.plus(4, ChronoUnit.DAYS));
    assertThat(pendingOfThisTest()).containsExactly(moved, newest);

    run(moved, "LLM_UNAVAILABLE", "u1", T0.plus(5, ChronoUnit.DAYS));

    assertThat(pendingOfThisTest()).containsExactly(newest, moved);
    assertThat(standing(moved).prioritised()).isFalse();
  }

  @Test
  void aRequeueAfterTheCapCountsOnlyTheFailedRunsAfterIt() {
    UUID failing = withText("Failing", "INDEXED", "REJECTED", T0);
    run(failing, "FAILED", "f1", T0.plus(1, ChronoUnit.HOURS));
    run(failing, "FAILED", "f2", T0.plus(2, ChronoUnit.HOURS));
    run(failing, "FAILED", "f3", T0.plus(3, ChronoUnit.HOURS));
    assertThat(pendingOfThisTest()).isEmpty();
    assertThat(pagedOfThisTest(View.OUT_OF_RETRIES)).containsExactly(failing);
    assertThat(entryOf(View.OUT_OF_RETRIES, failing).state()).isEqualTo(State.OUT_OF_RETRIES);
    assertThat(standing(failing)).isEqualTo(new Standing(false, true, false, false));

    control("failures_reset_at = :at", failing, T0.plus(4, ChronoUnit.HOURS));

    assertThat(pendingOfThisTest()).containsExactly(failing);
    assertThat(pagedOfThisTest(View.OUT_OF_RETRIES)).isEmpty();
    assertThat(entryOf(View.WAITING, failing).failedRuns()).isZero();
    run(failing, "FAILED", "f4", T0.plus(5, ChronoUnit.HOURS));
    run(failing, "FAILED", "f5", T0.plus(6, ChronoUnit.HOURS));
    assertThat(pendingOfThisTest()).containsExactly(failing);
    run(failing, "FAILED", "f6", T0.plus(7, ChronoUnit.HOURS));
    assertThat(pendingOfThisTest()).isEmpty();
  }

  @Test
  void theSkippedViewListsTheLatestSkipFirst() {
    UUID first = withText("First skip", "INDEXED", "NOT_RUN", T0.plus(2, ChronoUnit.DAYS));
    UUID second = withText("Second skip", "INDEXED", "NOT_RUN", T0);
    UUID third = withText("Third skip", "INDEXED", "LLM_UNAVAILABLE", T0.plus(1, ChronoUnit.DAYS));
    Instant now = Instant.parse("2030-01-01T00:00:00Z");
    control("skipped_at = :at", first, now);
    control("skipped_at = :at", second, now.plus(1, ChronoUnit.HOURS));
    control("skipped_at = :at", third, now.plus(2, ChronoUnit.HOURS));

    assertThat(pagedOfThisTest(View.SKIPPED)).containsExactly(third, second, first);
    assertThat(entryOf(View.SKIPPED, second).skippedAt()).isEqualTo(now.plus(1, ChronoUnit.HOURS));
    assertThat(pendingOfThisTest()).isEmpty();
  }

  @Test
  void anUnknownResourceHasNoStanding() {
    assertThat(standing(UUID.randomUUID())).isNull();
    UUID done = withText("Done", "INDEXED", "VALIDATED", T0);
    assertThat(standing(done)).isEqualTo(new Standing(false, false, false, false));
  }

  @Test
  void aQueueEntrySaysWhyItWaitsAndShowsItsRunsSoFar() {
    UUID retry = withText("Retry", "INDEXED", "REJECTED", T0.plus(9, ChronoUnit.DAYS));
    run(retry, "FAILED", "f1", T0.plus(10, ChronoUnit.DAYS));
    run(retry, "FAILED", "f2", T0.plus(11, ChronoUnit.DAYS));
    UUID fresh = withText("Fresh", "INDEXED", "NOT_RUN", T0.plus(8, ChronoUnit.DAYS));

    var entries =
        candidates.backlog(View.WAITING, null, 10_000, MIN_TEXT, MAX_FAILED).stream()
            .filter(e -> created.contains(e.id()))
            .toList();

    assertThat(entries)
        .containsExactly(
            new BacklogEntry(
                fresh,
                "Fresh",
                State.WAITING,
                Waiting.NEW,
                T0.plus(8, ChronoUnit.DAYS),
                BigDecimal.ZERO,
                0,
                null,
                null,
                null,
                null),
            new BacklogEntry(
                retry,
                "Retry",
                State.WAITING,
                Waiting.RETRY,
                T0.plus(9, ChronoUnit.DAYS),
                BigDecimal.ZERO,
                2,
                "FAILED",
                T0.plus(11, ChronoUnit.DAYS),
                null,
                null));
  }

  @Test
  void aFailedRunIsRetriedUntilTheCap() {
    UUID failing = withText("Failing", "INDEXED", "REJECTED", T0);
    run(failing, "FAILED", "f1", T0.plus(1, ChronoUnit.HOURS));
    run(failing, "FAILED", "f2", T0.plus(2, ChronoUnit.HOURS));
    assertThat(pendingOfThisTest()).containsExactly(failing);

    run(failing, "FAILED", "f3", T0.plus(3, ChronoUnit.HOURS));
    assertThat(pendingOfThisTest()).isEmpty();
  }

  @Test
  void aRejectedRunWhoseLastAttemptDidNotFailIsNotRetried() {
    UUID rejected = withText("Rejected", "INDEXED", "REJECTED", T0);
    run(rejected, "FAILED", "f1", T0.plus(1, ChronoUnit.HOURS));
    run(rejected, "REJECTED", "r1", T0.plus(2, ChronoUnit.HOURS));

    assertThat(pendingOfThisTest()).isEmpty();
  }

  @Test
  void withEnoughTextKeepsTheInputOrder() {
    UUID a = withText("A", "INDEXED", "NOT_RUN", T0);
    UUID b = withText("B", "INDEXED", "NOT_RUN", T0);
    UUID bare = resource("Bare", "INDEXED", "NOT_RUN", T0);
    record(bare, "curated", "Bare", null, null);

    assertThat(candidates.withEnoughText(List.of(b, UUID.randomUUID(), bare, a), MIN_TEXT))
        .containsExactly(b, a);
    assertThat(candidates.withEnoughText(List.of(), MIN_TEXT)).isEmpty();
  }

  @Test
  void aSkippedResourceIsNotEnrichedWhenItsTextChanges() {
    UUID kept = withText("Kept", "INDEXED", "NOT_RUN", T0);
    UUID skipped = withText("Skipped", "INDEXED", "NOT_RUN", T0);
    control("skipped_at = :at", skipped, T0);

    assertThat(candidates.withEnoughText(List.of(skipped, kept), MIN_TEXT)).containsExactly(kept);
  }

  @Test
  void aCandidateHasItsSourcesInSourceIdOrderAndTheLicenceOfTheFirstSourceThatStatesOne() {
    jdbc.sql(
            """
            INSERT INTO source (id, mode)
            VALUES ('aa-candidates', 'CURATED')
            ON CONFLICT (id) DO NOTHING
            """)
        .update();
    UUID id = resource("Candidate", "INDEXED", "NOT_RUN", T0);
    UUID curated = record(id, "curated", "From curated", "Curated text.", "Apache-2.0");
    UUID first = record(id, "aa-candidates", "From first", null, null);
    UUID other = resource("Other", "INDEXED", "NOT_RUN", T0);
    record(other, "aa-candidates", "Other", "x", "MIT");

    Candidate candidate = candidates.candidate(id);

    assertThat(candidate.title()).isEqualTo("Candidate");
    assertThat(candidate.type()).isEqualTo("OTHER");
    assertThat(candidate.openSource()).isNull();
    assertThat(candidate.license()).isEqualTo("Apache-2.0");
    assertThat(candidate.sources())
        .extracting(SourceText::recordId)
        .containsExactly(first, curated);
    assertThat(candidate.sources())
        .extracting(SourceText::text)
        .containsExactly("From first\n", "From curated\nCurated text.");
    assertThat(candidates.candidate(resource("No records", "INDEXED", "NOT_RUN", T0))).isNull();
  }

  @Test
  void eachSourceTextSaysWhereTheRecordCameFromAndWhichRepositoryItNames() {
    UUID id = resource("Origin", "INDEXED", "NOT_RUN", T0);
    UUID rec = record(id, "curated", "Origin", LONG, null);
    jdbc.sql(
            "UPDATE source_record SET repository_url = 'https://github.com/acme/origin' WHERE id = :id")
        .param("id", rec)
        .update();

    SourceText source = candidates.candidate(id).sources().getFirst();

    assertThat(source.sourceId()).isEqualTo("curated");
    assertThat(source.externalId()).isEqualTo(rec.toString());
    assertThat(source.repositoryUrl()).isEqualTo("https://github.com/acme/origin");
    assertThat(source.url()).isEqualTo("https://enrichment-candidates.example/record/" + rec);
  }

  @Test
  void theLastJudgedFingerprintSkipsRunsWithoutAJudgedAnswer() {
    UUID id = withText("Fingerprints", "INDEXED", "NOT_RUN", T0);
    assertThat(candidates.lastJudgedFingerprint(id)).isNull();

    run(id, "PARTIAL", "judged", T0.plus(1, ChronoUnit.HOURS));
    run(id, "FAILED", "failed", T0.plus(2, ChronoUnit.HOURS));
    run(id, "LLM_UNAVAILABLE", "unavailable", T0.plus(3, ChronoUnit.HOURS));

    assertThat(candidates.lastJudgedFingerprint(id)).isEqualTo("judged");
  }

  private UUID typed(String title, String origin, String type) {
    UUID id = resource(title, origin, "NOT_RUN", T0);
    jdbc.sql("UPDATE resource SET type = :type WHERE id = :id")
        .param("type", type)
        .param("id", id)
        .update();
    return id;
  }

  @Test
  void titledFindsTheOneIndexedOrCuratedToolWithTheTitleAndLeavesTheResourceItselfOut() {
    String title = "Titled " + UUID.randomUUID();
    UUID self = typed(title, "INDEXED", "LIBRARY");
    UUID tool = typed(title.toUpperCase(), "CURATED", "PRODUCT");
    typed(title, "OBSERVED", "MODEL");
    typed(title, "INDEXED", "ARTICLE");

    Map<String, UUID> found =
        candidates.titled(List.of(title.toLowerCase(), "nothing has this title"), self);

    assertThat(found).containsExactly(Map.entry(title.toLowerCase(), tool));
    assertThat(candidates.titled(List.of(), self)).isEmpty();
  }

  @Test
  void titledFindsARepositoryByItsNameWhateverSeparatesItsWords() {
    String name = "tool " + UUID.randomUUID().toString().substring(0, 8);
    UUID self = typed("Self " + name, "INDEXED", "LIBRARY");
    UUID repository =
        typed("some-owner/" + name.replace(' ', '-').toUpperCase(), "INDEXED", "AGENT");

    assertThat(candidates.titled(List.of(name), self)).containsExactly(Map.entry(name, repository));
    assertThat(candidates.titled(List.of(name.replace(' ', '_')), self))
        .containsExactly(Map.entry(name.replace(' ', '_'), repository));
  }

  @Test
  void aRepositoryNameTwoToolsShareOrATitleAndANameIsAmbiguous() {
    String name = "tool" + UUID.randomUUID().toString().substring(0, 8);
    UUID self = typed("Self " + name, "INDEXED", "LIBRARY");
    typed("first-owner/" + name, "INDEXED", "LIBRARY");
    typed("second-owner/" + name, "CURATED", "PRODUCT");
    String other = "tool" + UUID.randomUUID().toString().substring(0, 8);
    typed(other, "INDEXED", "SKILL");
    typed("an-owner/" + other, "INDEXED", "AGENT");

    assertThat(candidates.titled(List.of(name, other), self)).isEmpty();
  }

  @Test
  void anMcpServerASkillOrAPageASearchSawIsNeverATargetByItsRepositoryName() {
    String name = "tool" + UUID.randomUUID().toString().substring(0, 8);
    UUID self = typed("Self " + name, "INDEXED", "LIBRARY");
    typed("com.example/" + name, "INDEXED", "MCP_SERVER");
    typed("a-user/" + name, "INDEXED", "SKILL");
    typed("another-user/" + name, "OBSERVED", "LIBRARY");

    assertThat(candidates.titled(List.of(name), self)).isEmpty();
  }

  @Test
  void anMcpServerIsATargetByItsTitleOnlyWhenTheNameSaysMcp() {
    String product = "tool" + UUID.randomUUID().toString().substring(0, 8);
    UUID self = typed("Self " + product, "INDEXED", "LIBRARY");
    typed(product, "INDEXED", "MCP_SERVER");
    String server = product + "-mcp";
    UUID named = typed(server, "INDEXED", "MCP_SERVER");
    String spaced = "mcp " + product;
    UUID spacedServer = typed(spaced.toUpperCase(), "INDEXED", "MCP_SERVER");

    assertThat(candidates.titled(List.of(product, server, spaced), self))
        .containsOnly(Map.entry(server, named), Map.entry(spaced, spacedServer));
  }

  @Test
  void aCandidateCarriesThePlatformsAndUseCasesItsSourcesState() {
    UUID id = withText("Stated " + UUID.randomUUID(), "INDEXED", "NOT_RUN", T0);
    for (String[] label :
        new String[][] {
          {"PLATFORM", "python"}, {"PLATFORM", "cli"}, {"USE_CASE", "coding"}, {"TAG", "nlp"}
        }) {
      jdbc.sql("INSERT INTO resource_label (resource_id, kind, value) VALUES (:id, :kind, :value)")
          .param("id", id)
          .param("kind", label[0])
          .param("value", label[1])
          .update();
    }

    assertThat(candidates.candidate(id).sourceStated())
        .containsOnly(
            Map.entry("platforms", java.util.Set.of("python", "cli")),
            Map.entry("useCase", java.util.Set.of("coding")));
  }

  @Test
  void aTitleTwoToolsShareIsAmbiguousAndFindsNothing() {
    String title = "Shared " + UUID.randomUUID();
    UUID self = typed("Self " + title, "INDEXED", "LIBRARY");
    typed(title, "INDEXED", "LIBRARY");
    typed(title, "CURATED", "MODEL");

    assertThat(candidates.titled(List.of(title.toLowerCase()), self)).isEmpty();
  }

  @Test
  void anAcceptedProductOlderThanTheBatchStillComesFirst() {
    UUID accepted = withText("Accepted old", "INDEXED", "NOT_RUN", T0.minus(3650, ChronoUnit.DAYS));
    UUID newer = withText("Newer", "INDEXED", "NOT_RUN", T0.plus(1, ChronoUnit.DAYS));
    jdbc.sql(
            """
            INSERT INTO candidate_decision (kind, candidate_key, status, decided_at)
            VALUES ('RESOURCE_CANDIDATE', :key, 'ACCEPTED', now())
            """)
        .param("key", accepted)
        .update();
    try {
      List<UUID> batch = candidates.pending(Integer.MAX_VALUE, MIN_TEXT, MAX_FAILED);
      assertThat(batch).contains(accepted, newer);
      assertThat(batch.indexOf(accepted)).isLessThan(batch.indexOf(newer));
      jdbc.sql("UPDATE resource SET enrichment_status = 'VALIDATED' WHERE id = :id")
          .param("id", accepted)
          .update();
      assertThat(candidates.pending(10_000, MIN_TEXT, MAX_FAILED)).doesNotContain(accepted);
    } finally {
      jdbc.sql("DELETE FROM candidate_decision WHERE candidate_key = :key")
          .param("key", accepted)
          .update();
    }
  }

  @Test
  void anAcceptedProductWithoutSourceTextIsPending() {
    UUID accepted = resource("Acme", "INDEXED", "NOT_RUN", T0);
    record(accepted, "github-awesome", "Acme", null, null);
    UUID bare = resource("Bare title", "INDEXED", "NOT_RUN", T0);
    record(bare, "github-awesome", "Bare title", null, null);
    jdbc.sql(
            """
            INSERT INTO candidate_decision (kind, candidate_key, status, decided_at)
            VALUES ('RESOURCE_CANDIDATE', :key, 'ACCEPTED', now())
            """)
        .param("key", accepted)
        .update();
    try {
      assertThat(pendingOfThisTest()).containsExactly(accepted);
      assertThat(candidates.pendingCount(MIN_TEXT, MAX_FAILED))
          .isEqualTo(candidates.pending(Integer.MAX_VALUE, MIN_TEXT, MAX_FAILED).size());
      assertThat(candidates.backlog(View.WAITING, null, 10_000, MIN_TEXT, MAX_FAILED))
          .extracting(EnrichmentCandidates.BacklogEntry::id)
          .contains(accepted)
          .doesNotContain(bare);
    } finally {
      jdbc.sql("DELETE FROM candidate_decision WHERE candidate_key = :key")
          .param("key", accepted)
          .update();
    }
  }

  @Test
  void aRerunRequestHidesEarlierJudgedRunsUntilTheNextRun() {
    UUID id = withText("Rerun", "INDEXED", "VALIDATED", T0);
    run(id, "VALIDATED", "before", T0.plus(1, ChronoUnit.HOURS));
    assertThat(candidates.lastJudgedFingerprint(id)).isEqualTo("before");
    jdbc.sql(
            "UPDATE resource SET enrichment_status = 'NOT_RUN', enrichment_rerun_requested_at = :at WHERE id = :id")
        .param("at", java.sql.Timestamp.from(T0.plus(2, ChronoUnit.HOURS)))
        .param("id", id)
        .update();
    assertThat(candidates.lastJudgedFingerprint(id)).isNull();
    run(id, "PARTIAL", "after", T0.plus(3, ChronoUnit.HOURS));
    assertThat(candidates.lastJudgedFingerprint(id)).isEqualTo("after");
  }

  @Test
  void theNextBatchReadsTheBacklogIndex() {
    String plan =
        tx.execute(status -> {
          jdbc.sql("ANALYZE resource").update();
          jdbc.sql("SET LOCAL enable_seqscan = off").update();
          return String.join(
              "\n",
              jdbc.sql("EXPLAIN " + JdbcEnrichmentCandidates.pendingSql())
                  .param("maxFailed", MAX_FAILED)
                  .param("minText", MIN_TEXT)
                  .param("limit", 20)
                  .param("oldest", JdbcEnrichmentCandidates.oldestShare(20))
                  .query(String.class)
                  .list());
        });
    assertThat(plan)
        .contains("resource_enrichment_value_idx")
        .doesNotContain("Seq Scan on resource");
  }
}
