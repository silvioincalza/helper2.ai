package ai.helper2.catalog.internal;

import static org.assertj.core.api.Assertions.assertThat;

import ai.helper2.catalog.config.CatalogProperties;
import ai.helper2.search.SourceDescriptor;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

class CatalogSourceNamesTest {

  private static final String WRONG_PRODUCT_NAME = "Helper2|helper2(?!\\.ai)";
  private static final String INTERNAL_NAME = "Scout";
  private static final Duration SECOND = Duration.ofSeconds(1);

  private final CatalogProperties catalog =
      new CatalogProperties(
          1,
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
    assertThat(index.getName()).isEqualTo("helper2.ai catalog");
  }

  @Test
  void ourOwnSourcesSayHelper2AiAndNoInternalName() {
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
    assertThat(curated.getAttribution()).contains("written by helper2.ai", "Not live");
  }
}
