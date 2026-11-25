package ai.searchavo.catalog.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.searchavo.catalog.Embedder;
import ai.searchavo.catalog.Embedder.EmbeddingFailure;
import ai.searchavo.catalog.Embedder.FailureReason;
import ai.searchavo.search.SourceFailure;
import ai.searchavo.search.SourceQuery;
import ai.searchavo.testing.InMemorySharedCache;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

class SemanticSearchSourceTest {

  private final ListAppender<ILoggingEvent> logged = new ListAppender<>();
  private final Logger sourceLog = (Logger) LoggerFactory.getLogger(SemanticSearchSource.class);

  @BeforeEach
  void captureTheLog() {
    logged.start();
    sourceLog.addAppender(logged);
  }

  @AfterEach
  void releaseTheLog() {
    sourceLog.detachAppender(logged);
  }

  @Test
  void aProviderThatFailsIsAWarningWithItsReason() {
    SemanticSearchSource source =
        failingWith(
            new EmbeddingFailure("embedding call failed: HTTP 503", FailureReason.UNAVAILABLE));

    assertThatThrownBy(() -> source.search(query("vector database")))
        .isInstanceOf(SourceFailure.Unavailable.class);

    assertThat(logged.list)
        .singleElement()
        .satisfies(event -> {
          assertThat(event.getLevel()).isEqualTo(Level.WARN);
          assertThat(event.getFormattedMessage()).contains("HTTP 503");
        });
    assertThat(logged.list)
        .extracting(ILoggingEvent::getFormattedMessage)
        .noneMatch(message -> message.contains("vector database"));
  }

  @Test
  void aCallTheCircuitRefusedIsNoWarning() {
    SemanticSearchSource source =
        failingWith(
            new EmbeddingFailure("embedding call refused: CIRCUIT_OPEN", FailureReason.REFUSED));

    assertThatThrownBy(() -> source.search(query("vector database")))
        .isInstanceOf(SourceFailure.Unavailable.class);

    assertThat(logged.list).extracting(ILoggingEvent::getLevel).doesNotContain(Level.WARN);
  }

  private static SemanticSearchSource failingWith(EmbeddingFailure failure) {
    return new SemanticSearchSource(
        CatalogSearchSourceHitsTest.INDEX,
        null,
        new FailingEmbedder(failure),
        new QueryVectorCache(
            new InMemorySharedCache("query-vector"), "nvidia/embed", 4, Duration.ofDays(7)),
        Set.of(),
        0.5,
        Clock.systemUTC());
  }

  private static SourceQuery query(String text) {
    return new SourceQuery(
        text, List.of(), null, 20, List.of(text), List.of(), null, null, null, false, Set.of());
  }

  private record FailingEmbedder(EmbeddingFailure failure) implements Embedder {

    @Override
    public String model() {
      return "nvidia/embed";
    }

    @Override
    public int dimensions() {
      return 4;
    }

    @Override
    public List<float[]> embed(List<String> texts, Kind kind) {
      throw failure;
    }
  }
}
