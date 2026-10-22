package ai.helper2.catalog.internal;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** User text reaches websearch_to_tsquery as words only, never as its NOT / phrase / OR syntax. */
class PlainWordsTest {

    @Test
    void searchSyntaxIsRemovedWordsStay() {
        assertThat(ResourceStore.plainWords("-mcp server \"postgres\"")).isEqualTo("mcp server postgres");
        assertThat(ResourceStore.plainWords("apache OR mit")).isEqualTo("apache mit");
        assertThat(ResourceStore.plainWords("  text-to-speech   model ")).isEqualTo("text-to-speech model");
    }

    @Test
    void nothingButSyntaxLeavesNothing() {
        assertThat(ResourceStore.plainWords("- \"\" or")).isEmpty();
    }
}
