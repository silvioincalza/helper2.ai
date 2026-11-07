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

/**
 * The enrichment backlog and a resource's source text, read from the catalog tables on real
 * PostgreSQL.
 */
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
  void onlyIndexedObservedAndCuratedResourcesWithEnoughSourceTextArePending() {
    UUID kept = withText("Indexed", "INDEXED", "NOT_RUN", T0);
    withText("Demo", "DEMO", "NOT_RUN", T0);
    UUID bare = resource("Bare title", "INDEXED", "NOT_RUN", T0);
    record(bare, "curated", "Bare title", "Too short.", null);

    assertThat(pendingOfThisTest()).containsExactly(kept);
  }

  /** The overview's backlog count follows the same rule as the backlog the Scout takes. */
  @Test
  void theCountIsTheSizeOfTheWholeBacklog() {
    withText("Counted", "INDEXED", "NOT_RUN", T0);
    resource("Bare title", "INDEXED", "NOT_RUN", T0);

    assertThat(candidates.pendingCount(MIN_TEXT, MAX_FAILED))
        .isEqualTo(candidates.pending(Integer.MAX_VALUE, MIN_TEXT, MAX_FAILED).size());
  }

  /** The queue pages the backlog in the Scout's order, each resource once, with no OFFSET. */
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

  /** Every page of [view], two at a time, as the cursor walks it; only this test's resources. */
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

  /**
   * ADR 0045 §7: one order for the next batch, the page and the count. Prioritised first (before
   * the never-run part), skipped absent, each resource once.
   */
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

  /** A move to the top holds until the resource's next run starts. */
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

  /**
   * Out of retries: still in the backlog index (REJECTED), listed in its own view; a requeue counts
   * only the FAILED runs after it, so the resource is due again until the cap is reached anew.
   */
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

  /** The skipped view lists the latest skip first, page by page, so a mistake is easy to undo. */
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

  /** ADR 0045 §7: a skip holds when a collector sees new text, not only in the backlog. */
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
            INSERT INTO source (id, name, mode, homepage, attribution)
            VALUES ('aa-candidates', 'Candidates test', 'CURATED', 'https://helper2.ai/sources', 'Test')
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

  @Test
  void titledMatchesLowerCaseTitlesTheEarliestWinsAndTheResourceItselfIsLeftOut() {
    String title = "Titled " + UUID.randomUUID();
    UUID self = resource(title, "INDEXED", "NOT_RUN", T0);
    UUID earliest =
        resource(title.toUpperCase(), "INDEXED", "NOT_RUN", T0.plus(1, ChronoUnit.DAYS));
    resource(title, "INDEXED", "NOT_RUN", T0.plus(2, ChronoUnit.DAYS));

    Map<String, UUID> found =
        candidates.titled(List.of(title.toLowerCase(), "nothing has this title"), self);

    assertThat(found).containsExactly(Map.entry(title.toLowerCase(), earliest));
    assertThat(candidates.titled(List.of(), self)).isEmpty();
  }

  /**
   * ADR 0056 with the backlog index of ADR 0045 (#219): an accepted product comes first even when
   * it is older than the newest [limit] resources of its part.
   */
  @Test
  void anAcceptedProductOlderThanTheBatchStillComesFirst() {
    UUID accepted = withText("Accepted old", "INDEXED", "NOT_RUN", T0.minus(3650, ChronoUnit.DAYS));
    UUID newer = withText("Newer", "INDEXED", "NOT_RUN", T0.plus(1, ChronoUnit.DAYS));
    String url =
        jdbc.sql("SELECT canonical_url FROM resource WHERE id = :id")
            .param("id", accepted)
            .query(String.class)
            .single();
    jdbc.sql(
            """
            INSERT INTO review_item (id, kind, status, candidate_url, reason, resolved_at)
            VALUES (:id, 'RESOURCE_CANDIDATE', 'ACCEPTED', :url, 'test', now())
            """)
        .param("id", UUID.randomUUID())
        .param("url", url)
        .update();
    try {
      // Other tests accept products too (they wait without source text, ADR 0058), so the head of
      // the batch is shared: the accepted product comes before the newer never-run one.
      List<UUID> batch = candidates.pending(Integer.MAX_VALUE, MIN_TEXT, MAX_FAILED);
      assertThat(batch).contains(accepted, newer);
      assertThat(batch.indexOf(accepted)).isLessThan(batch.indexOf(newer));
      // Once enriched it leaves the backlog: an accepted product is not pinned at the head.
      jdbc.sql("UPDATE resource SET enrichment_status = 'VALIDATED' WHERE id = :id")
          .param("id", accepted)
          .update();
      assertThat(candidates.pending(10_000, MIN_TEXT, MAX_FAILED)).doesNotContain(accepted);
    } finally {
      jdbc.sql("DELETE FROM review_item WHERE candidate_url = :url").param("url", url).update();
    }
  }

  /**
   * ADR 0058: a product linked from an awesome list keeps no snippet, so it has no source text.
   * Once a person accepts it, it still waits for enrichment: the ladder reads its page. The count
   * and the queue follow the same rule.
   */
  @Test
  void anAcceptedProductWithoutSourceTextIsPending() {
    UUID accepted = resource("Acme", "INDEXED", "NOT_RUN", T0);
    record(accepted, "github-awesome", "Acme", null, null);
    UUID bare = resource("Bare title", "INDEXED", "NOT_RUN", T0);
    record(bare, "github-awesome", "Bare title", null, null);
    String url =
        jdbc.sql("SELECT canonical_url FROM resource WHERE id = :id")
            .param("id", accepted)
            .query(String.class)
            .single();
    jdbc.sql(
            """
            INSERT INTO review_item (id, kind, status, candidate_url, reason, resolved_at)
            VALUES (:id, 'RESOURCE_CANDIDATE', 'ACCEPTED', :url, 'test', now())
            """)
        .param("id", UUID.randomUUID())
        .param("url", url)
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
      jdbc.sql("DELETE FROM review_item WHERE candidate_url = :url").param("url", url).update();
    }
  }

  /** ADR 0056: a re-run request makes the earlier judged runs stop counting, a later run counts. */
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

  /**
   * The two backlog parts read resources newest first through an index, not the whole table. On a
   * small test database the planner may take the plain discovery index (V6) instead of the partial
   * backlog index (#219): both give the order, and the choice depends on the statistics other tests
   * leave behind, so either one passes.
   */
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
                  .query(String.class)
                  .list());
        });
    assertThat(plan)
        .containsAnyOf("resource_enrichment_backlog_idx", "resource_first_discovered_idx")
        .doesNotContain("Seq Scan on resource");
  }
}
