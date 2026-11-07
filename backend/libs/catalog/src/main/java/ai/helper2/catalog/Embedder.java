package ai.helper2.catalog;

import java.util.List;

/**
 * Outbound port: text to vectors with one embedding model (NVIDIA on the API catalog in
 * production). Retrieval models embed a search query and a stored text differently, so the caller
 * says which one it sends. Returns one vector per input, in input order. May throw {@link
 * EmbeddingFailure}.
 */
public interface Embedder {

  enum Kind {
    QUERY,
    PASSAGE
  }

  /**
   * Stored next to every vector, so a model change re-embeds everything instead of mixing spaces.
   */
  String model();

  int dimensions();

  List<float[]> embed(List<String> texts, Kind kind);

  /** Why an embedding call failed: callers pause, give up or try again smaller. */
  enum FailureReason {
    /** The API said 429: pause instead of retrying at once. */
    RATE_LIMITED,
    /** The circuit refused the call: the API was not reached. */
    REFUSED,
    /** The API answered with an error (5xx or another non-429 status). */
    PROVIDER_ERROR,
    /** No answer: network error or the call's deadline. */
    UNAVAILABLE,
    /** An answer we cannot use: wrong number or size of vectors. */
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
