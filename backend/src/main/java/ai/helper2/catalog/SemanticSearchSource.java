package ai.helper2.catalog;

import ai.helper2.catalog.Embedder.EmbeddingFailure;
import ai.helper2.catalog.internal.ResourceStore;
import ai.helper2.catalog.internal.ResourceStore.DataOrigin;
import ai.helper2.search.SearchSource;
import ai.helper2.search.SourceDescriptor;
import ai.helper2.search.SourceFailure;
import ai.helper2.search.SourceHit;
import ai.helper2.search.SourceQuery;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import org.springframework.dao.DataAccessException;

/**
 * The Helper2 catalog searched by meaning: the query is embedded and the nearest stored resources are
 * returned. It is one more federated source, so SearchResources fuses it with keyword search by rank
 * (Ranking), and a failure here (embedding API down, rate limit) only removes its results.
 *
 * Query vectors are shared by every replica ({@link QueryVectorCache}), so a repeated query costs no embedding call.
 */
public class SemanticSearchSource implements SearchSource {

    private final SourceDescriptor descriptor;
    private final ResourceStore store;
    private final Embedder embedder;
    private final Set<DataOrigin> origins;
    private final double maxDistance;
    private final Clock clock;
    private final QueryVectorCache queryVectors;

    SemanticSearchSource(SourceDescriptor descriptor, ResourceStore store, Embedder embedder, QueryVectorCache queryVectors,
                         Set<DataOrigin> origins, double maxDistance, Clock clock) {
        this.descriptor = descriptor;
        this.store = store;
        this.embedder = embedder;
        this.origins = origins;
        this.maxDistance = maxDistance;
        this.clock = clock;
        this.queryVectors = queryVectors;
    }

    @Override
    public SourceDescriptor getDescriptor() {
        return descriptor;
    }

    @Override
    public List<SourceHit> search(SourceQuery query) {
        String text = query.getText().strip();
        if (text.isEmpty()) {
            return List.of();
        }
        float[] vector = vectorOf(text);
        try {
            return CatalogSearchSource.hits(descriptor,
                store.semanticSearch(vector, embedder.model(), origins, query.getLimit(), maxDistance), clock.instant());
        } catch (DataAccessException e) {
            throw new SourceFailure.Unavailable("catalog unavailable");
        }
    }

    private float[] vectorOf(String text) {
        float[] cached = queryVectors.get(text);
        if (cached != null) {
            return cached;
        }
        float[] vector;
        try {
            vector = embedder.embed(List.of(text), Embedder.Kind.QUERY).getFirst();
        } catch (EmbeddingFailure e) {
            throw e.rateLimited() ? new SourceFailure.RateLimited(Duration.ofMinutes(1)) : new SourceFailure.Unavailable(e.getMessage());
        }
        queryVectors.put(text, vector);
        return vector;
    }
}
