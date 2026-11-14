package ai.helper2.sources.huggingface;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class HubTasksTest {

  @Test
  void taskWordsMapToThePipelineTag() {
    assertThat(HubTasks.of("speech to text").map(HubTasks.Match::pipelineTag))
        .contains("automatic-speech-recognition");
    assertThat(HubTasks.of("open model for image segmentation").map(HubTasks.Match::pipelineTag))
        .contains("image-segmentation");
    assertThat(HubTasks.of("reranker model for retrieval").map(HubTasks.Match::pipelineTag))
        .contains("text-ranking");
  }

  @Test
  void theLongestPhraseWins() {
    assertThat(HubTasks.of("image segmentation").map(HubTasks.Match::phrase))
        .contains("image segmentation");
  }

  @Test
  void aQueryWithoutTaskWordsHasNoTag() {
    assertThat(HubTasks.of("stable diffusion xl")).isEmpty();
    assertThat(HubTasks.of("ollama")).isEmpty();
  }

  @Test
  void remainingWordsLeaveOutThePhrase() {
    HubTasks.Match m = HubTasks.of("reranker retrieval").orElseThrow();
    assertThat(HubTasks.remaining(List.of("reranker", "retrieval"), m))
        .containsExactly("retrieval");
  }

  @Test
  void aHyphenatedTaskWordLeavesTheRemainingWords() {
    HubTasks.Match m = HubTasks.of("distil-whisper speech-to-text").orElseThrow();
    assertThat(HubTasks.remaining(List.of("distil-whisper", "speech-to-text"), m))
        .containsExactly("distil-whisper");
  }
}
