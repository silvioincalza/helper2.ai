package ai.helper2.platform.llm;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/** NVIDIA-only (ADR 0014): the application does not start with a model id of another vendor. */
class NvidiaOnlyTest {

    static final String SUPER = "nvidia/nemotron-3-super-120b-a12b";
    static final String NANO = "nvidia/NVIDIA-Nemotron-3-Nano-30B-A3B";
    static final String EMBED = "nvidia/nemotron-3.5-embed-1b";

    @Test
    void nvidiaModelsStart() {
        assertThatCode(() -> NvidiaOnly.require("openai", SUPER, NANO, SUPER, "key", EMBED)).doesNotThrowAnyException();
    }

    @Test
    void aChatModelOfAnotherVendorStopsStartupAndIsNamed() {
        assertThatThrownBy(() -> NvidiaOnly.require("openai", SUPER, "meta-llama/Llama-3.3-70B-Instruct", SUPER, "key", EMBED))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("helper2.llm.fast-model=meta-llama/Llama-3.3-70B-Instruct");
        assertThatThrownBy(() -> NvidiaOnly.require("openai", "gpt-4o", NANO, SUPER, "", ""))
            .hasMessageContaining("spring.ai.openai.chat.model=gpt-4o");
    }

    @Test
    void anEmbeddingModelOfAnotherVendorStopsStartupWhenEmbeddingsAreOn() {
        assertThatThrownBy(() -> NvidiaOnly.require("none", "", "", "", "key", "text-embedding-3-small"))
            .hasMessageContaining("helper2.embedding.model=text-embedding-3-small");
        assertThatCode(() -> NvidiaOnly.require("none", "", "", "", "", "text-embedding-3-small")).doesNotThrowAnyException();
    }

    /** Only the OpenAI-compatible client reaches a provider; a test's scripted ChatModel calls nobody. */
    @Test
    void chatIdsAreCheckedOnlyWhenTheyReachAProvider() {
        assertThatCode(() -> NvidiaOnly.require("none", "", "fake-nano", "fake-super", "", "")).doesNotThrowAnyException();
        assertThatCode(() -> NvidiaOnly.require("fake", "", "fake-nano", "fake-super", "", "")).doesNotThrowAnyException();
    }
}
