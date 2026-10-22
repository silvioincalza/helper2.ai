package ai.helper2.catalog;

import java.util.List;

/**
 * Outbound port: text to vectors with one embedding model (NVIDIA on the API catalog in production).
 * Retrieval models embed a search query and a stored text differently, so the caller says which one it
 * sends. Returns one vector per input, in input order. May throw {@link EmbeddingFailure}.
 */
public interface Embedder {

    enum Kind { QUERY, PASSAGE }

    /** Stored next to every vector, so a model change re-embeds everything instead of mixing spaces. */
    String model();

    int dimensions();

    List<float[]> embed(List<String> texts, Kind kind);

    /** [rateLimited] lets callers pause instead of retrying at once. */
    final class EmbeddingFailure extends RuntimeException {
        private final boolean rateLimited;

        public EmbeddingFailure(String message, boolean rateLimited) {
            super(message);
            this.rateLimited = rateLimited;
        }

        public boolean rateLimited() {
            return rateLimited;
        }
    }
}
