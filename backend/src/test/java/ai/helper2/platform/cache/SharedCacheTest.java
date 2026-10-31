package ai.helper2.platform.cache;

import static org.assertj.core.api.Assertions.assertThat;

import ai.helper2.testing.InMemorySharedCache;
import org.junit.jupiter.api.Test;

/** Shared cache keys are a hash of the query, never its text, and the same query typed differently is one key. */
class SharedCacheTest {

    private final SharedCache translations = new InMemorySharedCache("translation");

    @Test
    void aKeyIsAFixedLengthHash() {
        String key = translations.key("fast", "v1", "it", "server mcp per postgres");

        assertThat(key).matches("[0-9a-f]{64}");
        assertThat(key).doesNotContain("postgres");
    }

    @Test
    void everyPartChangesTheKey() {
        String key = translations.key("fast", "v1", "it", "server mcp");

        assertThat(translations.key("fast", "v2", "it", "server mcp")).as("prompt version").isNotEqualTo(key);
        assertThat(translations.key("other", "v1", "it", "server mcp")).as("model").isNotEqualTo(key);
        assertThat(translations.key("fast", "v1", "es", "server mcp")).as("language").isNotEqualTo(key);
    }

    @Test
    void partsCannotRunIntoEachOther() {
        assertThat(translations.key("ab", "c")).isNotEqualTo(translations.key("a", "bc"));
    }

    @Test
    void aQueryIsNormalisedForCaseAndSpaces() {
        assertThat(SharedCache.normalisedQuery("  Server   MCP\tper Postgres \n")).isEqualTo("server mcp per postgres");
    }
}
