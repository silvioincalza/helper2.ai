package ai.helper2.catalog.internal;

import ai.helper2.catalog.DataOrigin;
import ai.helper2.catalog.Embedder;
import ai.helper2.catalog.Embedder.EmbeddingFailure;
import ai.helper2.catalog.ResourceRow;
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

public class SemanticSearchSource implements SearchSource {

  private final SourceDescriptor descriptor;
  private final JdbcCatalogSearch store;
  private final Embedder embedder;
  private final Set<DataOrigin> origins;
  private final double maxDistance;
  private final Clock clock;
  private final QueryVectorCache queryVectors;

  SemanticSearchSource(
      SourceDescriptor descriptor,
      JdbcCatalogSearch store,
      Embedder embedder,
      QueryVectorCache queryVectors,
      Set<DataOrigin> origins,
      double maxDistance,
      Clock clock) {
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
      List<ResourceRow> rows =
          store.semanticSearch(vector, embedder.model(), origins, query.getLimit(), maxDistance);
      return CatalogSearchSource.hits(
          descriptor,
          rows,
          clock.instant(),
          store.statedVitality(rows),
          store.statedEventFacts(rows));
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
      throw e.rateLimited()
          ? new SourceFailure.RateLimited(Duration.ofMinutes(1))
          : new SourceFailure.Unavailable(e.getMessage());
    }
    queryVectors.put(text, vector);
    return vector;
  }
}
