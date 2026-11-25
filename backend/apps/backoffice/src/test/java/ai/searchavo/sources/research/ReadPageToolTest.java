package ai.searchavo.sources.research;

import static org.assertj.core.api.Assertions.assertThat;

import ai.searchavo.platform.llm.DailyBudget;
import ai.searchavo.research.ResearchTool;
import ai.searchavo.scout.PageReader;
import ai.searchavo.scout.ReadPage;
import ai.searchavo.search.ShadowLibraries;
import java.time.Clock;
import java.util.List;
import org.junit.jupiter.api.Test;

class ReadPageToolTest {

  static final String ARGUMENTS = "{\"url\":\"https://example.com/rag-book\"}";

  ReadPageTool tool(String finalUrl) {
    PageReader reader = urls -> List.of(new ReadPage(finalUrl, "The RAG book, chapter one."));
    return new ReadPageTool(
        reader,
        new DailyBudget(10, Clock.systemUTC(), "read-page-test"),
        ShadowLibraries.of("z-lib"));
  }

  @Test
  void aPageIsEvidenceAtTheAddressItWasReadFrom() {
    ResearchTool.Output out = tool("https://example.com/rag-book").call(ARGUMENTS);

    assertThat(out.text()).isEqualTo("The RAG book, chapter one.");
    assertThat(out.url()).isEqualTo("https://example.com/rag-book");
  }

  @Test
  void aPageThatEndsOnAShadowLibraryIsNoEvidence() {
    ResearchTool.Output out = tool("https://de.z-lib.fm/book/rag").call(ARGUMENTS);

    assertThat(out.text()).startsWith("error: not read:");
    assertThat(out.url()).isNull();
  }
}
