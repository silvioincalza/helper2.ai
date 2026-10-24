package ai.helper2.platform.llm;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ModelAnswersTest {

    @Test
    void reasoningWithBracesDoesNotMoveTheJsonStart() {
        String answer = "<think>The schema is {items}; I should answer {\"items\":[]}.</think>\n{\"items\":[{\"id\":\"r0\"}]}";

        assertThat(ModelAnswers.jsonObject(answer)).contains("{\"items\":[{\"id\":\"r0\"}]}");
    }

    @Test
    void preambleAndCodeFencesAroundTheJsonAreDropped() {
        assertThat(ModelAnswers.jsonObject("Sure:\n```json\n{\"a\":1}\n```")).contains("{\"a\":1}");
    }

    @Test
    void anUnclosedReasoningBlockMeansThereIsNoAnswer() {
        String truncated = "<think>Looking at {\"a\":1} first, then";

        assertThat(ModelAnswers.withoutReasoning(truncated)).isEmpty();
        assertThat(ModelAnswers.jsonObject(truncated)).isEmpty();
    }

    @Test
    void noJsonOrNoAnswerIsEmpty() {
        assertThat(ModelAnswers.jsonObject("I think they are all AI.")).isEmpty();
        assertThat(ModelAnswers.jsonObject("} backwards {")).isEmpty();
        assertThat(ModelAnswers.jsonObject(null)).isEmpty();
        assertThat(ModelAnswers.withoutReasoning(null)).isEmpty();
    }

    @Test
    void textWithoutReasoningIsOnlyStripped() {
        assertThat(ModelAnswers.withoutReasoning("  MCP server postgres\n")).isEqualTo("MCP server postgres");
    }
}
