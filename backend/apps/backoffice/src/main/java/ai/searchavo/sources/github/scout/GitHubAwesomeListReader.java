package ai.searchavo.sources.github.scout;

import ai.searchavo.platform.http.ObservedHttp;
import ai.searchavo.platform.resilience.CallRefused;
import ai.searchavo.platform.resilience.Resilience;
import ai.searchavo.scout.AwesomeListReader;
import ai.searchavo.search.SourceFailure;
import ai.searchavo.sources.support.HttpClients;
import java.time.Clock;
import java.util.Collection;
import java.util.Optional;
import java.util.regex.Pattern;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

public final class GitHubAwesomeListReader implements AwesomeListReader {

  private static final Pattern REPO = Pattern.compile("^[A-Za-z0-9-]{1,39}/[A-Za-z0-9._-]{1,100}$");

  private final RestClient http;
  private final Resilience resilience;
  private final Clock clock;

  public GitHubAwesomeListReader(RestClient http, Resilience resilience, Clock clock) {
    this.http = http;
    this.resilience = resilience;
    this.clock = clock;
  }

  @Override
  public Optional<Boolean> isAwesomeList(String repoFullName, Collection<String> topics) {
    if (repoFullName == null || !REPO.matcher(repoFullName).matches()) {
      return Optional.of(false);
    }
    if (!AwesomeLists.named(repoFullName, topics)) {
      return Optional.of(false);
    }
    try {
      return Optional.of(AwesomeLists.isAwesomeList(repoFullName, topics, readme(repoFullName)));
    } catch (SourceFailure | CallRefused e) {
      return Optional.empty();
    }
  }

  private String readme(String fullName) {
    String[] parts = fullName.split("/", 2);
    return resilience.call(
        GitHubAwesomeCollector.POLICY,
        () ->
            HttpClients.getText(
                () -> {
                  try {
                    return http.get()
                        .uri("/repos/{owner}/{repo}/readme", parts[0], parts[1])
                        .header("Accept", "application/vnd.github.raw+json")
                        .attributes(
                            ObservedHttp.maxResponseBytes(GitHubAwesomeCollector.README_MAX_BYTES))
                        .attributes(ObservedHttp.deadline(GitHubAwesomeCollector.README_DEADLINE))
                        .retrieve()
                        .body(String.class);
                  } catch (HttpClientErrorException.NotFound e) {
                    return null;
                  }
                },
                clock));
  }
}
