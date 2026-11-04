package ai.helper2.shared;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** Page titles of web results, cleaned without losing the real name. */
class WebPageTitleTest {

  static Stream<Arguments> titles() {
    return Stream.of(
        // The site name at the end goes, compared with the host and its registrable name.
        Arguments.of(
            "Best Open Source Vector Databases for Production RAG 2026 | scored.tools",
            "scored.tools",
            "Best Open Source Vector Databases for Production RAG 2026"),
        Arguments.of(
            "Chat with PDF | Ask your PDF | Free ... | allpdf.com",
            "allpdf.com",
            "Chat with PDF | Ask your PDF | Free ..."),
        Arguments.of(
            "PDF Free | Chat with PDF AI - No ... - Kaily.ai",
            "kaily.ai",
            "PDF Free | Chat with PDF AI - No ..."),
        Arguments.of("Building agents with MCP - Medium", "medium.com", "Building agents with MCP"),
        Arguments.of(
            "Building agents with MCP | by Jane Doe | Medium",
            "medium.com",
            "Building agents with MCP | by Jane Doe"),
        Arguments.of(
            "OpenAI ships a new model · The Verge", "theverge.com", "OpenAI ships a new model"),
        Arguments.of(
            "Anthropic raises funds — TechCrunch", "techcrunch.com", "Anthropic raises funds"),
        Arguments.of("Fine-tuning guide – Hugging Face", "huggingface.co", "Fine-tuning guide"),
        Arguments.of("AI rules explained | BBC", "bbc.co.uk", "AI rules explained"),
        Arguments.of("Easy, fast LLM serving - vLLM", "docs.vllm.ai", "Easy, fast LLM serving"),
        // GitHub repository pages: owner/repo is the name.
        Arguments.of(
            "GitHub - crystaldba/postgres-mcp: Postgres MCP Pro provides configurable read/write access and performance "
                + "analysis for you and your AI agents. · GitHub",
            "github.com",
            "crystaldba/postgres-mcp"),
        Arguments.of("GitHub - ollama/ollama · GitHub", "github.com", "ollama/ollama"),
        Arguments.of(
            "GitHub - vllm-project/vllm.rs: A Rust port", "github.com", "vllm-project/vllm.rs"),
        Arguments.of(
            "Issues · crystaldba/postgres-mcp · GitHub",
            "github.com",
            "Issues · crystaldba/postgres-mcp"),
        // Not the site name: kept.
        Arguments.of(
            "Ollama - Get up and running with large language models",
            "ollama.com",
            "Ollama - Get up and running with large language models"),
        Arguments.of("LangChain | Docs", "langchain.com", "LangChain | Docs"),
        Arguments.of(
            "vLLM - Easy, Fast, and Cheap LLM Serving",
            "docs.vllm.ai",
            "vLLM - Easy, Fast, and Cheap LLM Serving"),
        Arguments.of(
            "Microsoft Learn | Microsoft Learn",
            "learn.microsoft.com",
            "Microsoft Learn | Microsoft Learn"),
        Arguments.of(
            "Agents - the next step | Acme Labs",
            "news.example",
            "Agents - the next step | Acme Labs"),
        Arguments.of(
            "GitHub - not a repo name here: text",
            "example.com",
            "GitHub - not a repo name here: text"),
        Arguments.of(
            "GitHub - crystaldba/postgres-mcp: copied title",
            "mirror.example",
            "GitHub - crystaldba/postgres-mcp: copied title"),
        // Only the site name, or nothing left: the title stays.
        Arguments.of("Medium", "medium.com", "Medium"),
        Arguments.of(" | Medium", "medium.com", "| Medium"),
        Arguments.of("scored.tools", "scored.tools", "scored.tools"),
        // Whitespace trimmed and collapsed; non-ASCII kept.
        Arguments.of(
            "  Modèles   d’IA ouverts \n | Le Monde ", "lemonde.fr", "Modèles d’IA ouverts"),
        Arguments.of("生成AIの最新動向 | 日経", "nikkei.com", "生成AIの最新動向 | 日経"),
        Arguments.of(
            "Neue KI-Modelle im Überblick | Golem.de", "golem.de", "Neue KI-Modelle im Überblick"));
  }

  @ParameterizedTest
  @MethodSource("titles")
  void cleansThePageTitle(String raw, String host, String expected) {
    assertThat(WebPageTitle.of(raw, host).getTitle()).isEqualTo(expected);
  }

  @Test
  void aGitHubRepositoryTitleCarriesItsDescription() {
    WebPageTitle cleaned =
        WebPageTitle.of(
            "GitHub - crystaldba/postgres-mcp: Postgres MCP Pro provides configurable read/write "
                + "access. · GitHub",
            "github.com");

    assertThat(cleaned.getTitle()).isEqualTo("crystaldba/postgres-mcp");
    assertThat(cleaned.getDescription())
        .isEqualTo("Postgres MCP Pro provides configurable read/write access.");
  }

  @Test
  void otherTitlesHaveNoDescription() {
    assertThat(WebPageTitle.of("GitHub - ollama/ollama · GitHub", "github.com").getDescription())
        .isNull();
    assertThat(WebPageTitle.of("Building agents with MCP - Medium", "medium.com").getDescription())
        .isNull();
  }

  @Test
  void cleaningTwiceChangesNothing() {
    String once =
        WebPageTitle.of("Chat with PDF | Ask your PDF | allpdf.com", "allpdf.com").getTitle();

    assertThat(WebPageTitle.of(once, "allpdf.com").getTitle()).isEqualTo(once);
  }

  @Test
  void onlyPageTitleSourcesAreCleanedByName() {
    CanonicalUrl repo = CanonicalUrl.of("https://github.com/crystaldba/postgres-mcp");
    String raw = "GitHub - crystaldba/postgres-mcp: Postgres MCP Pro · GitHub";

    assertThat(WebPageTitle.forSource("tavily", raw, repo)).isEqualTo("crystaldba/postgres-mcp");
    assertThat(WebPageTitle.forSource("github", raw, repo)).isEqualTo(raw);
    assertThat(WebPageTitle.forSource("tavily", "crystaldba/postgres-mcp", repo))
        .isEqualTo("crystaldba/postgres-mcp");
  }

  @Test
  void withoutAHostOnlyWhitespaceIsCleaned() {
    assertThat(WebPageTitle.of("Building  agents - Medium", null).getTitle())
        .isEqualTo("Building agents - Medium");
  }
}
