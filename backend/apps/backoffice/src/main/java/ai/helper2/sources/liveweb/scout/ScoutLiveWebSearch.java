package ai.helper2.sources.liveweb.scout;

import ai.helper2.platform.resilience.Resilience;
import ai.helper2.scout.CollectedBatch;
import ai.helper2.sources.support.HttpClients;
import java.time.Clock;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

public final class ScoutLiveWebSearch {

  private static final String SEARCH_PATH = "/search";

  private final RestClient http;
  private final Clock clock;
  private final Resilience resilience;
  private final ScoutLiveWebCredits credits;
  private final String policy;
  private final String searchDepth;

  public ScoutLiveWebSearch(
      RestClient http,
      Clock clock,
      Resilience resilience,
      ScoutLiveWebCredits credits,
      String policy,
      String searchDepth) {
    this.http = http;
    this.clock = clock;
    this.resilience = resilience;
    this.credits = credits;
    this.policy = policy;
    this.searchDepth = searchDepth;
  }

  public boolean capReached() {
    return credits.capReached();
  }

  public CollectedBatch deferred() {
    return CollectedBatch.deferred(capReachedMessage());
  }

  public JsonNode search(Map<String, Object> body) {
    return counted(() -> http.post().uri(SEARCH_PATH).body(body).retrieve().body(String.class));
  }

  JsonNode post(String path, Map<String, Object> body, Consumer<Map<String, Object>> attributes) {
    return counted(() ->
        http.post().uri(path).attributes(attributes).body(body).retrieve().body(String.class));
  }

  private JsonNode counted(Supplier<String> request) {
    if (credits.capReached()) {
      throw new CapReached(capReachedMessage());
    }
    return resilience.call(
        policy,
        () -> credits.countedAttempt(searchDepth, () -> HttpClients.getJson(request, clock)));
  }

  private String capReachedMessage() {
    return "Daily live web credit cap of the Scout reached ("
        + credits.dailyCap()
        + " credits); next try tomorrow";
  }

  public static final class CapReached extends RuntimeException {

    private CapReached(String message) {
      super(message, null, false, false);
    }
  }
}
