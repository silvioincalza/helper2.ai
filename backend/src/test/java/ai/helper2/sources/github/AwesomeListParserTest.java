package ai.helper2.sources.github;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

class AwesomeListParserTest {

  static final String README =
      """
      # Awesome LLM [![Awesome](https://awesome.re/badge.svg)](https://github.com/sindresorhus/awesome)

      See also https://github.com/someone/not-a-list-item in prose.

      ## Agents
      - [OpenInterpreter](https://github.com/openinterpreter/openinterpreter) - Code interpreter.
      - [Continue](https://github.com/continuedev/continue.git) and [again](https://github.com/OpenInterpreter/OpenInterpreter)
      * See https://github.com/topics/llm and https://github.com/sponsors/someone

      ## Tables
      | Name | Link |
      |------|------|
      | Storm | https://github.com/stanford-oval/storm. |
      1. [Self](https://github.com/acme/awesome-llm#readme)
      2. [Issues](https://github.com/plandex-ai/plandex/issues)
      """;

  @Test
  void keepsRepositoriesLinkedFromListItemsAndTablesWithTheirSection() {
    List<AwesomeListParser.Entry> entries = AwesomeListParser.entries(README, "acme/awesome-llm");

    assertThat(entries)
        .extracting(AwesomeListParser.Entry::fullName, AwesomeListParser.Entry::section)
        .containsExactly(
            org.assertj.core.groups.Tuple.tuple("openinterpreter/openinterpreter", "Agents"),
            org.assertj.core.groups.Tuple.tuple("continuedev/continue", "Agents"),
            org.assertj.core.groups.Tuple.tuple("stanford-oval/storm", "Tables"),
            org.assertj.core.groups.Tuple.tuple("plandex-ai/plandex", "Tables"));
  }

  @Test
  void emptyReadmeHasNoEntries() {
    assertThat(AwesomeListParser.entries("", "acme/awesome-llm")).isEmpty();
    assertThat(AwesomeListParser.parse("", "acme/awesome-llm").headings()).isEmpty();
  }

  @Test
  void anEntryKeepsItsLineAsPlainTextWithoutLinksOrBadges() {
    AwesomeListParser.Entry entry =
        AwesomeListParser.entries(README, "acme/awesome-llm").getFirst();

    assertThat(entry.text()).isEqualTo("OpenInterpreter - Code interpreter.");
  }

  static final String NESTED =
      """
      # Awesome AI [![Awesome](https://awesome.re/badge.svg)](https://awesome.re)
      - [Top](https://github.com/acme/top) - Under the title only.
      ## Tools
      ### Vector databases
      - [Qdrant](https://github.com/qdrant/qdrant) - Vector search engine.
      #### <a name="x"></a> Embedded :rocket:
      - [Chroma](https://github.com/chroma-core/chroma) - Embedded store.
      ### Agents
      - [Crew](https://github.com/crewai/crewai) - Agent teams.
      ## Papers
      ```
      # not a heading
      - [Fenced](https://github.com/acme/fenced)
      ```
      - [Attention](https://github.com/acme/attention) - Classic.
      """;

  @Test
  void headingPathFollowsNestedSectionsAndSkipsTheTitle() {
    List<AwesomeListParser.Entry> entries = AwesomeListParser.entries(NESTED, "acme/awesome-ai");

    assertThat(entries)
        .extracting(AwesomeListParser.Entry::fullName, AwesomeListParser.Entry::headingPath)
        .containsExactly(
            org.assertj.core.groups.Tuple.tuple("acme/top", "Awesome AI"),
            org.assertj.core.groups.Tuple.tuple("qdrant/qdrant", "Tools > Vector databases"),
            org.assertj.core.groups.Tuple.tuple(
                "chroma-core/chroma", "Tools > Vector databases > Embedded :rocket:"),
            org.assertj.core.groups.Tuple.tuple("crewai/crewai", "Tools > Agents"),
            org.assertj.core.groups.Tuple.tuple("acme/attention", "Papers"));
  }

  @Test
  void codeFencesHoldNeitherHeadingsNorEntries() {
    AwesomeListParser.Parsed parsed = AwesomeListParser.parse(NESTED, "acme/awesome-ai");

    assertThat(parsed.entries())
        .extracting(AwesomeListParser.Entry::fullName)
        .doesNotContain("acme/fenced");
    assertThat(parsed.headings())
        .containsExactly(
            "Awesome AI", "Tools", "Vector databases", "Embedded :rocket:", "Agents", "Papers");
  }

  @Test
  void aLongEntryLineAndALongHeadingAreCut() {
    String readme =
        "## "
            + "h".repeat(300)
            + "\n### "
            + "k".repeat(300)
            + "\n- [X](https://github.com/acme/x) "
            + "w".repeat(2000)
            + "\n";

    AwesomeListParser.Entry entry = AwesomeListParser.entries(readme, "acme/list").getFirst();

    assertThat(entry.text()).hasSize(AwesomeListParser.MAX_ENTRY_TEXT);
    assertThat(entry.headingPath()).hasSize(AwesomeListParser.MAX_HEADING_PATH);
    assertThat(entry.section()).hasSize(AwesomeListParser.MAX_SECTION);
  }

  @Test
  void theSearchTextStopsAtTheEntryCap() {
    AwesomeListParser.Parsed parsed = AwesomeListParser.parse(NESTED, "acme/awesome-ai");

    assertThat(parsed.searchEntries(2, Integer.MAX_VALUE))
        .extracting(AwesomeListParser.Entry::fullName)
        .containsExactly("acme/top", "qdrant/qdrant");
  }

  @Test
  void theSearchTextStopsAtTheByteCap() {
    StringBuilder readme = new StringBuilder("## Tools\n");
    for (int i = 0; i < 100; i++) {
      readme.append("- [R").append(i).append("](https://github.com/acme/r").append(i).append(")");
      readme.append(" ").append("é".repeat(100)).append('\n');
    }
    AwesomeListParser.Parsed parsed = AwesomeListParser.parse(readme.toString(), "acme/list");

    List<AwesomeListParser.Entry> kept = parsed.searchEntries(1000, 2_000);

    int bytes =
        kept.stream()
            .mapToInt(e -> (e.text() + e.headingPath()).getBytes(StandardCharsets.UTF_8).length)
            .sum();
    assertThat(kept).hasSizeBetween(1, 99);
    assertThat(bytes).isLessThanOrEqualTo(2_000);
  }

  @Test
  void theHeadingsTextIsCappedToo() {
    StringBuilder readme = new StringBuilder();
    for (int i = 0; i < 2_000; i++) {
      readme.append("## Section number ").append(i).append('\n');
    }

    String text = AwesomeListParser.parse(readme.toString(), "acme/list").headingsText();

    assertThat(text.getBytes(StandardCharsets.UTF_8).length)
        .isLessThanOrEqualTo(AwesomeListParser.MAX_HEADINGS_BYTES);
    assertThat(text).startsWith("Section number 0\n");
  }
}
