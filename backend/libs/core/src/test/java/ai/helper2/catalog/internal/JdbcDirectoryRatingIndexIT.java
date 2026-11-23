package ai.helper2.catalog.internal;

import static org.assertj.core.api.Assertions.assertThat;

import ai.helper2.TestcontainersConfiguration;
import ai.helper2.catalog.DirectoryRating;
import ai.helper2.catalog.DirectoryRatingIndex;
import ai.helper2.catalog.DirectoryRatingIndex.ItemPage;
import ai.helper2.catalog.DirectoryRatingIndex.ListRatings;
import ai.helper2.catalog.DirectoryRatingIndex.Reading;
import ai.helper2.catalog.ResourceRatings;
import ai.helper2.catalog.StatedFigures;
import ai.helper2.shared.CanonicalUrl;
import ai.helper2.shared.ResourceId;
import ai.helper2.testing.AsBackOffice;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
@AsBackOffice
class JdbcDirectoryRatingIndexIT {

  @Autowired DirectoryRatingIndex index;
  @Autowired ResourceRatings ratings;
  @Autowired JdbcClient jdbc;

  private final String run = UUID.randomUUID().toString().substring(0, 8);
  private final String host = "directory-" + run + ".example";
  private final CanonicalUrl list = CanonicalUrl.of("https://" + host + "/best-ai-editors");
  private final CanonicalUrl product = CanonicalUrl.of("https://product-" + run + ".example");
  private final CanonicalUrl other = CanonicalUrl.of("https://other-" + run + ".example");
  private final CanonicalUrl unknown = CanonicalUrl.of("https://unknown-" + run + ".example");
  private final CanonicalUrl itemPage = CanonicalUrl.of("https://" + host + "/tool/product");
  private final Instant read = Instant.now().truncatedTo(ChronoUnit.SECONDS);

  private static UUID idOf(CanonicalUrl url) {
    return ResourceId.of(url).getValue();
  }

  @AfterEach
  void clean() {
    List.of(product, other)
        .forEach(
            url -> jdbc.sql("DELETE FROM resource WHERE id = :id").param("id", idOf(url)).update());
  }

  private void resource(CanonicalUrl url) {
    jdbc.sql(
            """
            INSERT INTO resource (id, canonical_url, url, title, description_origin, type, audience, data_origin)
            VALUES (:id, :url, :url, 'Listed', 'SOURCE_PROVIDED', 'PRODUCT', 'USE_AI', 'INDEXED')
            """)
        .param("id", idOf(url))
        .param("url", url.getValue())
        .update();
  }

  private static StatedFigures figures(double average, int scale, int votes) {
    return StatedFigures.checked(average, scale, votes).orElseThrow();
  }

  private ListRatings pass(
      List<CanonicalUrl> named, List<Reading> onTheList, List<Reading> onItemPages) {
    return new ListRatings(list, host, named, onTheList, onItemPages);
  }

  private List<DirectoryRating.Stated> statedFor(CanonicalUrl resource) {
    return ratings.stated(idOf(resource));
  }

  @Test
  void uc030Br010TheFiguresAListStatesAreKeptForTheResourcesOfTheCatalog() {
    resource(product);

    index.replace(
        pass(
            List.of(product, unknown),
            List.of(
                new Reading(product, list, figures(4.6, 5, 800), read),
                new Reading(unknown, list, figures(4.0, 5, 10), read)),
            List.of()));

    assertThat(statedFor(product))
        .containsExactly(
            new DirectoryRating.Stated(host, list.getValue(), figures(4.6, 5, 800), read));
    assertThat(ratings.stated(List.of(idOf(product), idOf(unknown))))
        .containsOnlyKeys(idOf(product));
  }

  @Test
  void uc030Br010FiguresTheListNoLongerStatesAndResourcesItNoLongerNamesAreRemoved() {
    resource(product);
    resource(other);
    index.replace(
        pass(
            List.of(product, other),
            List.of(
                new Reading(product, list, figures(4.6, 5, 800), read),
                new Reading(other, list, figures(4.0, 5, 10), read)),
            List.of()));

    index.replace(pass(List.of(product), List.of(), List.of()));

    assertThat(statedFor(product)).isEmpty();
    assertThat(statedFor(other)).isEmpty();
  }

  @Test
  void uc030Br011APageOfAnItemIsDueOnceItsResourceIsInTheCatalogAndCountsWhenTried() {
    resource(product);
    ItemPage known = new ItemPage(list, product, itemPage);
    ItemPage notInTheCatalog =
        new ItemPage(list, unknown, CanonicalUrl.of("https://" + host + "/tool/unknown"));
    Instant weekAgo = read.minus(7, ChronoUnit.DAYS);
    int before = index.itemPageTriesSince(read);

    assertThat(index.due(List.of(notInTheCatalog, known), weekAgo)).containsExactly(known);

    index.tried(known, host, read);

    assertThat(index.due(List.of(known), weekAgo)).isEmpty();
    assertThat(index.due(List.of(known), read)).containsExactly(known);
    assertThat(index.itemPageTriesSince(read)).isEqualTo(before + 1);
    assertThat(statedFor(product)).isEmpty();
  }

  @Test
  void uc030Br011TheFiguresOfAPageOfAnItemStayUntilThePageIsReadAgain() {
    resource(product);
    ItemPage page = new ItemPage(list, product, itemPage);
    index.tried(page, host, read);
    index.replace(
        pass(
            List.of(product),
            List.of(),
            List.of(new Reading(product, itemPage, figures(9.1, 10, 40), read))));

    index.replace(pass(List.of(product), List.of(), List.of()));

    assertThat(statedFor(product))
        .containsExactly(
            new DirectoryRating.Stated(host, itemPage.getValue(), figures(9.1, 10, 40), read));

    index.tried(page, host, read.plusSeconds(60));
    assertThat(statedFor(product)).hasSize(1);

    index.replace(
        pass(List.of(product), List.of(), List.of(new Reading(product, itemPage, null, read))));

    assertThat(statedFor(product)).isEmpty();
  }

  @Test
  void uc030Br010TwoReadingsOfOneResourceInOnePassKeepTheFirst() {
    resource(product);

    index.replace(
        pass(
            List.of(product),
            List.of(
                new Reading(product, list, figures(4.6, 5, 800), read),
                new Reading(product, list, figures(4.1, 5, 90), read)),
            List.of()));

    assertThat(statedFor(product))
        .extracting(DirectoryRating.Stated::figures)
        .containsExactly(figures(4.6, 5, 800));
  }

  @Test
  void uc030Br010TheFiguresGoWithTheResource() {
    resource(product);
    index.replace(
        pass(
            List.of(product),
            List.of(new Reading(product, list, figures(4.6, 5, 800), read)),
            List.of()));

    jdbc.sql("DELETE FROM resource WHERE id = :id").param("id", idOf(product)).update();

    assertThat(
            jdbc.sql("SELECT count(*) FROM directory_rating WHERE list_url = :url")
                .param("url", list.getValue())
                .query(Integer.class)
                .single())
        .isZero();
  }
}
