package ai.helper2.sources.huggingface;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class SpaceCategoriesTest {

  @Test
  void aNeedNamesItsCategoryByItsWordsInAnyOrder() {
    assertThat(SpaceCategories.of("remove image background")).contains("background-removal");
    assertThat(SpaceCategories.of("Remove background from photo")).contains("background-removal");
    assertThat(SpaceCategories.of("make images from text")).contains("image-generation");
    assertThat(SpaceCategories.of("make a logo")).contains("image-generation");
    assertThat(SpaceCategories.of("make a song with AI")).contains("music-generation");
    assertThat(SpaceCategories.of("text to speech, free")).contains("speech-synthesis");
    assertThat(SpaceCategories.of("TTS")).contains("speech-synthesis");
  }

  @Test
  void aNeedThatFitsNoRuleHasNoCategory() {
    assertThat(SpaceCategories.of("chat with a pdf")).isEmpty();
    assertThat(SpaceCategories.of("speech to text")).isEmpty();
    assertThat(SpaceCategories.of("image background")).isEmpty();
    assertThat(SpaceCategories.of("backgrounder removals")).isEmpty();
    assertThat(SpaceCategories.of("")).isEmpty();
  }
}
