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

@Validated
@ConfigurationProperties("helper2.catalog")
public record CatalogProperties(
    @Positive int observationQueue,
    @Valid @NotNull Index index,
    @Valid @NotNull Curated curated,
    @Valid @NotNull Semantic semantic,
    @Valid @NotNull Indexer indexer,
    @Valid @NotNull Categories categories,
    boolean reconcileByProjection) {
  public record Categories(boolean backfillAtStartup) {}

  public record Index(@NotNull Duration timeout) {}

  public record Curated(boolean enabled, @NotNull Duration timeout) {}

  public record Semantic(
      @NotNull Duration timeout,
      @DecimalMin("0.0") @DecimalMax("2.0") double maxDistance,
      @NotNull Duration queryVectorTtl) {}

  public record Indexer(
      @NotNull Duration timeout,
      @Positive int batch,
      @PositiveOrZero int singlesAfterFailure,
      @NotNull Duration initialDelay,
      @NotNull Duration every) {}
}
