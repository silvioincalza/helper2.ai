package ai.searchavo.catalog.config;

import static org.assertj.core.api.Assertions.assertThat;

import ai.searchavo.testing.ConfigBinding;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.validation.BindValidationException;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

class CatalogPropertiesBindingTest {

  @Configuration(proxyBeanMethods = false)
  @EnableConfigurationProperties({CatalogProperties.class, CatalogRetentionProperties.class})
  static class Binding {}

  final ApplicationContextRunner context = ConfigBinding.of(Binding.class);

  @Test
  void theYamlDefaultsBind() {
    context.run(c -> {
      assertThat(c.getBean(CatalogProperties.class))
          .isEqualTo(
              new CatalogProperties(
                  256,
                  Duration.ofHours(24),
                  new CatalogProperties.Index(Duration.ofSeconds(3)),
                  new CatalogProperties.Curated(true, Duration.ofMillis(1000)),
                  new CatalogProperties.Semantic(Duration.ofSeconds(3), 0.65, Duration.ofDays(7)),
                  new CatalogProperties.Indexer(
                      Duration.ofSeconds(30), 1, 5, Duration.ofSeconds(30), Duration.ofSeconds(5)),
                  new CatalogProperties.Categories(false),
                  true));
      assertThat(c.getBean(CatalogRetentionProperties.class))
          .isEqualTo(
              new CatalogRetentionProperties(
                  Duration.ofDays(180),
                  Duration.ofDays(365),
                  Duration.ofDays(90),
                  Duration.ofDays(180),
                  Duration.ofDays(3650),
                  Duration.ofDays(180)));
    });
  }

  @Test
  void anH2VariableReachesItsField() {
    context
        .withSystemProperties(
            "SA_SOURCE_INDEX_TIMEOUT=5s",
            "SA_SOURCE_CURATED_ENABLED=false",
            "SA_SOURCE_SEMANTIC_MAX_DISTANCE=0.5",
            "SA_SEARCH_QUERY_VECTOR_TTL=1d",
            "SA_RETENTION_WEB_NEWS=P30D",
            "SA_CATALOG_RECONCILE_BY_PROJECTION=false")
        .run(c -> {
          CatalogProperties catalog = c.getBean(CatalogProperties.class);
          assertThat(catalog.index().timeout()).isEqualTo(Duration.ofSeconds(5));
          assertThat(catalog.curated().enabled()).isFalse();
          assertThat(catalog.semantic().maxDistance()).isEqualTo(0.5);
          assertThat(catalog.semantic().queryVectorTtl()).isEqualTo(Duration.ofDays(1));
          assertThat(catalog.reconcileByProjection()).isFalse();
          assertThat(c.getBean(CatalogRetentionProperties.class).webNews())
              .isEqualTo(Duration.ofDays(30));
        });
  }

  @Test
  void aLeftOverListIndexPeriodIsIgnoredAndStartupGoesOn() {
    context
        .withSystemProperties("SA_RETENTION_LIST_INDEX=P30D")
        .withPropertyValues("SA_RETENTION_LIST_INDEX=P30D")
        .run(c -> {
          assertThat(c).hasNotFailed();
          assertThat(c.getBean(CatalogRetentionProperties.class).observedUnseen())
              .isEqualTo(Duration.ofDays(180));
        });
  }

  @Test
  void theCategoryBackfillRunsAtStartupByDefault() throws java.io.IOException {
    String yaml =
        new String(
            getClass().getResourceAsStream("/searchavo-shared.yaml").readAllBytes(),
            java.nio.charset.StandardCharsets.UTF_8);
    assertThat(yaml).contains("backfill-at-startup: ${SA_CATEGORIES_BACKFILL_AT_STARTUP:true}");
  }

  @Test
  void aDistanceOutsideTheCosineRangeStopsStartup() {
    context
        .withSystemProperties("SA_SOURCE_SEMANTIC_MAX_DISTANCE=2.5")
        .run(c -> {
          assertThat(c).hasFailed();
          assertThat(c.getStartupFailure())
              .rootCause()
              .isInstanceOf(BindValidationException.class)
              .hasMessageContaining("semantic.maxDistance");
        });
  }

  @Test
  void anEmptyRetentionPeriodStopsStartup() {
    context
        .withPropertyValues("SA_RETENTION_PAST_EVENTS=")
        .run(c -> {
          assertThat(c).hasFailed();
          assertThat(c.getStartupFailure())
              .rootCause()
              .isInstanceOf(BindValidationException.class)
              .hasMessageContaining("pastEvents");
        });
  }
}
