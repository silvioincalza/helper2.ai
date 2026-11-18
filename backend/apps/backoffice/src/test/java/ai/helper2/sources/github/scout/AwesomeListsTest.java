package ai.helper2.sources.github.scout;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class AwesomeListsTest {

  static String awesomeReadme(int items) {
    return "# Awesome LLM tools [![Awesome](https://awesome.re/badge.svg)](https://awesome.re)\n\n"
        + "A curated list of tools for large language models.\n\n"
        + "## Contents\n\n"
        + IntStream.rangeClosed(1, items)
            .mapToObj(i ->
                "- [Tool "
                    + i
                    + "](https://github.com/owner"
                    + i
                    + "/tool"
                    + i
                    + ") - Does thing "
                    + i
                    + ".")
            .collect(Collectors.joining("\n"))
        + "\n\n## License\n\nCC0\n";
  }

  static final String PRODUCT_README =
      """
      # Awesome Notes

      [![build](https://img.shields.io/badge/build-passing-green)](https://ci.example)

      Awesome Notes writes the summary of your meetings. Read the [docs](https://notes.example/docs)
      or try the [web app](https://notes.example).

      ## Install

      ```
      npm install awesome-notes
      ```

      ## Features

      - Transcripts for Zoom and Meet
      - Summaries in 20 languages
      - Tasks sent to [Linear](https://linear.app)

      ## License

      MIT
      """;

  @Test
  void aListOfLinksInARepositoryNamedAwesomeIsAnAwesomeList() {
    assertThat(AwesomeLists.isAwesomeList("someone/awesome-llm-tools", awesomeReadme(30))).isTrue();
  }

  @Test
  void aProductNamedAwesomeWithANormalReadmeIsNot() {
    assertThat(AwesomeLists.isAwesomeList("acme/awesome-notes", PRODUCT_README)).isFalse();
  }

  @Test
  void aRepositoryNamedAwesomeWithNoListIsNot() {
    assertThat(
            AwesomeLists.isAwesomeList(
                "someone/awesome-x",
                "# awesome-x\n\nA fast parser.\n\nIt parses things quickly.\n"))
        .isFalse();
  }

  @Test
  void theNameAloneNeverDecidesAndAListUnderAnotherNameIsNot() {
    assertThat(AwesomeLists.isAwesomeList("someone/llm-tools", awesomeReadme(30))).isFalse();
  }

  @Test
  void fewerThanTwentyLinkedItemsIsNotEnough() {
    assertThat(AwesomeLists.isAwesomeList("someone/awesome-tiny", awesomeReadme(19))).isFalse();
    assertThat(AwesomeLists.isAwesomeList("someone/awesome-tiny", awesomeReadme(20))).isTrue();
  }

  @Test
  void linkedItemsMustBeAtLeastHalfOfTheTextLines() {
    String prose =
        IntStream.rangeClosed(1, 40)
            .mapToObj(i -> "This is a long paragraph line number " + i + " about the project.")
            .collect(Collectors.joining("\n"));
    assertThat(
            AwesomeLists.isAwesomeList("someone/awesome-essay", prose + "\n" + awesomeReadme(25)))
        .isFalse();
  }
}
