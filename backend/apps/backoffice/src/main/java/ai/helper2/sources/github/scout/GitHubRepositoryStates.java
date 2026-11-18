package ai.helper2.sources.github.scout;

import ai.helper2.catalog.enrichment.RepositoryVisits.Found;
import ai.helper2.catalog.enrichment.RepositoryVisits.Gone;
import ai.helper2.catalog.enrichment.RepositoryVisits.Parent;
import ai.helper2.catalog.enrichment.RepositoryVisits.Visit;
import ai.helper2.platform.resilience.Resilience;
import ai.helper2.scout.usecase.RepositoryStates;
import ai.helper2.search.SourceFailure;
import ai.helper2.sources.support.HttpClients;
import java.time.Clock;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

public final class GitHubRepositoryStates implements RepositoryStates {

  public static final String POLICY = "collector-repository-sweep";

  private static final Pattern OWNER_AND_NAME =
      Pattern.compile("[A-Za-z0-9-]{1,39}/(?!\\.{1,2}$)[A-Za-z0-9_.-]{1,100}");
  private static final int SMALLEST_JUDGED_BATCH = 10;
  private static final int MOST_NOT_FOUND_IN_FIVE = 1;
  private static final Pattern ALIAS = Pattern.compile("r\\d{1,4}");
  private static final String FACTS =
      "{ isArchived parent { nameWithOwner isArchived } latestRelease { publishedAt } }";

  private final RestClient http;
  private final Resilience resilience;
  private final Clock clock;

  public GitHubRepositoryStates(RestClient http, Resilience resilience, Clock clock) {
    this.http = http;
    this.resilience = resilience;
    this.clock = clock;
  }

  @Override
  public Map<String, Visit> of(Collection<String> repositories) {
    List<String> asked =
        repositories.stream().filter(r -> OWNER_AND_NAME.matcher(r).matches()).distinct().toList();
    if (asked.isEmpty()) {
      return Map.of();
    }
    JsonNode answer = ask(asked);
    Set<String> notFound = believed(notFoundAliases(answer), asked.size());
    JsonNode data = answer.path("data");
    if (!data.isObject()) {
      throw new SourceFailure.Unavailable("GraphQL answer without data");
    }
    Map<String, Visit> visits = new HashMap<>();
    for (int i = 0; i < asked.size(); i++) {
      Visit visit = visitOf(data.path("r" + i), notFound.contains("r" + i));
      if (visit != null) {
        visits.put(asked.get(i), visit);
      }
    }
    return visits;
  }

  private static Set<String> believed(Set<String> notFound, int asked) {
    boolean tooMany =
        asked >= SMALLEST_JUDGED_BATCH && notFound.size() * 5 > asked * MOST_NOT_FOUND_IN_FIVE;
    return tooMany ? Set.of() : notFound;
  }

  private JsonNode ask(List<String> asked) {
    ObjectNode body = JsonNodeFactory.instance.objectNode().put("query", query(asked));
    return resilience.call(
        POLICY,
        () ->
            HttpClients.getJson(
                () ->
                    http.post()
                        .uri("/graphql")
                        .header("Content-Type", "application/json")
                        .body(body.toString())
                        .retrieve()
                        .body(String.class),
                clock));
  }

  static String query(List<String> asked) {
    StringBuilder q = new StringBuilder("query {");
    for (int i = 0; i < asked.size(); i++) {
      String[] parts = asked.get(i).split("/", 2);
      q.append(" r")
          .append(i)
          .append(": repository(owner: \"")
          .append(parts[0])
          .append("\", name: \"")
          .append(parts[1])
          .append("\") ")
          .append(FACTS);
    }
    return q.append(" }").toString();
  }

  private static Set<String> notFoundAliases(JsonNode answer) {
    Set<String> notFound = new HashSet<>();
    for (JsonNode error : answer.path("errors")) {
      String type = error.path("type").asString("");
      if ("RATE_LIMITED".equals(type)) {
        throw new SourceFailure.RateLimited(null);
      }
      JsonNode path = error.path("path");
      String alias = path.path(0).asString("");
      if ("NOT_FOUND".equals(type) && path.size() == 1 && ALIAS.matcher(alias).matches()) {
        notFound.add(alias);
      }
    }
    return notFound;
  }

  private static @Nullable Visit visitOf(JsonNode repository, boolean notFound) {
    if (repository.isObject() && repository.path("isArchived").isBoolean()) {
      return new Found(
          repository.path("isArchived").asBoolean(),
          parentOf(repository.path("parent")),
          HttpClients.instant(repository.path("latestRelease"), "publishedAt"));
    }
    return notFound && repository.isNull() ? new Gone() : null;
  }

  private static @Nullable Parent parentOf(JsonNode parent) {
    String repository = parent.isObject() ? HttpClients.text(parent, "nameWithOwner") : null;
    if (repository == null || !parent.path("isArchived").isBoolean()) {
      return null;
    }
    return new Parent(repository, parent.path("isArchived").asBoolean());
  }
}
