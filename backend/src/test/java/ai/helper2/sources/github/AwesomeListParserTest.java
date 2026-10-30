package ai.helper2.sources.github;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class AwesomeListParserTest {

    static final String README = """
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

        assertThat(entries).containsExactly(
            new AwesomeListParser.Entry("openinterpreter/openinterpreter", "Agents"),
            new AwesomeListParser.Entry("continuedev/continue", "Agents"),
            new AwesomeListParser.Entry("stanford-oval/storm", "Tables"),
            new AwesomeListParser.Entry("plandex-ai/plandex", "Tables"));
    }

    @Test
    void emptyReadmeHasNoEntries() {
        assertThat(AwesomeListParser.entries("", "acme/awesome-llm")).isEmpty();
    }
}
