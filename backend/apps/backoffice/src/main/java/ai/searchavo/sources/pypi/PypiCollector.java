package ai.searchavo.sources.pypi;

import ai.searchavo.platform.resilience.Resilience;
import ai.searchavo.scout.Candidate;
import ai.searchavo.scout.CollectedBatch;
import ai.searchavo.scout.Collector;
import ai.searchavo.scout.ScoutCursor;
import ai.searchavo.scout.admission.AdmissionPolicy;
import ai.searchavo.search.SourceDescriptor;
import ai.searchavo.search.SourceHit;
import ai.searchavo.shared.Audience;
import ai.searchavo.shared.CanonicalUrl;
import ai.searchavo.shared.CollectorIds;
import ai.searchavo.shared.DateFact;
import ai.searchavo.shared.ResourceType;
import ai.searchavo.sources.config.CollectorDescriptors;
import ai.searchavo.sources.support.Classification;
import ai.searchavo.sources.support.HttpClients;
import ai.searchavo.sources.support.RunInterval;
import ai.searchavo.sources.support.SourceSettings;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;

public class PypiCollector implements Collector {

  public static final String ID = CollectorIds.PYPI;

  static final String POLICY = "collector-pypi";

  static final String SQL =
      """
      SELECT name, version, summary, home_page, project_urls, keywords, license_expression,
             upload_time
      FROM `bigquery-public-data.pypi.distribution_metadata`
      WHERE upload_time >= @since
        AND (upload_time > @since OR name > @after)
        AND EXISTS (
          SELECT 1 FROM UNNEST(classifiers) AS c
          WHERE STARTS_WITH(c, 'Topic :: Scientific/Engineering :: Artificial Intelligence'))
      QUALIFY ROW_NUMBER() OVER (PARTITION BY name ORDER BY upload_time DESC) = 1
      ORDER BY upload_time, name
      LIMIT @limit
      """;

  private static final Duration OVERLAP = Duration.ofDays(2);
  private static final Duration CANDIDATE_WINDOW = Duration.ofDays(30);
  private static final int MAX_PACKAGES = 5_000;
  private static final int MAX_TAGS = Candidate.MAX_TAGS;
  private static final String PROJECT_PAGE = "https://pypi.org/project/";

  private record Position(Instant since, String after) {}

  public interface Queries {
    List<Map<String, Object>> query(
        String sql, List<BigQueryClient.Parameter> parameters, int maxRows);
  }

  private final Queries queries;
  private final Clock clock;
  private final Resilience resilience;
  private final RunInterval interval;
  private final int candidatesPerDay;
  private final AdmissionPolicy admission;
  private final SourceDescriptor descriptor;

  PypiCollector(
      SourceSettings settings,
      Queries queries,
      Clock clock,
      Resilience resilience,
      RunInterval interval,
      int candidatesPerDay,
      AdmissionPolicy admission) {
    this.queries = queries;
    this.clock = clock;
    this.resilience = resilience;
    this.interval = interval;
    this.candidatesPerDay = candidatesPerDay;
    this.admission = admission;
    this.descriptor = CollectorDescriptors.pypi(settings);
    if (descriptor.getEnabled()) {
      resilience.require(POLICY);
    }
  }

  @Override
  public SourceDescriptor getDescriptor() {
    return descriptor;
  }

  @Override
  public Duration runEvery() {
    return interval.every();
  }

  @Override
  public CollectedBatch collect(
      @Nullable String checkpoint, @Nullable String resumeCursor, int runBudget) {
    return collect(new ScoutCursor(checkpoint, resumeCursor, null), runBudget);
  }

  @Override
  public CollectedBatch collect(ScoutCursor cursor, int runBudget) {
    Instant startedAt = clock.instant();
    if (!interval.allowsPass(cursor.getCheckpoint(), cursor.getResumeCursor(), startedAt)) {
      return CollectedBatch.notDue();
    }
    int budget = Math.max(1, Math.min(runBudget, MAX_PACKAGES));
    List<Map<String, Object>> rows = read(position(cursor, startedAt), budget + 1);
    boolean more = rows.size() > budget;
    List<Map<String, Object>> kept = more ? rows.subList(0, budget) : rows;
    List<SourceHit> hits = new ArrayList<>();
    List<Candidate> candidates = new ArrayList<>();
    for (Map<String, Object> row : kept) {
      sort(row, startedAt, hits, candidates);
    }
    String nextCursor = more ? cursorAfter(kept.getLast()) : null;
    return new CollectedBatch(
        List.copyOf(hits),
        nextCursor,
        startedAt.toString(),
        false,
        List.copyOf(candidates),
        null,
        List.of(),
        false,
        candidatesPerDay);
  }

  private List<Map<String, Object>> read(Position from, int limit) {
    List<BigQueryClient.Parameter> parameters =
        List.of(
            new BigQueryClient.Parameter("since", "TIMESTAMP", from.since().toString()),
            new BigQueryClient.Parameter("after", "STRING", from.after()),
            new BigQueryClient.Parameter("limit", "INT64", Integer.toString(limit)));
    return resilience.call(POLICY, () -> queries.query(SQL, parameters, limit));
  }

  private static Position position(ScoutCursor cursor, Instant now) {
    String resume = cursor.getOwnResume();
    if (resume != null) {
      int bar = resume.indexOf('|');
      return bar < 0
          ? new Position(Instant.parse(resume), "")
          : new Position(Instant.parse(resume.substring(0, bar)), resume.substring(bar + 1));
    }
    if (cursor.getCheckpoint() != null) {
      return new Position(Instant.parse(cursor.getCheckpoint()).minus(OVERLAP), "");
    }
    return new Position(
        cursor.getCandidatesHeldBack() ? now.minus(CANDIDATE_WINDOW) : Instant.EPOCH, "");
  }

  private static String cursorAfter(Map<String, Object> row) {
    return uploadTime(row) + "|" + text(row, "name");
  }

  private void sort(
      Map<String, Object> row, Instant now, List<SourceHit> hits, List<Candidate> candidates) {
    String name = text(row, "name");
    if (name == null) {
      HttpClients.dropped(ID, "no-name");
      return;
    }
    String summary = text(row, "summary");
    CanonicalUrl repo = gitHubRepo(row);
    CanonicalUrl page = CanonicalUrl.of(projectPage(name));
    if (page == null) {
      HttpClients.dropped(ID, "bad-name");
      return;
    }
    SourceHit hit = hit(row, name, summary, repo, page, now, hits.size());
    if (admission.indexes(hit, ID)) {
      hits.add(hit);
    } else if (uploadTime(row).isBefore(now.minus(CANDIDATE_WINDOW))) {
      HttpClients.dropped(ID, "old-without-repo");
    } else {
      candidates.add(candidate(row, name, summary, page));
    }
  }

  private SourceHit hit(
      Map<String, Object> row,
      String name,
      @Nullable String summary,
      @Nullable CanonicalUrl repo,
      CanonicalUrl page,
      Instant now,
      int rank) {
    Set<String> keywords = keywords(row);
    String license = text(row, "license_expression");
    return new SourceHit(
            ID,
            name,
            URI.create(projectPage(name)),
            page,
            name,
            Classification.typeOf(name, summary, keywords, ResourceType.LIBRARY),
            now)
        .withRepository(repo)
        .withDescription(summary)
        .withAudience(Audience.BUILD_WITH_AI)
        .withTags(keywords)
        .withLicense(license, Classification.openSource(license))
        .withPlatforms(Set.of("python"))
        .withUpdated(DateFact.fromSource(uploadTime(row), ID, now))
        .withRank(rank);
  }

  private static Candidate candidate(
      Map<String, Object> row, String name, @Nullable String summary, CanonicalUrl page) {
    Set<String> keywords = keywords(row);
    java.util.Map<String, Object> facts = new java.util.LinkedHashMap<>();
    Optional.ofNullable(text(row, "version")).ifPresent(v -> facts.put("version", v));
    Optional.ofNullable(text(row, "home_page")).ifPresent(v -> facts.put("homePage", v));
    return new Candidate(
        page, name, summary, "PyPI", ResourceType.LIBRARY, keywords, Map.copyOf(facts));
  }

  static String projectPage(String name) {
    return PROJECT_PAGE + name.toLowerCase(Locale.ROOT).replaceAll("[-_.]+", "-") + "/";
  }

  private static @Nullable CanonicalUrl gitHubRepo(Map<String, Object> row) {
    Stream<String> projectUrls =
        row.get("project_urls") instanceof List<?> list
            ? list.stream().map(String::valueOf).map(PypiCollector::urlOf)
            : Stream.empty();
    return Stream.concat(projectUrls, Stream.ofNullable(text(row, "home_page")))
        .map(CanonicalUrl::of)
        .filter(url -> url != null && url.isGitHubRepository())
        .findFirst()
        .orElse(null);
  }

  private static String urlOf(String projectUrl) {
    int comma = projectUrl.indexOf(',');
    return (comma < 0 ? projectUrl : projectUrl.substring(comma + 1)).strip();
  }

  private static Set<String> keywords(Map<String, Object> row) {
    String raw = text(row, "keywords");
    if (raw == null) {
      return Set.of();
    }
    Set<String> words = new LinkedHashSet<>();
    Arrays.stream(raw.split("[,;\\s]+"))
        .map(w -> w.strip().toLowerCase(Locale.ROOT))
        .filter(w -> !w.isEmpty() && w.length() <= 40)
        .limit(MAX_TAGS)
        .forEach(words::add);
    return Set.copyOf(words);
  }

  private static Instant uploadTime(Map<String, Object> row) {
    String micros = text(row, "upload_time");
    return micros == null
        ? Instant.EPOCH
        : Instant.EPOCH.plus(Long.parseLong(micros), ChronoUnit.MICROS);
  }

  private static @Nullable String text(Map<String, Object> row, String column) {
    return row.get(column) instanceof String s && !s.isBlank() ? s.strip() : null;
  }
}
