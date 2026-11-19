package ai.helper2.sources.npm.scout;

import ai.helper2.platform.http.ObservedHttp;
import ai.helper2.platform.resilience.Resilience;
import ai.helper2.search.SourceHit;
import ai.helper2.shared.CanonicalUrl;
import ai.helper2.sources.npm.NpmSource;
import ai.helper2.sources.support.HttpClients;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

public final class NpmPackageReader {

  public record Answer(boolean found, @Nullable SourceHit aiPackage) {

    static final Answer NOT_FOUND = new Answer(false, null);
  }

  static final int MAX_BYTES = 512 * 1024;

  private static final int NOT_FOUND = 404;

  private static final String PART = "[a-z0-9][a-z0-9._-]{0,99}";

  private static final Pattern NAME = Pattern.compile("(?:@(" + PART + ")/)?(" + PART + ")");

  private static final Pattern PACKAGE_PAGE =
      Pattern.compile(
          "https://npmjs\\.com/package/((?:@"
              + PART
              + "/)?"
              + PART
              + ")(?:/v/[A-Za-z0-9.+-]{1,60})?");

  private final RestClient http;
  private final Resilience resilience;
  private final String policy;
  private final Clock clock;

  public NpmPackageReader(RestClient http, Resilience resilience, String policy, Clock clock) {
    this.http = http;
    this.resilience = resilience;
    this.policy = policy;
    this.clock = clock;
  }

  public static @Nullable String nameOf(CanonicalUrl page) {
    Matcher m = PACKAGE_PAGE.matcher(page.getValue());
    return m.matches() ? m.group(1) : null;
  }

  public Answer read(String name, String sourceId, Instant now) {
    Matcher parts = NAME.matcher(name);
    if (!parts.matches()) {
      return Answer.NOT_FOUND;
    }
    return latestVersion(parts.group(1), parts.group(2))
        .map(version -> new Answer(true, NpmSource.ofLatestVersion(version, sourceId, now)))
        .orElse(Answer.NOT_FOUND);
  }

  private Optional<JsonNode> latestVersion(@Nullable String scope, String name) {
    return resilience.call(
        policy,
        () -> {
          ResponseEntity<String> answer =
              HttpClients.get(
                  () ->
                      (scope == null
                              ? http.get().uri("/{name}/latest", name)
                              : http.get().uri("/@{scope}/{name}/latest", scope, name))
                          .attributes(ObservedHttp.maxResponseBytes(MAX_BYTES))
                          .retrieve()
                          .onStatus(s -> s.value() == NOT_FOUND, (request, reply) -> {})
                          .toEntity(String.class),
                  clock);
          if (answer.getStatusCode().value() == NOT_FOUND) {
            return Optional.empty();
          }
          return Optional.of(HttpClients.getJson(answer::getBody, clock));
        });
  }
}
