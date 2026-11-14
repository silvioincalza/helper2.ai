package ai.helper2.sources.pypi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.helper2.platform.resilience.Resilience;
import ai.helper2.scout.Candidate;
import ai.helper2.scout.CollectedBatch;
import ai.helper2.scout.RunStatus;
import ai.helper2.scout.ScoutCursor;
import ai.helper2.scout.admission.AdmissionFixtures;
import ai.helper2.scout.usecase.CappedReview;
import ai.helper2.search.SourceFailure;
import ai.helper2.search.SourceHit;
import ai.helper2.shared.Audience;
import ai.helper2.shared.ResourceType;
import ai.helper2.sources.SourceFixtures;
import ai.helper2.sources.support.RunInterval;
import ai.helper2.sources.support.SourceSettings;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PypiCollectorTest {

  static final Instant NOW = SourceFixtures.CLOCK.instant();

  static final class FakeQueries implements PypiCollector.Queries {
    final List<List<BigQueryClient.Parameter>> calls = new ArrayList<>();
    final List<Integer> limits = new ArrayList<>();
    List<Map<String, Object>> rows = List.of();
    RuntimeException failure;

    @Override
    public List<Map<String, Object>> query(
        String sql, List<BigQueryClient.Parameter> parameters, int maxRows) {
      calls.add(parameters);
      limits.add(maxRows);
      if (failure != null) {
        throw failure;
      }
      return rows.subList(0, Math.min(rows.size(), maxRows));
    }
  }

  final FakeQueries queries = new FakeQueries();

  private PypiCollector collector() {
    return new PypiCollector(
        new SourceSettings(true, null, null, Duration.ofSeconds(30), Duration.ZERO),
        queries,
        SourceFixtures.CLOCK,
        Resilience.NONE,
        new RunInterval(Duration.ofDays(1)),
        20,
        AdmissionFixtures.AS_CONFIGURED);
  }

  private static Map<String, Object> row(
      String name, String summary, String homePage, List<String> projectUrls, String micros) {
    Map<String, Object> row = new java.util.LinkedHashMap<>();
    row.put("name", name);
    row.put("version", "1.2.0");
    if (summary != null) {
      row.put("summary", summary);
    }
    if (homePage != null) {
      row.put("home_page", homePage);
    }
    row.put("project_urls", projectUrls);
    row.put("keywords", "llm, training rag");
    row.put("license_expression", "Apache-2.0");
    row.put("upload_time", micros);
    return row;
  }

  static final String OCT_20 = "1760918400000000";
  static final String OCT_21 = "1761004800000000";
  static final String RECENT =
      Long.toString(Instant.parse("2026-10-21T00:00:00Z").getEpochSecond() * 1_000_000);

  @Test
  void aPackageWithAGitHubRepoAndASummaryIsIndexedAsAPythonLibrary() {
    queries.rows =
        List.of(
            row(
                "Torch_Tools",
                "Tools for training LLMs",
                null,
                List.of("Source, https://github.com/Acme/torch-tools.git"),
                OCT_20));

    CollectedBatch batch = collector().collect(null, null, 100);

    assertThat(batch.getHits()).hasSize(1);
    SourceHit hit = batch.getHits().getFirst();
    assertThat(hit.getSourceId()).isEqualTo("pypi");
    assertThat(hit.getTitle()).isEqualTo("Torch_Tools");
    assertThat(hit.getUrl().toString()).isEqualTo("https://pypi.org/project/torch-tools/");
    assertThat(hit.getRepositoryUrl().getValue()).isEqualTo("https://github.com/acme/torch-tools");
    assertThat(hit.getDescription()).isEqualTo("Tools for training LLMs");
    assertThat(hit.getType()).isEqualTo(ResourceType.LIBRARY);
    assertThat(hit.getAudience()).isEqualTo(Audience.BUILD_WITH_AI);
    assertThat(hit.getPlatforms()).containsExactly("python");
    assertThat(hit.getTags()).contains("llm", "training", "rag");
    assertThat(hit.getLicense()).isEqualTo("Apache-2.0");
    assertThat(hit.getUpdated().getValue()).isEqualTo(Instant.parse("2025-10-20T00:00:00Z"));
    assertThat(batch.getCandidates()).isEmpty();
  }

  @Test
  void theHomePageCanBeTheRepo() {
    queries.rows =
        List.of(row("rag-kit", "A RAG kit", "https://github.com/acme/rag-kit", List.of(), OCT_20));

    assertThat(collector().collect(null, null, 100).getHits()).hasSize(1);
  }

  @Test
  void aGitHubLinkDeeperThanTheRepoGivesTheRepo() {
    queries.rows =
        List.of(
            row(
                "tensorflow",
                "An open source machine learning framework",
                "https://www.tensorflow.org/",
                List.of("Download, https://github.com/tensorflow/tensorflow/tags"),
                OCT_20));

    CollectedBatch batch = collector().collect(null, null, 100);

    assertThat(batch.getHits())
        .extracting(hit -> hit.getRepositoryUrl().getValue())
        .containsExactly("https://github.com/tensorflow/tensorflow");
  }

  @Test
  void aGitHubSponsorsLinkIsNoRepo() {
    queries.rows =
        List.of(
            row(
                "sponsored",
                "An agent toolkit",
                null,
                List.of("Funding, https://github.com/sponsors/acme"),
                RECENT));

    CollectedBatch batch = collector().collect(null, null, 100);

    assertThat(batch.getHits()).isEmpty();
    assertThat(batch.getCandidates()).extracting(Candidate::getTitle).containsExactly("sponsored");
  }

  @Test
  void aPackageWithoutAGitHubRepoOrWithoutASummaryGoesToTheReviewList() {
    queries.rows =
        List.of(
            row("no-repo", "An agent toolkit", "https://acme.example", List.of(), RECENT),
            row("no-summary", null, null, List.of("Code, https://github.com/acme/x"), RECENT));

    CollectedBatch batch = collector().collect(null, null, 100);

    assertThat(batch.getHits()).isEmpty();
    assertThat(batch.getCandidates())
        .extracting(Candidate::getTitle)
        .containsExactly("no-repo", "no-summary");
    Candidate first = batch.getCandidates().getFirst();
    assertThat(first.getUrl().getValue()).isEqualTo("https://pypi.org/project/no-repo");
    assertThat(first.getType()).isEqualTo(ResourceType.LIBRARY);
    assertThat(first.getPlatform()).isEqualTo("PyPI");
    assertThat(batch.getNewCandidatesPerDay()).isEqualTo(20);
  }

  @Test
  void theFirstPassReadsEverythingAndALaterPassOnlyTheLastTwoDays() {
    collector().collect(null, null, 100);
    collector().collect("2026-10-20T03:00:00Z", null, 100);

    assertThat(queries.calls.get(0).getFirst().value()).isEqualTo("1970-01-01T00:00:00Z");
    assertThat(queries.calls.get(1).getFirst().value()).isEqualTo("2026-10-18T03:00:00Z");
  }

  @Test
  void aPassStoppedByTheBudgetResumesRightAfterTheLastPackageItRead() {
    queries.rows =
        List.of(
            row("a", "A", null, List.of("x, https://github.com/a/a"), OCT_20),
            row("b", "B", null, List.of("x, https://github.com/b/b"), OCT_21),
            row("c", "C", null, List.of("x, https://github.com/c/c"), OCT_21));

    CollectedBatch first = collector().collect(null, null, 2);

    assertThat(queries.limits.getFirst()).isEqualTo(3);
    assertThat(first.getHits()).extracting(SourceHit::getTitle).containsExactly("a", "b");
    assertThat(first.getNextCursor()).isEqualTo("2025-10-21T00:00:00Z|b");

    collector().collect(null, first.getNextCursor(), 2);
    assertThat(queries.calls.get(1))
        .extracting(BigQueryClient.Parameter::value)
        .startsWith("2025-10-21T00:00:00Z", "b");
  }

  @Test
  void packagesWithOneUploadTimeAreNotReadTwiceAcrossPasses() {
    assertThat(PypiCollector.SQL)
        .contains("upload_time >= @since")
        .contains("(upload_time > @since OR name > @after)")
        .contains("ORDER BY upload_time, name");
  }

  @Test
  void afterARunTheReviewCapHeldBackTheNextRunProposesTheHeldCandidates() {
    queries.rows =
        List.of(
            row(
                "sponsored",
                "An agent toolkit",
                null,
                List.of("Funding, https://github.com/sponsors/acme"),
                RECENT));

    List<CappedReview.Run> runs =
        CappedReview.aHeldBackRunThenTheNext(collector(), SourceFixtures.CLOCK);

    assertThat(runs).extracting(CappedReview.Run::getStatus).containsOnly(RunStatus.SUCCEEDED);
    assertThat(runs.get(0).getCursorAfter().getCandidatesHeldBack()).isTrue();
    assertThat(runs.get(0).getProposed()).isNotEmpty();
    assertThat(runs.get(1).getProposed()).containsExactlyElementsOf(runs.get(0).getProposed());
    assertThat(runs.get(1).getCursorAfter().getResumeCursor()).isNull();
  }

  @Test
  void afterTheReviewCapHeldCandidatesBackThePassWindowIsReadAgain() {
    collector().collect(ScoutCursor.candidatesHeldBack("2026-10-20T03:00:00Z", null), 100);
    collector().collect(ScoutCursor.candidatesHeldBack(null, null), 100);

    assertThat(queries.calls.get(0))
        .extracting(BigQueryClient.Parameter::value)
        .startsWith("2026-10-18T03:00:00Z", "");
    assertThat(queries.calls.get(1).getFirst().value()).isEqualTo("2026-09-22T10:00:00Z");
  }

  @Test
  void anOldPackageWithoutARepoIsLeftOutSoTheFirstFillDoesNotFloodTheReviewList() {
    String sep20 =
        Long.toString(Instant.parse("2026-09-20T00:00:00Z").getEpochSecond() * 1_000_000);
    String oct10 =
        Long.toString(Instant.parse("2026-10-10T00:00:00Z").getEpochSecond() * 1_000_000);
    queries.rows =
        List.of(
            row("old", "An old toolkit", "https://acme.example", List.of(), sep20),
            row("recent", "A recent toolkit", "https://acme.example", List.of(), oct10));

    CollectedBatch batch = collector().collect(null, null, 100);

    assertThat(batch.getCandidates()).extracting(Candidate::getTitle).containsExactly("recent");
  }

  @Test
  void aPassThatReadsEverythingEndsWithACheckpoint() {
    queries.rows = List.of(row("a", "A", null, List.of("x, https://github.com/a/a"), OCT_20));

    CollectedBatch batch = collector().collect(null, null, 100);

    assertThat(batch.getNextCursor()).isNull();
    assertThat(batch.getCheckpoint()).isEqualTo(NOW.toString());
  }

  @Test
  void aPassReadTodayIsNotReadAgainTheSameDay() {
    CollectedBatch batch = collector().collect(NOW.toString(), null, 100);

    assertThat(batch.getNotDue()).isTrue();
    assertThat(queries.calls).isEmpty();
  }

  @Test
  void aBigQueryFailureIsAFailedRun() {
    queries.failure = new SourceFailure.Unavailable("HTTP 503");

    assertThatThrownBy(() -> collector().collect(null, null, 100))
        .isInstanceOf(SourceFailure.class);
  }

  @Test
  void theQueryReadsNoPersonalColumnsAndOnlyAiPackages() {
    assertThat(PypiCollector.SQL)
        .doesNotContainIgnoringCase("author")
        .doesNotContainIgnoringCase("maintainer")
        .doesNotContainIgnoringCase("email")
        .contains("Topic :: Scientific/Engineering :: Artificial Intelligence")
        .contains("upload_time >= @since");
  }
}
