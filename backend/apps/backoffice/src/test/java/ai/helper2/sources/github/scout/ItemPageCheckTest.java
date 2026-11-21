package ai.helper2.sources.github.scout;

import static org.assertj.core.api.Assertions.assertThat;

import ai.helper2.shared.CanonicalUrl;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** UC-030 BR-013: the check by code that a page is the page of the item an entry names. */
class ItemPageCheckTest {

  @ParameterizedTest
  @CsvSource({
    "Vectorly, https://vectorly.io",
    "Vectorly, https://www.vectorly.io/pricing",
    "Acme AI, https://acme.ai",
    "Acme AI, https://acme-ai.com",
    "Acme AI, https://app.acmeai.com/start",
    "Café Écrit, https://cafeecrit.fr",
    "RagFlow, https://docs.example.dev/ragflow",
  })
  void theNameIsAWholePartOfTheAddressOfAnOwnSite(String name, String address) {
    assertThat(ItemPageCheck.nameInAddress(name, CanonicalUrl.of(address), true)).isTrue();
  }

  @ParameterizedTest
  @CsvSource({
    "Chat, https://chatbase.co",
    "Acme, https://notacme.com",
    "AI, https://ai.com",
    "Go, https://go.dev",
    "Vectorly, https://reviews.example.com/best-tools",
    "Bard, https://gemini.google.com",
  })
  void aNameThatIsOnlyAPieceOfAWordOrTooShortProvesNothing(String name, String address) {
    assertThat(ItemPageCheck.nameInAddress(name, CanonicalUrl.of(address), true)).isFalse();
  }

  @ParameterizedTest
  @CsvSource({
    "RagFlow, https://github.com/infiniflow/ragflow, true",
    "infiniflow, https://github.com/infiniflow/ragflow, true",
    "GitHub, https://github.com/infiniflow/ragflow, false",
    "Hugging Face, https://huggingface.co/acme/tiny-llm, false",
    "tiny-llm, https://huggingface.co/acme/tiny-llm, true",
  })
  void onAHostManyItemsShareOnlyThePathNamesTheItem(String name, String address, boolean named) {
    assertThat(ItemPageCheck.nameInAddress(name, CanonicalUrl.of(address), false)).isEqualTo(named);
  }

  @ParameterizedTest
  @CsvSource(
      delimiter = '|',
      value = {
        "Acme AI | # Acme AI - write faster | true",
        "Acme AI | Skip to content\\n\\n## **Acme AI**: the writer | true",
        "Acme AI | # The 10 best AI writers | false",
        "Acme AI | # Acmeai for teams | false",
        "Acme AI | Acme AI is great\\n\\nNo heading here | false",
        "AI | # AI for everyone | false",
      })
  void theWordsOfTheNameStandTogetherInTheFirstHeadingOfThePage(
      String name, String markdown, boolean named) {
    assertThat(ItemPageCheck.nameInTitle(name, markdown.replace("\\n", "\n"))).isEqualTo(named);
  }
}
