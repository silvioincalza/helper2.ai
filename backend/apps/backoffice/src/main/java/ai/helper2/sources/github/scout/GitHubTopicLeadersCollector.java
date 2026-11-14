package ai.helper2.sources.github.scout;

import ai.helper2.platform.resilience.Resilience;
import ai.helper2.scout.Candidate;
import ai.helper2.scout.CandidateSignals;
import ai.helper2.scout.CollectedBatch;
import ai.helper2.scout.Collector;
import ai.helper2.scout.ScoutCursor;
import ai.helper2.search.SourceDescriptor;
import ai.helper2.search.SourceHit;
import ai.helper2.shared.CanonicalUrl;
import ai.helper2.sources.config.CollectorDescriptors;
import ai.helper2.sources.github.GitHubRepositories;
import ai.helper2.sources.github.scout.GitHubSdkCollector.Pause;
import ai.helper2.sources.support.Classification;
import ai.helper2.sources.support.HttpClients;
import ai.helper2.sources.support.RunInterval;
import ai.helper2.sources.support.SourceSettings;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

public class GitHubTopicLeadersCollector implements Collector {

  static final String POLICY = "collector-github-topic-leaders";

  private static final int PAGE_SIZE = 100;

  public record Indexing(int minStars, Duration pushedWithin, int perTopic, Duration searchPause) {

    public static final Indexing OFF = new Indexing(0, Duration.ZERO, 0, Duration.ZERO);

    boolean on() {
      return perTopic > 0;
    }
  }

  public record Limits(
      List<String> topics, int minStars, int perTopic, int newPerDay, Indexing indexing) {}

  record Place(int topic, int page, int taken) {

    static final Place START = new Place(0, 1, 0);

    static Place of(@Nullable String resumeCursor) {
      if (resumeCursor == null) {
        return START;
      }
      String[] parts = resumeCursor.split(":", -1);
      if (parts.length < 2 || parts.length > 3) {
        return START;
      }
      try {
        return new Place(
            Math.max(0, Integer.parseInt(parts[0])),
            Math.max(1, Integer.parseInt(parts[1])),
            parts.length == 3 ? Math.clamp(Integer.parseInt(parts[2]), 0, PAGE_SIZE) : 0);
      } catch (NumberFormatException e) {
        return START;
      }
    }

    String cursor() {
      return taken == 0 ? topic + ":" + page : topic + ":" + page + ":" + taken;
    }

    int rankOf(int positionInPage) {
      return (page - 1) * PAGE_SIZE + positionInPage;
    }

    Place after(int positionInPage) {
      return new Place(topic, page, positionInPage);
    }

    Place nextPage() {
      return new Place(topic, page + 1, 0);
    }

    Place nextTopic() {
      return new Place(topic + 1, 1, 0);
    }
  }

  private final RestClient http;
  private final Clock clock;
  private final Resilience resilience;
  private final RunInterval interval;
  private final Limits limits;
  private final Pause pause;
  private final SourceDescriptor descriptor;

  public GitHubTopicLeadersCollector(
      SourceSettings settings,
      RestClient.Builder builder,
      Clock clock,
      Resilience resilience,
      RunInterval interval,
      Limits limits,
      Pause pause) {
    this.clock = clock;
    this.resilience = resilience;
    if (settings.enabled()) {
      resilience.require(POLICY);
    }
    this.interval = interval;
    this.limits = limits;
    this.pause = pause;
    this.http = HttpClients.gitHubClient(builder, settings);
    this.descriptor = CollectorDescriptors.gitHubTopicLeaders(settings, limits.topics());
  }

  @Override
  public SourceDescriptor getDescriptor() {
    return descriptor;
  }

  @Override
  public CollectedBatch collect(
      @Nullable String checkpoint, @Nullable String resumeCursor, int maxItems) {
    return collect(new ScoutCursor(checkpoint, resumeCursor, null), maxItems);
  }

  @Override
  public CollectedBatch collect(ScoutCursor cursor, int maxItems) {
    Instant startedAt = clock.instant();
    if (!interval.allowsPass(cursor.getCheckpoint(), cursor.getResumeCursor(), startedAt)) {
      return CollectedBatch.notDue();
    }
    Map<CanonicalUrl, SourceHit> indexed = new LinkedHashMap<>();
    Place next = null;
    if (limits.indexing().on() && !cursor.getCandidatesHeldBack()) {
      next = index(Place.of(cursor.getOwnResume()), startedAt, maxItems, indexed);
    }
    List<Candidate> candidates = next == null ? forReview() : List.of();
    return new CollectedBatch(
        List.copyOf(indexed.values()),
        next == null ? null : next.cursor(),
        next == null ? startedAt.toString() : null,
        false,
        candidates,
        null,
        List.of(),
        false,
        limits.newPerDay());
  }

  private @Nullable Place index(
      Place from, Instant now, int maxItems, Map<CanonicalUrl, SourceHit> indexed) {
    Indexing rule = limits.indexing();
    String since = LocalDate.ofInstant(now.minus(rule.pushedWithin()), ZoneOffset.UTC).toString();
    Place place = from;
    while (place.topic() < limits.topics().size()) {
      if (place.rankOf(place.taken()) >= rule.perTopic()) {
        place = place.nextTopic();
        continue;
      }
      if (indexed.size() >= maxItems) {
        return place;
      }
      String topic = limits.topics().get(place.topic());
      String q =
          "topic:%s stars:>=%d pushed:>=%s fork:false archived:false"
              .formatted(topic, rule.minStars(), since);
      JsonNode repositories = search(q, PAGE_SIZE, place.page()).path("items");
      int position = place.taken();
      while (position < repositories.size()
          && place.rankOf(position) < rule.perTopic()
          && indexed.size() < maxItems) {
        JsonNode repository = repositories.get(position);
        SourceHit hit =
            isAboutAiBeyond(topic, repository)
                ? GitHubRepositories.hit(descriptor.getId(), repository, now, indexed.size())
                : null;
        if (hit != null) {
          indexed.putIfAbsent(hit.getCanonicalUrl(), hit);
        }
        position++;
      }
      if (position < repositories.size()) {
        place = place.after(position);
      } else {
        place = repositories.size() < PAGE_SIZE ? place.nextTopic() : place.nextPage();
      }
    }
    return null;
  }

  private boolean isAboutAiBeyond(String topic, JsonNode repository) {
    Set<String> otherTopics = GitHubRepositories.topics(repository);
    otherTopics.remove(topic);
    boolean aboutAi =
        Classification.isAiRelated(
            HttpClients.text(repository, "full_name"),
            HttpClients.text(repository, "description"),
            otherTopics);
    if (!aboutAi) {
      HttpClients.dropped(descriptor.getId(), "not-ai");
    }
    return aboutAi;
  }

  private List<Candidate> forReview() {
    Map<CanonicalUrl, Leader> leaders = new LinkedHashMap<>();
    for (String topic : limits.topics()) {
      String q = "topic:" + topic + " stars:>=" + limits.minStars() + " fork:false archived:false";
      for (JsonNode repo : search(q, Math.min(limits.perTopic(), PAGE_SIZE), 1).path("items")) {
        Leader found = Leader.of(repo);
        if (found != null) {
          leaders.computeIfAbsent(found.url(), u -> found).topics().add(topic);
        }
      }
    }
    return leaders.values().stream().map(Leader::toCandidate).toList();
  }

  private JsonNode search(String q, int perPage, int page) {
    pause.sleep(limits.indexing().searchPause());
    return resilience.call(
        POLICY,
        () ->
            HttpClients.getJson(
                () ->
                    http.get()
                        .uri(u ->
                            u.path("/search/repositories")
                                .queryParam("q", q)
                                .queryParam("sort", "stars")
                                .queryParam("order", "desc")
                                .queryParam("per_page", perPage)
                                .queryParam("page", page)
                                .build())
                        .retrieve()
                        .body(String.class),
                clock));
  }

  private record Leader(
      CanonicalUrl url,
      String name,
      String description,
      CandidateSignals signals,
      Set<String> labels,
      Set<String> topics) {

    static Leader of(JsonNode repo) {
      String name = HttpClients.text(repo, "full_name");
      CanonicalUrl url = CanonicalUrl.of(HttpClients.text(repo, "html_url"));
      if (name == null || url == null) {
        return null;
      }
      return new Leader(
          url,
          name,
          HttpClients.text(repo, "description"),
          RepositorySignals.of(repo),
          GitHubRepositories.topics(repo),
          new LinkedHashSet<>());
    }

    Candidate toCandidate() {
      List<String> tags =
          new ArrayList<>(topics).subList(0, Math.min(topics.size(), Candidate.MAX_TAGS));
      Map<String, Object> facts = new LinkedHashMap<>();
      facts.put("topics", List.copyOf(topics));
      return new Candidate(
          url,
          name,
          description,
          null,
          Classification.typeOf(name, description, labels, ai.helper2.shared.ResourceType.LIBRARY),
          new LinkedHashSet<>(tags),
          facts,
          signals);
    }
  }
}
