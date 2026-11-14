package ai.helper2.sources.liveweb.scout;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class QueryProgressTest {

  @Test
  void theQueriesDoneReadBackFromTheCursor() {
    String cursor = QueryProgress.cursor(List.of("tts \"open\"", "music"));

    assertThat(QueryProgress.done(cursor)).containsExactly("tts \"open\"", "music");
  }

  @Test
  void anotherCursorOrNoneMeansNothingIsDone() {
    assertThat(QueryProgress.done(null)).isEmpty();
    assertThat(QueryProgress.done("held-back")).isEmpty();
    assertThat(QueryProgress.done(QueryProgress.PREFIX + "not json")).isEmpty();
  }
}
