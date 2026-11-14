package ai.helper2.catalog;

import java.util.List;
import java.util.UUID;

public interface EmbeddingStore {
  record EmbeddingTask(UUID resourceId, String text, String textHash) {}

  record PendingEmbeddings(List<EmbeddingTask> tasks, long backlog) {}

  record EmbeddingCoverage(long embedded, long total) {}

  final class Unavailable extends RuntimeException {
    public Unavailable(Throwable cause) {
      super(cause.getClass().getSimpleName(), cause);
    }
  }

  PendingEmbeddings pendingEmbeddings(String model, int limit);

  void saveEmbeddings(String model, List<EmbeddingTask> tasks, List<float[]> vectors);

  EmbeddingCoverage embeddingCoverage(String model);
}
