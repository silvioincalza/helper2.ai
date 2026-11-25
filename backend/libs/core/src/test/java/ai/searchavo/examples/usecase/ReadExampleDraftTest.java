package ai.searchavo.examples.usecase;

import static org.assertj.core.api.Assertions.assertThat;

import ai.searchavo.catalog.ReviewQueue;
import ai.searchavo.examples.DraftQueue;
import ai.searchavo.examples.ExampleDraft;
import ai.searchavo.examples.WaitingDraft;
import ai.searchavo.shared.AudiencePath;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

class ReadExampleDraftTest {

  private static final WaitingDraft DRAFT =
      new WaitingDraft("Chat with a PDF", "chat with pdf", AudiencePath.USE_AI, "nemotron");

  private final DraftQueue drafts =
      new DraftQueue() {
        @Override
        public void open(ExampleDraft draft) {}

        @Override
        public Set<UUID> waitingResources() {
          return Set.of();
        }

        @Override
        public List<WaitingDraft> waiting() {
          return List.of();
        }

        @Override
        public @Nullable WaitingDraft read(@Nullable String details) {
          return "draft".equals(details) ? DRAFT : null;
        }
      };

  private final ReadExampleDraft read = new ReadExampleDraft(drafts);

  @Test
  void aDraftedExampleIsReadByTheExamplesModule() {
    assertThat(read.in(item(ReviewQueue.Kind.EXAMPLE_CANDIDATE, "draft"))).contains(DRAFT);
  }

  @Test
  void unreadableDetailsGiveNoDraft() {
    assertThat(read.in(item(ReviewQueue.Kind.EXAMPLE_CANDIDATE, "not json"))).isEmpty();
  }

  @Test
  void anotherKindHasNoDraftEvenWithTheSameDetails() {
    assertThat(read.in(item(ReviewQueue.Kind.RESOURCE_CANDIDATE, "draft"))).isEmpty();
  }

  private static ReviewQueue.Item item(ReviewQueue.Kind kind, String details) {
    return new ReviewQueue.Item(
        UUID.randomUUID(),
        kind,
        ReviewQueue.Status.OPEN,
        UUID.randomUUID(),
        "A resource",
        "Drafted example",
        details,
        Instant.EPOCH,
        null,
        null,
        List.of());
  }
}
