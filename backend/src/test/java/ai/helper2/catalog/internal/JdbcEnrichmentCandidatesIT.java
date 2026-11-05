package ai.helper2.catalog.internal;

import static org.assertj.core.api.Assertions.assertThat;

import ai.helper2.TestcontainersConfiguration;
import ai.helper2.catalog.enrichment.EnrichmentCandidates;
import ai.helper2.catalog.enrichment.EnrichmentCandidates.Candidate;
import ai.helper2.catalog.enrichment.EnrichmentCandidates.SourceText;
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

/**
 * The enrichment backlog and a resource's source text, read from the catalog tables on real
 * PostgreSQL.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class JdbcEnrichmentCandidatesIT {

  static final int MIN_TEXT = 40;
  static final int MAX_FAILED = 3;
  static final String LONG = "A description long enough for the model to cite something.";
  static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");

  @Autowired JdbcEnrichmentCandidates candidates;

  @Autowired JdbcClient jdbc;

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

    List<UUID> paged = new ArrayList<>();
    EnrichmentCandidates.BacklogCursor after = null;
    while (true) {
      List<EnrichmentCandidates.BacklogEntry> page =
          candidates.backlog(after, 2, MIN_TEXT, MAX_FAILED);
      if (page.isEmpty()) {
        break;
      }
      page.stream().map(EnrichmentCandidates.BacklogEntry::id).forEach(paged::add);
      after = page.getLast().cursor();
    }

    assertThat(paged.stream().filter(created::contains).toList())
        .containsExactly(newest, older, oldest, retry, unavailable)
        .containsExactlyElementsOf(pendingOfThisTest());
  }

  @Test
  void aQueueEntrySaysWhyItWaitsAndShowsItsRunsSoFar() {
    UUID retry = withText("Retry", "INDEXED", "REJECTED", T0.plus(9, ChronoUnit.DAYS));
    run(retry, "FAILED", "f1", T0.plus(10, ChronoUnit.DAYS));
    run(retry, "FAILED", "f2", T0.plus(11, ChronoUnit.DAYS));
    UUID fresh = withText("Fresh", "INDEXED", "NOT_RUN", T0.plus(8, ChronoUnit.DAYS));

    var entries =
        candidates.backlog(null, 10_000, MIN_TEXT, MAX_FAILED).stream()
            .filter(e -> created.contains(e.id()))
            .toList();

    assertThat(entries)
        .containsExactly(
            new EnrichmentCandidates.BacklogEntry(
                fresh,
                "Fresh",
                EnrichmentCandidates.Waiting.NEW,
                T0.plus(8, ChronoUnit.DAYS),
                0,
                null,
                null),
            new EnrichmentCandidates.BacklogEntry(
                retry,
                "Retry",
                EnrichmentCandidates.Waiting.RETRY,
                T0.plus(9, ChronoUnit.DAYS),
                2,
                "FAILED",
                T0.plus(11, ChronoUnit.DAYS)));
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
}
