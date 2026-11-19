package ai.helper2.sources.github.scout;

import ai.helper2.platform.http.ObservedHttp;
import ai.helper2.platform.resilience.Resilience;
import ai.helper2.sources.support.HttpClients;
import java.time.Clock;
import java.time.Duration;
import org.springframework.web.client.RestClient;

final class GitHubReadmeReader implements ListReader {

  static final int MAX_BYTES = 5 * 1024 * 1024;

  static final Duration DEADLINE = Duration.ofSeconds(60);

  private final RestClient http;
  private final Resilience resilience;
  private final Clock clock;

  GitHubReadmeReader(RestClient http, Resilience resilience, Clock clock) {
    this.http = http;
    this.resilience = resilience;
    this.clock = clock;
  }

  @Override
  public String markdown(String repository) {
    String[] parts = repository.split("/", 2);
    return resilience.call(
        GitHubAwesomeCollector.POLICY,
        () ->
            HttpClients.getText(
                () ->
                    http.get()
                        .uri("/repos/{owner}/{repo}/readme", parts[0], parts[1])
                        .header("Accept", "application/vnd.github.raw+json")
                        .attributes(ObservedHttp.maxResponseBytes(MAX_BYTES))
                        .attributes(ObservedHttp.deadline(DEADLINE))
                        .retrieve()
                        .body(String.class),
                clock));
  }
}
