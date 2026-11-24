package ai.searchavo.sources.research;

import ai.searchavo.platform.resilience.Resilience;
import ai.searchavo.research.ResearchTool;
import ai.searchavo.search.ShadowLibraries;
import ai.searchavo.sources.liveweb.scout.ScoutLiveWebCredits;
import ai.searchavo.sources.liveweb.scout.ScoutLiveWebSearch;
import java.net.URI;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

public final class WebSearchTool implements ResearchTool {

  public static final String FEATURE = "review-research";
  static final String POLICY = "research-web-search";
  private static final String SEARCH_DEPTH = "basic";
  private static final int RESULTS = 8;
  static final int MAX_CALLS_PER_RUN = 4;

  private final ScoutLiveWebSearch liveWeb;
  private final ShadowLibraries shadowLibraries;

  public WebSearchTool(
      RestClient http,
      ScoutLiveWebCredits credits,
      Resilience resilience,
      Clock clock,
      ShadowLibraries shadowLibraries) {
    this.liveWeb =
        new ScoutLiveWebSearch(
            http, clock, resilience, credits.forFeature(FEATURE), POLICY, SEARCH_DEPTH);
    this.shadowLibraries = shadowLibraries;
    resilience.require(POLICY);
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
  public int maxCallsPerRun() {
    return MAX_CALLS_PER_RUN;
  }

  @Override
  public Output call(String arguments) {
    String query = Args.of(arguments).text("query");
    if (query == null) {
      return Output.error("query is required (1 to 500 characters)");
    }
    if (liveWeb.capReached()) {
      return Output.error("today's web search credits are used up");
    }
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("query", query);
    body.put("topic", "general");
    body.put("search_depth", SEARCH_DEPTH);
    body.put("max_results", RESULTS);
    body.put("include_answer", false);
    body.put("include_raw_content", false);
    JsonNode root = liveWeb.search(body);
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
