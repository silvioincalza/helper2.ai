package ai.helper2.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class KnownTargetTest {

  @Test
  void aKeyIgnoresCaseOuterAndRepeatedSpacesAndAFinalPossessive() {
    assertThat(KnownTarget.key("  Open   AI ")).isEqualTo("open ai");
    assertThat(KnownTarget.key("OpenAI's")).isEqualTo("openai");
    assertThat(KnownTarget.key("Anthropic\u2019s")).isEqualTo("anthropic");
    assertThat(KnownTarget.key("Z.ai")).isEqualTo("z.ai");
  }

  @Test
  void aKeyKeepsEverythingElseSoAProductIsNotItsVendor() {
    assertThat(KnownTarget.key("OpenAI Codex")).isEqualTo("openai codex");
    assertThat(KnownTarget.key("Models")).isEqualTo("models");
    assertThat(KnownTarget.key("")).isEmpty();
  }
}
