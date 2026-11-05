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

/**
 * The evidence ladder's page reader (ADR 0056): Tavily Extract, {@code POST /extract}, basic depth,
 * markdown, no images. Docs: https://docs.tavily.com/documentation/api-reference/endpoint/extract —
 * one credit per 5 pages read. The URLs come from the Scout's code ({@code OfficialPages}), never
 * from a model. A page in {@code failed_results} is a page with no text. Tavily follows redirects:
 * each result says the URL it ended at, and the Scout checks that URL again.
 */
public class TavilyExtract implements PageReader {

  static final String POLICY = "collector-gap-web";

  /** One call reads at most this many URLs; the Scout sends at most 4. */
  static final int MAX_URLS = 5;

  /** The whole answer: up to 4 pages of markdown. */
  static final int MAX_BYTES = 2 * 1024 * 1024;

  private final RestClient http;
  private final Resilience resilience;
  private final Clock clock;
  private final boolean available;

  public TavilyExtract(
      SourceSettings settings, RestClient.Builder builder, Resilience resilience, Clock clock) {
    this.available = settings.enabled() && settings.hasToken();
    if (available) {
      resilience.require(POLICY);
    }
    this.http = HttpClients.client(builder, settings);
    this.resilience = resilience;
    this.clock = clock;
  }

  /** Off without H2_TAVILY_API_KEY or with H2_SOURCE_TAVILY_EXTRACT_ENABLED=false. */
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

  /** Basic depth (1 credit per 5 pages), markdown so the Scout can read the links, no images. */
  static Map<String, Object> requestBody(List<URI> urls) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("urls", urls.stream().map(URI::toString).toList());
    body.put("extract_depth", "basic");
    body.put("format", "markdown");
    body.put("include_images", false);
    body.put("include_favicon", false);
    return body;
  }
}
