package ai.helper2.sources.huggingface.scout;

import ai.helper2.platform.http.ObservedHttp;
import ai.helper2.platform.resilience.Resilience;
import ai.helper2.search.SourceHit;
import ai.helper2.sources.huggingface.HuggingFaceModelsSource;
import ai.helper2.sources.support.HttpClients;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

public final class HuggingFaceModelReader {

  static final int MAX_BYTES = 1024 * 1024;

  private static final Pattern ID =
      Pattern.compile("^([A-Za-z0-9][A-Za-z0-9._-]{0,95})/([A-Za-z0-9][A-Za-z0-9._-]{0,95})$");

  private static final Set<Integer> NOT_A_PUBLIC_MODEL = Set.of(401, 404);

  private final RestClient http;
  private final Resilience resilience;
  private final String policy;
  private final Clock clock;

  public HuggingFaceModelReader(
      RestClient http, Resilience resilience, String policy, Clock clock) {
    this.http = http;
    this.resilience = resilience;
    this.policy = policy;
    this.clock = clock;
  }

  public Optional<SourceHit> read(String id, String sourceId, Instant now) {
    Matcher name = ID.matcher(id);
    if (!name.matches()) {
      return Optional.empty();
    }
    return model(name.group(1), name.group(2))
        .map(m -> HuggingFaceModelsSource.toHit(m, sourceId, now, 0))
        .map(hit -> hit.withExternalId(hit.getCanonicalUrl().getValue()));
  }

  private Optional<JsonNode> model(String owner, String name) {
    return resilience.call(
        policy,
        () -> {
          ResponseEntity<String> answer =
              HttpClients.get(
                  () ->
                      http.get()
                          .uri(u -> {
                            u.path("/api/models/{owner}/{name}");
                            HuggingFaceModelsSource.EXPANDED_FIELDS.forEach(
                                f -> u.queryParam("expand[]", f));
                            return u.build(owner, name);
                          })
                          .attributes(ObservedHttp.maxResponseBytes(MAX_BYTES))
                          .retrieve()
                          .onStatus(
                              s -> NOT_A_PUBLIC_MODEL.contains(s.value()), (request, reply) -> {})
                          .toEntity(String.class),
                  clock);
          if (NOT_A_PUBLIC_MODEL.contains(answer.getStatusCode().value())) {
            return Optional.empty();
          }
          return Optional.of(HttpClients.getJson(answer::getBody, clock));
        });
  }
}
