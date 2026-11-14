package ai.helper2.catalog.internal;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class PlainWordsTest {

  @Test
  void searchSyntaxIsRemovedWordsStay() {
    assertThat(JdbcCatalogSearch.plainWords("-mcp server \"postgres\""))
        .isEqualTo("mcp server postgres");
    assertThat(JdbcCatalogSearch.plainWords("apache OR mit")).isEqualTo("apache mit");
    assertThat(JdbcCatalogSearch.plainWords("  text-to-speech   model "))
        .isEqualTo("text-to-speech model");
  }

  @Test
  void aSlashSplitsANameAndADotDoesNot() {
    assertThat(JdbcCatalogSearch.plainWords("ollama/ollama")).isEqualTo("ollama ollama");
    assertThat(JdbcCatalogSearch.plainWords("@modelcontextprotocol/sdk"))
        .isEqualTo("@modelcontextprotocol sdk");
    assertThat(JdbcCatalogSearch.plainWords("llama.cpp")).isEqualTo("llama.cpp");
  }

  @Test
  void nothingButSyntaxLeavesNothing() {
    assertThat(JdbcCatalogSearch.plainWords("- \"\" or")).isEmpty();
  }
}
