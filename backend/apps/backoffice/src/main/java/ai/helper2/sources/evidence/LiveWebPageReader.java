package ai.helper2.sources.evidence;

import ai.helper2.platform.http.ObservedHttp;
import ai.helper2.platform.resilience.Resilience;
import ai.helper2.scout.PageReader;
import ai.helper2.scout.ReadPage;
import ai.helper2.sources.support.HttpClients;
import ai.helper2.sources.support.SourceSettings;
import java.net.URI;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

public class LiveWebPageReader implements PageReader {

  static final String POLICY = "collector-gap-web";

  static final int MAX_URLS = 5;

  static final int MAX_BYTES = 2 * 1024 * 1024;

  private final RestClient http;
  private final Resilience resilience;
  private final Clock clock;
  private final boolean available;

  public LiveWebPageReader(
      SourceSettings settings, RestClient.Builder builder, Resilience resilience, Clock clock) {
    this.available = settings.enabled() && settings.hasToken();
    if (available) {
      resilience.require(POLICY);
    }
    this.http = HttpClients.client(builder, settings);
    this.resilience = resilience;
    this.clock = clock;
  }

  @Override
  public boolean getAvailable() {
    return available;
  }

  @Override
  public List<ReadPage> extract(List<URI> urls) {
    if (!available || urls.isEmpty()) {
      return List.of();
    }
    if (urls.size() > MAX_URLS) {
      throw new IllegalArgumentException("at most " + MAX_URLS + " URLs per call");
    }
    JsonNode root =
        resilience.call(
            POLICY,
            () ->
                HttpClients.getJson(
                    () ->
                        http.post()
                            .uri("/extract")
                            .attributes(ObservedHttp.maxResponseBytes(MAX_BYTES))
                            .body(requestBody(urls))
                            .retrieve()
                            .body(String.class),
                    clock));
    List<ReadPage> pages = new ArrayList<>();
    for (JsonNode r : root.path("results")) {
      String url = HttpClients.text(r, "url");
      String raw = HttpClients.text(r, "raw_content");
      if (url != null && raw != null && !raw.isBlank()) {
        pages.add(new ReadPage(url, raw));
      }
    }
    return pages;
  }

  public static Map<String, Object> requestBody(List<URI> urls) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("urls", urls.stream().map(URI::toString).toList());
    body.put("extract_depth", "basic");
    body.put("format", "markdown");
    body.put("include_images", false);
    body.put("include_favicon", false);
    return body;
  }
}
