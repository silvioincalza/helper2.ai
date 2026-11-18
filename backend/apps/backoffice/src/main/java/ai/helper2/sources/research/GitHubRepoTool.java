package ai.helper2.sources.research;

import ai.helper2.platform.resilience.Resilience;
import ai.helper2.research.ResearchTool;
import ai.helper2.sources.support.HttpClients;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

public final class GitHubRepoTool implements ResearchTool {

  static final String POLICY = "source-github";
  private static final Pattern REPO = Pattern.compile("^[A-Za-z0-9-]{1,39}/[A-Za-z0-9._-]{1,100}$");

  private final RestClient http;
  private final Resilience resilience;
  private final Clock clock;

  public GitHubRepoTool(RestClient http, Resilience resilience, Clock clock) {
    this.http = http;
    this.resilience = resilience;
    this.clock = clock;
  }

  @Override
  public String name() {
    return "github_repo";
  }

  @Override
  public String description() {
    return "Read a GitHub repository's record: description, homepage, licence (SPDX), topics, stars, last push,"
        + " archived.";
  }

  @Override
  public String parameters() {
    return """
    {"type":"object","properties":{"repo":{"type":"string","description":"owner/name"}},"required":["repo"]}\
    """;
  }

  @Override
  public Output call(String arguments) {
    String repo = Args.of(arguments).text("repo");
    if (repo == null || !REPO.matcher(repo).matches()) {
      return Output.error("repo must be owner/name");
    }
    JsonNode r;
    try {
      r =
          resilience.call(
              POLICY,
              () ->
                  HttpClients.getJson(
                      () -> http.get().uri("/repos/{repo}", repo).retrieve().body(String.class),
                      clock));
    } catch (HttpClientErrorException.NotFound e) {
      return new Output("not found: " + repo, null);
    }
    List<String> topics = new ArrayList<>();
    r.path("topics").forEach(t -> topics.add(t.asString()));
    String fullName = r.path("full_name").asString(repo);
    return new Output(
        "repository: "
            + fullName
            + "\ndescription: "
            + r.path("description").asString("")
            + "\nhomepage: "
            + r.path("homepage").asString("")
            + "\nlicense: "
            + r.path("license").path("spdx_id").asString("none stated")
            + "\ntopics: "
            + String.join(", ", topics)
            + "\nstars: "
            + r.path("stargazers_count").asLong(0)
            + "\nlast push: "
            + r.path("pushed_at").asString("")
            + "\narchived: "
            + r.path("archived").asBoolean(false),
        "https://github.com/" + fullName);
  }
}
