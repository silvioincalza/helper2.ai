package ai.helper2.sources.research;

import ai.helper2.platform.resilience.Resilience;
import ai.helper2.research.ResearchTool;
import ai.helper2.search.ShadowLibraries;
import ai.helper2.sources.liveweb.scout.ScoutLiveWebCredits;
import ai.helper2.sources.support.HttpClients;
import java.net.URI;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

public final class WebSearchTool implements ResearchTool {

  public static final String FEATURE = "review-research";
  static final String POLICY = "source-discovery";
  private static final int RESULTS = 8;

  private final RestClient http;
  private final ScoutLiveWebCredits credits;
  private final Resilience resilience;
  private final Clock clock;
  private final ShadowLibraries shadowLibraries;

  public WebSearchTool(
      RestClient http,
      ScoutLiveWebCredits credits,
      Resilience resilience,
      Clock clock,
      ShadowLibraries shadowLibraries) {
    this.http = http;
    this.credits = credits.forFeature(FEATURE);
    this.resilience = resilience;
    this.clock = clock;
    this.shadowLibraries = shadowLibraries;
  }

  @Override
  public String name() {
    return "web_search";
  }

  @Override
  public String description() {
    return "Search the web. Returns up to 8 results (title, url, short text). Results are leads, not evidence: read a"
        + " page with read_page before you cite it.";
  }

  @Override
  public String parameters() {
    return """
    {"type":"object","properties":{"query":{"type":"string","description":"The search, a few words"}},\
    "required":["query"]}\
    """;
  }

  @Override
  public Output call(String arguments) {
    String query = Args.of(arguments).text("query");
    if (query == null) {
      return Output.error("query is required (1 to 500 characters)");
    }
    if (credits.capReached()) {
      return Output.error("today's web search credits are used up");
    }
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("query", query);
    body.put("topic", "general");
    body.put("search_depth", "basic");
    body.put("max_results", RESULTS);
    body.put("include_answer", false);
    body.put("include_raw_content", false);
    JsonNode root =
        resilience.call(
            POLICY,
            () ->
                credits.countedAttempt(
                    "basic",
                    () ->
                        HttpClients.getJson(
                            () ->
                                http.post().uri("/search").body(body).retrieve().body(String.class),
                            clock)));
    StringBuilder out = new StringBuilder();
    int n = 0;
    for (JsonNode r : root.path("results")) {
      String url = r.path("url").asString("");
      if (url.isBlank() || shadowLibraries.isShadowLibrary(host(url))) {
        continue;
      }
      out.append(++n)
          .append(". ")
          .append(r.path("title").asString(""))
          .append('\n')
          .append("   ")
          .append(url)
          .append('\n')
          .append("   ")
          .append(r.path("content").asString("").replaceAll("\\s+", " "))
          .append("\n\n");
    }
    return new Output(n == 0 ? "no results" : out.toString().strip(), null);
  }

  private static String host(String url) {
    try {
      String h = URI.create(url).getHost();
      return h == null ? "" : h;
    } catch (IllegalArgumentException e) {
      return "";
    }
  }
}
