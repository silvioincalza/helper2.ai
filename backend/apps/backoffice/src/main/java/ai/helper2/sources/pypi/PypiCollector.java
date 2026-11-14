package ai.helper2.sources.pypi;

import ai.helper2.platform.resilience.Resilience;
import ai.helper2.scout.Candidate;
import ai.helper2.scout.CollectedBatch;
import ai.helper2.scout.Collector;
import ai.helper2.scout.ScoutCursor;
import ai.helper2.scout.admission.AdmissionPolicy;
import ai.helper2.search.SourceDescriptor;
import ai.helper2.search.SourceHit;
import ai.helper2.shared.Audience;
import ai.helper2.shared.CanonicalUrl;
import ai.helper2.shared.CollectorIds;
import ai.helper2.shared.DateFact;
import ai.helper2.shared.ResourceType;
import ai.helper2.shared.ValueOrigin;
import ai.helper2.sources.config.CollectorDescriptors;
import ai.helper2.sources.support.Classification;
import ai.helper2.sources.support.HttpClients;
import ai.helper2.sources.support.RunInterval;
import ai.helper2.sources.support.SourceSettings;
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
        repo,
        name,
        summary,
        ValueOrigin.SOURCE_PROVIDED,
        Classification.typeOf(name, summary, keywords, ResourceType.LIBRARY),
        Audience.BUILD_WITH_AI,
        null,
        Set.of(),
        keywords,
        license,
        null,
        Set.of("python"),
        Classification.openSource(license),
        DateFact.unknown(),
        DateFact.fromSource(uploadTime(row), ID, now),
        List.of(),
        now,
        rank);
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
