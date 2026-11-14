package ai.helper2.sources.github;

import ai.helper2.search.SearchSource;
import ai.helper2.search.SourceDescriptor;
import ai.helper2.search.SourceHit;
import ai.helper2.search.SourceQuery;
import ai.helper2.shared.ResourceType;
import ai.helper2.shared.SourceMode;
import ai.helper2.sources.support.Classification;
import ai.helper2.sources.support.HttpClients;
import ai.helper2.sources.support.SourceSettings;
import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.stream.Stream;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

public class GitHubSource implements SearchSource {

  private final RestClient http;
  private final Clock clock;
  private final SourceDescriptor descriptor;

  public GitHubSource(SourceSettings settings, RestClient.Builder builder, Clock clock) {
    this.clock = clock;
    this.http = HttpClients.gitHubClient(builder, settings);
    this.descriptor =
        new SourceDescriptor(
            "github",
            "GitHub",
            "GitHub",
            true,
            SourceMode.LIVE,
            URI.create("https://github.com"),
            URI.create(
                "https://docs.github.com/en/site-policy/github-terms/github-terms-of-service"),
            "Repository metadata from the GitHub REST API",
            EnumSet.of(
                ResourceType.LIBRARY,
                ResourceType.MCP_SERVER,
                ResourceType.AGENT,
                ResourceType.PROMPT,
                ResourceType.SKILL),
            settings.timeout(),
            settings.cacheTtl(),
            settings.enabled(),
            20,
            false,
            true,
            true,
            true);
  }

  @Override
  public SourceDescriptor getDescriptor() {
    return descriptor;
  }

  @Override
  public List<SourceHit> search(SourceQuery query) {
    String q = searchText(query);
    boolean windowed = query.getPublishedAfter() != null;
    JsonNode root =
        HttpClients.getJson(
            () ->
                http.get()
                    .uri(u -> {
                      u.path("/search/repositories")
                          .queryParam("q", q)
                          .queryParam("per_page", Math.min(query.getLimit(), 50));
                      if (windowed) {
                        u.queryParam("sort", "stars").queryParam("order", "desc");
                      }
                      return u.build();
                    })
                    .retrieve()
                    .body(String.class),
            clock);
    return HttpClients.hits("github", root.path("items"), clock.instant(), GitHubSource::toHit);
  }

  static String searchText(SourceQuery query) {
    String words = query.getBrowse() ? BROWSE_SCOPE : searchWords(query);
    String created =
        query.getPublishedAfter() == null ? "" : " created:>=" + query.getPublishedAfter();
    return words + " fork:true archived:false" + created;
  }

  static final int MAX_WORDS_LENGTH = 200;

  static final int MAX_ALTERNATIVES = 4;

  private static String searchWords(SourceQuery query) {
    if (!query.getSearchTerms().isEmpty()) {
      return cut(query.getKeywords());
    }
    String words =
        cut(
            query.getRequiredVariants().isEmpty()
                ? query.getText()
                : query.getRequiredVariants().getFirst());
    if (words.contains(" ")) {
      return words;
    }
    List<String> alternatives =
        query.getExpandedTerms().stream()
            .map(String::trim)
            .filter(t -> !t.isEmpty() && !t.equalsIgnoreCase(words))
            .map(GitHubSource::cut)
            .filter(t -> !t.isEmpty())
            .map(t -> t.contains(" ") ? "\"" + t + "\"" : t)
            .limit(MAX_ALTERNATIVES)
            .toList();
    String text =
        String.join(" OR ", Stream.concat(Stream.of(words), alternatives.stream()).toList());
    return text.length() <= MAX_WORDS_LENGTH ? text : words;
  }

  private static String cut(String words) {
    String trimmed =
        words
            .replaceAll("[^\\p{L}\\p{N}\\s-]", " ")
            .replaceAll("(^|\\s)-+", " ")
            .trim()
            .replaceAll("\\s+", " ");
    if (trimmed.length() <= MAX_WORDS_LENGTH) {
      return trimmed;
    }
    int end = trimmed.lastIndexOf(' ', MAX_WORDS_LENGTH);
    return trimmed.substring(0, end > 0 ? end : MAX_WORDS_LENGTH);
  }

  static final String BROWSE_SCOPE = "llm OR ai-agent OR mcp OR generative-ai OR rag";

  private static SourceHit toHit(JsonNode item, Instant now, int position) {
    String name = HttpClients.text(item, "full_name");
    if (name == null) {
      return null;
    }
    String description = HttpClients.text(item, "description");
    if (!Classification.isAiRelated(name, description, GitHubRepositories.topics(item))) {
      return null;
    }
    return GitHubRepositories.hit("github", item, now, position);
  }
}
