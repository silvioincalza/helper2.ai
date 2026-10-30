package ai.helper2.platform.prompt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import org.junit.jupiter.api.Test;

class PromptTemplateTest {

    @Test
    void insertsEveryValue() {
        PromptTemplate template = PromptTemplate.of("t", "Return at most {{maxPicks}} picks, {{maxPicks}} or fewer.\n");

        assertThat(template.render(Map.of("maxPicks", 3))).isEqualTo("Return at most 3 picks, 3 or fewer.\n");
    }

    @Test
    void leavesJsonBracesAlone() {
        String json = "{\"items\":[{\"id\":\"r0\"}]}";

        assertThat(PromptTemplate.of("t", json).render()).isEqualTo(json);
    }

    @Test
    void insertsValuesLiterally() {
        PromptTemplate template = PromptTemplate.of("t", "values: {{values}}");

        assertThat(template.render(Map.of("values", "$1 \\ {{other}}"))).isEqualTo("values: $1 \\ {{other}}");
    }

    @Test
    void aPlaceholderWithoutAValueIsAnError() {
        PromptTemplate template = PromptTemplate.of("guide/choose.txt", "at most {{maxPicks}}");

        assertThatThrownBy(template::render)
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("prompt guide/choose.txt: no value for {{maxPicks}}");
    }

    @Test
    void aValueWithoutAPlaceholderIsAnError() {
        PromptTemplate template = PromptTemplate.of("guide/choose.txt", "at most {{maxPicks}}");

        assertThatThrownBy(() -> template.render(Map.of("maxPicks", 3, "maxPics", 3, "minQuoteChars", 20)))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("prompt guide/choose.txt: no placeholder for [maxPics, minQuoteChars]");
    }

    @Test
    void readsAPromptFromTheClasspath() {
        assertThat(PromptTemplate.fromClasspath("search/translate-query.txt").render(Map.of("maxWords", 12)))
            .startsWith("Translate the user's search query")
            .contains("at most 12 words");
    }

    @Test
    void aMissingFileIsAnError() {
        assertThatThrownBy(() -> PromptTemplate.fromClasspath("guide/missing.txt"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessage("prompt not found on the classpath: prompts/guide/missing.txt");
    }
}
