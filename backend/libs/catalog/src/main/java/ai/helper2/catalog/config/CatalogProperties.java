package ai.helper2.catalog.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * The catalog's settings, from {@code helper2.catalog}: its own search sources (index, curated,
 * semantic), the background writer of observed results and the embedding indexer. The defaults and
 * the {@code H2_} variables are in application.yaml (ADR 0007 (part was 0007) point 8).
 *
 * @param observationQueue search results waiting to be written in the background
 */
@Validated
@ConfigurationProperties("helper2.catalog")
public record CatalogProperties(
    @Positive int observationQueue,
    @Valid @NotNull Index index,
    @Valid @NotNull Curated curated,
    @Valid @NotNull Semantic semantic,
    @Valid @NotNull Indexer indexer,
    @Valid @NotNull Categories categories) {

  /**
   * The categories of the catalog's resources (ADR 0013).
   *
   * @param backfillAtStartup assign them again at startup when the category words or rules changed
   */
  public record Categories(boolean backfillAtStartup) {}

  /** The keyword search of the index. */
  public record Index(@NotNull Duration timeout) {}

  /** The editorial seed list. */
  public record Curated(boolean enabled, @NotNull Duration timeout) {}

  /**
   * Vector search (ADR 0011), on only with an embedding key.
   *
   * @param maxDistance cosine distance cut-off: 0 = same, 2 = opposite
   * @param queryVectorTtl how long query vectors are shared by the replicas
   */
  public record Semantic(
      @NotNull Duration timeout,
      @DecimalMin("0.0") @DecimalMax("2.0") double maxDistance,
      @NotNull Duration queryVectorTtl) {}

  /**
   * The background indexer: [batch] texts per call every [every], each call bounded by [timeout].
   * When the API rejects a batch, up to [singlesAfterFailure] of its texts go one per call in the
   * same tick (0: none).
   */
  public record Indexer(
      @NotNull Duration timeout,
      @Positive int batch,
      @PositiveOrZero int singlesAfterFailure,
      @NotNull Duration initialDelay,
      @NotNull Duration every) {}
}
