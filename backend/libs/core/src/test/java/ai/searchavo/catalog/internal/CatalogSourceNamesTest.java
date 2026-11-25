package ai.searchavo.catalog.internal;

import static org.assertj.core.api.Assertions.assertThat;

import ai.searchavo.catalog.config.CatalogProperties;
import ai.searchavo.search.SourceDescriptor;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

class CatalogSourceNamesTest {

  private static final String WRONG_PRODUCT_NAME = "Searchavo|searchavo(?!\\.ai)";
  private static final String INTERNAL_NAME = "Scout";
  private static final Duration SECOND = Duration.ofSeconds(1);

  private final CatalogProperties catalog =
      new CatalogProperties(
          1,
          SECOND,
          new CatalogProperties.Index(SECOND),
          new CatalogProperties.Curated(true, SECOND),
          new CatalogProperties.Semantic(SECOND, 0.65, SECOND),
          new CatalogProperties.Indexer(SECOND, 1, 0, SECOND, SECOND),
          new CatalogProperties.Categories(false),
          true);
  private final CatalogConfiguration configuration = new CatalogConfiguration();
  private final SourceDescriptor index =
      configuration.indexSearchSource(null, Clock.systemUTC(), catalog).getDescriptor();
  private final SourceDescriptor curated =
      configuration.curatedSearchSource(null, Clock.systemUTC(), catalog).getDescriptor();

  @Test
  void theCatalogIsNamedAfterTheProductInLowerCase() {
    assertThat(index.getName()).isEqualTo("searchavo.ai catalog");
  }

  @Test
  void ourOwnSourcesSaySearchavoAiAndNoInternalName() {
    assertThat(List.of(index, curated))
        .allSatisfy(source -> {
          assertThat(source.getExternal()).isFalse();
          assertThat(source.getName()).doesNotContainPattern(WRONG_PRODUCT_NAME);
          assertThat(source.getAttribution())
              .doesNotContainPattern(WRONG_PRODUCT_NAME)
              .doesNotContainPattern(INTERNAL_NAME);
        });
  }

  @Test
  void theirDescriptionsSayInPlainWordsThatTheyAreNotLive() {
    assertThat(index.getAttribution()).contains("built ahead of time", "behind the original");
    assertThat(curated.getAttribution()).contains("written by searchavo.ai", "Not live");
  }
}
