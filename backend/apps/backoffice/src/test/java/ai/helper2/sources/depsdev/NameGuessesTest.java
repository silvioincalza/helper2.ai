package ai.helper2.sources.depsdev;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class NameGuessesTest {

  @Test
  void theRepoNameComesFirst() {
    assertThat(NameGuesses.of("huggingface/transformers")).containsExactly("transformers");
  }

  @Test
  void languageAndSdkWordsAreTakenOffOneAtATime() {
    assertThat(NameGuesses.of("anthropics/anthropic-sdk-python"))
        .containsExactly("anthropic-sdk-python", "anthropic-sdk", "anthropic");
  }

  @Test
  void aPrefixIsTakenOffToo() {
    assertThat(NameGuesses.of("acme/python-genai")).containsExactly("python-genai", "genai");
  }

  @Test
  void aNameThatIsOnlyAnAffixStays() {
    assertThat(NameGuesses.of("vercel/ai")).containsExactly("ai");
  }
}
