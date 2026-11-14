package ai.helper2.catalog;

import java.util.List;

public interface Embedder {

  enum Kind {
    QUERY,
    PASSAGE
  }

  String model();

  int dimensions();

  List<float[]> embed(List<String> texts, Kind kind);

  enum FailureReason {
    RATE_LIMITED,
    REFUSED,
    PROVIDER_ERROR,
    UNAVAILABLE,
    INVALID_RESPONSE
  }

  final class EmbeddingFailure extends RuntimeException {
    private final FailureReason reason;

    public EmbeddingFailure(String message, FailureReason reason) {
      super(message);
      this.reason = reason;
    }

    public FailureReason reason() {
      return reason;
    }

    public boolean rateLimited() {
      return reason == FailureReason.RATE_LIMITED;
    }
  }
}
