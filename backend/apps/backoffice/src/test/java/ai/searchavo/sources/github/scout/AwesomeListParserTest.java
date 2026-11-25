package ai.searchavo.sources.github.scout;

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

  static final String WITH_LINKS =
      """
      # Awesome Voice
      - [Voice tools](#voice-tools)
      ## Tools
      ### Text-to-speech
      - [Acme Voice](https://acme.ai/?utm_source=list) - Clones voices. [Docs](https://docs.acme.ai)
      - [Repo](https://github.com/acme/voice) - [Site](https://repo-site.ai)
      - [![logo](https://img.example/logo.png)](https://badge-only.ai)
      - [Again](https://acme.ai/?utm_source=list)
      - [Licence](LICENSE)
      ```
      - [Fenced](https://fenced.ai)
      ```
      | Name | Site |
      | [Tabled](https://tabled.ai) | fast |
      Prose with [a link](https://prose.ai) is not an entry.
      """;

  @Test
  void everyLinkOfAnEntryOutsideGitHubIsKeptWithItsHeading() {
    List<AwesomeListParser.Link> links =
        AwesomeListParser.parse(WITH_LINKS, "acme/awesome-voice").links();

    assertThat(links)
        .extracting(
            AwesomeListParser.Link::url,
            AwesomeListParser.Link::text,
            AwesomeListParser.Link::headingPath)
        .containsExactly(
            org.assertj.core.groups.Tuple.tuple(
                "https://acme.ai/?utm_source=list", "Acme Voice", "Tools > Text-to-speech"),
            org.assertj.core.groups.Tuple.tuple(
                "https://docs.acme.ai", "Docs", "Tools > Text-to-speech"),
            org.assertj.core.groups.Tuple.tuple(
                "https://repo-site.ai", "Site", "Tools > Text-to-speech"),
            org.assertj.core.groups.Tuple.tuple(
                "https://tabled.ai", "Tabled", "Tools > Text-to-speech"));
  }

  @Test
  void aLinkIsKeptOnceWithTheTextOfItsEntryAndOfTheLinesUnderIt() {
    String readme =
        """
        ## Models
        - [Repo](https://github.com/acme/voice) - Speaks. [Site](https://voice.ai) and [weights](https://huggingface.co/acme/voice)
          Trained on [a corpus](https://corpus.example.org/voice).
        - [Site again](https://voice.ai)
        """;

    List<AwesomeListParser.Link> links = AwesomeListParser.parse(readme, "acme/list").links();

    assertThat(links)
        .extracting(AwesomeListParser.Link::url, AwesomeListParser.Link::text)
        .containsExactly(
            org.assertj.core.groups.Tuple.tuple("https://voice.ai", "Site"),
            org.assertj.core.groups.Tuple.tuple("https://huggingface.co/acme/voice", "weights"),
            org.assertj.core.groups.Tuple.tuple("https://corpus.example.org/voice", "a corpus"));
    assertThat(links)
        .extracting(AwesomeListParser.Link::entryText)
        .containsOnly("Repo - Speaks. Site and weights Trained on a corpus.");
  }

  @Test
  void anIndentedItemWithItsOwnLinkIsItsOwnEntryAndTheLinesWithoutOneBelongToTheirItem() {
    String readme =
        """
        ## Agents
        - [Agents](https://github.com/acme/agents) - Agent frameworks.
          - Works with any model.
          - [Runner](https://github.com/acme/runner) - Runs agents.
            Needs a GPU.
        - Plain group
          - [Planner](https://planner.ai) - Plans.
        """;

    AwesomeListParser.Parsed parsed = AwesomeListParser.parse(readme, "acme/list");

    assertThat(parsed.entries())
        .extracting(AwesomeListParser.Entry::fullName, AwesomeListParser.Entry::text)
        .containsExactly(
            org.assertj.core.groups.Tuple.tuple(
                "acme/agents", "Agents - Agent frameworks. Works with any model."),
            org.assertj.core.groups.Tuple.tuple(
                "acme/runner", "Runner - Runs agents. Needs a GPU."));
    assertThat(parsed.links())
        .extracting(AwesomeListParser.Link::url, AwesomeListParser.Link::entryText)
        .containsExactly(
            org.assertj.core.groups.Tuple.tuple("https://planner.ai", "Planner - Plans."));
  }

  @Test
  void aLinkTextIsCut() {
    String readme = "## Tools\n- [" + "n".repeat(250) + "](https://long.ai)\n";

    AwesomeListParser.Link link = AwesomeListParser.parse(readme, "acme/list").links().getFirst();

    assertThat(link.text()).hasSize(AwesomeListParser.MAX_LINK_TEXT);
  }

  @Test
  void aLinkToAnotherListIsKnownByItsNameOrItsSection() {
    String readme =
        """
        # A curated list of awesome LLM tools
        - [Title only](https://github.com/acme/title-only)
        ## Tools
        - [Tool](https://github.com/acme/tool)
        - [Named](https://github.com/acme/Awesome-RAG)
        ## Other awesome lists
        - [Survey](https://github.com/acme/llm-survey)
        ### Even deeper
        - [Deeper](https://github.com/acme/deeper)
        ## Contributing
        - [Guide](https://github.com/acme/guide)
        """;

    List<AwesomeListParser.Entry> entries = AwesomeListParser.entries(readme, "acme/awesome-llm");

    assertThat(entries)
        .filteredOn(AwesomeListParser.Entry::pointsAtList)
        .extracting(AwesomeListParser.Entry::fullName)
        .containsExactly("acme/Awesome-RAG", "acme/llm-survey", "acme/deeper");
  }

  @Test
  void aListItemThatIsOnlyAUrlIsStillAnEntryAndALinkedLine() {
    String readme =
        """
        # Awesome agents
        ## Tools
        - https://github.com/acme/tool
        - <https://github.com/acme/agent>
        - [Runner](https://github.com/acme/runner) - Runs agents.
        """;

    AwesomeListParser.Parsed parsed = AwesomeListParser.parse(readme, "acme/awesome-agents");

    assertThat(parsed.entries())
        .extracting(AwesomeListParser.Entry::fullName)
        .containsExactly("acme/tool", "acme/agent", "acme/runner");
    assertThat(parsed.linkedItems()).isEqualTo(3);
    assertThat(parsed.textLines()).isEqualTo(3);
  }

  @Test
  void aPaperWithItsLinkInDoubleBracketsIsALinkedItem() {
    AwesomeListParser.Parsed parsed =
        AwesomeListParser.parse(
            "# Awesome speech\n## 2014\n"
                + "- **Deep Speech**(2014), Awni Hannun et al. [[pdf]](https://arxiv.org/pdf/1412.5567.pdf)\n"
                + "- **A neural transducer**(2015), N Jaitly et al. [[pdf]](https://arxiv.org/abs/1511.04868)\n",
            "acme/awesome-speech");

    assertThat(parsed.linkedItems()).isEqualTo(2);
    assertThat(parsed.textLines()).isEqualTo(2);
  }

  @Test
  void theSummaryAndAuthorLinesUnderAnItemBelongToIt() {
    AwesomeListParser.Parsed parsed =
        AwesomeListParser.parse(
            "# Awesome agents\n## Papers\n"
                + "- **[Why Do Multi-Agent LLM Systems Fail?](https://arxiv.org/abs/2503.13657)** (*2025*)\n"
                + "  > Presents a taxonomy for multi-agent failures.\n"
                + "1. MultiNLI: **\"A Broad-Coverage Corpus\"**. ![](https://img.shields.io/badge/Dataset-blue)\n"
                + "    *Adina Williams et al.* NAACL-HLT 2018. [[Paper](https://aclanthology.org/N18-1101)]\n"
                + "- Benchmarking Attacks on Learning with Errors. **[Topic: AEs]**\n"
                + "  [[pdf]](https://arxiv.org/abs/2408.00882)\n"
                + "  - Emily Wenger, Eshika Saxena. *IEEE S&P*, 2025.\n",
            "acme/awesome-agents");

    assertThat(parsed.linkedItems()).isEqualTo(3);
    assertThat(parsed.textLines()).isEqualTo(3);
  }

  @Test
  void anAnchorToASectionAndABadgeAreNoLinks() {
    AwesomeListParser.Parsed parsed =
        AwesomeListParser.parse(
            "# Awesome tool\n## Contents\n"
                + "- [Install](#install)\n"
                + "- [Usage](docs/usage.md)\n"
                + "- ![build](https://img.shields.io/badge/build-passing-green)\n",
            "acme/awesome-tool");

    assertThat(parsed.linkedItems()).isZero();
    assertThat(parsed.textLines()).isEqualTo(3);
  }

  @Test
  void anHtmlListItemWithALinkCounts() {
    AwesomeListParser.Parsed parsed =
        AwesomeListParser.parse(
            "# Awesome context\n<ul>\n"
                + "<li><i><b>A Survey of Large Language Models</b></i>, Zhao et al.,"
                + " <a href=\"https://arxiv.org/abs/2303.18223\">paper</a></li>\n"
                + "</ul>\n",
            "acme/awesome-context");

    assertThat(parsed.linkedItems()).isEqualTo(1);
    assertThat(parsed.textLines()).isEqualTo(1);
  }

  @Test
  void aNestedItemWithItsOwnLinkIsAnItemOfItsOwn() {
    AwesomeListParser.Parsed parsed =
        AwesomeListParser.parse(
            "# Awesome llm\n"
                + "- [Agents](https://github.com/acme/agents) - Agent frameworks.\n"
                + "  - [Runner](https://github.com/acme/runner) - Runs agents.\n",
            "acme/awesome-llm");

    assertThat(parsed.linkedItems()).isEqualTo(2);
  }

  @Test
  void aBadgeLinkedToAnotherPageIsNoLinkedItem() {
    StringBuilder readme = new StringBuilder("# Awesome builds\n");
    for (int i = 0; i < 20; i++) {
      readme
          .append("- [![status](https://img.shields.io/badge/build-passing-green)]")
          .append("(https://ci.example.com/build/")
          .append(i)
          .append(")\n");
    }
    readme.append(
        "- [![logo](https://img.shields.io/badge/x-y-z)](https://ci.example.com) [Runner](https://runner.example.com)\n");

    AwesomeListParser.Parsed parsed =
        AwesomeListParser.parse(readme.toString(), "acme/awesome-builds");

    assertThat(parsed.linkedItems()).isEqualTo(1);
  }
}
