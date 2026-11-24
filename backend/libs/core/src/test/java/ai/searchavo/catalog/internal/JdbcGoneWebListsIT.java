package ai.searchavo.catalog.internal;

import static org.assertj.core.api.Assertions.assertThat;

import ai.searchavo.TestcontainersConfiguration;
import ai.searchavo.catalog.AwesomeListIndex;
import ai.searchavo.catalog.AwesomeListText;
import ai.searchavo.catalog.DirectoryListIndex;
import ai.searchavo.catalog.DirectoryRatingIndex;
import ai.searchavo.catalog.DirectoryRatingIndex.ListRatings;
import ai.searchavo.catalog.DirectoryRatingIndex.Reading;
import ai.searchavo.catalog.GoneLists;
import ai.searchavo.catalog.GoneWebLists;
import ai.searchavo.catalog.ListNamings;
import ai.searchavo.catalog.ResourceRatings;
import ai.searchavo.catalog.StatedFigures;
import ai.searchavo.shared.CanonicalUrl;
import ai.searchavo.shared.CollectorIds;
import ai.searchavo.shared.ResourceId;
import ai.searchavo.testing.AsBackOffice;
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
class JdbcGoneWebListsIT {

  @Autowired GoneWebLists gone;
  @Autowired DirectoryListIndex directoryLists;
  @Autowired DirectoryRatingIndex directoryRatings;
  @Autowired ResourceRatings ratings;
  @Autowired AwesomeListIndex index;
  @Autowired ListNamings namings;
  @Autowired ResourceRows rows;
  @Autowired JdbcClient jdbc;

  private final String run = UUID.randomUUID().toString().substring(0, 8);
  private final String host = "directory-" + run + ".example";
  private final CanonicalUrl directoryList =
      CanonicalUrl.of("https://" + host + "/best-ai-editors");
  private final CanonicalUrl otherList = CanonicalUrl.of("https://" + host + "/best-ai-agents");
  private final CanonicalUrl curatedPage = CanonicalUrl.of("https://blog-" + run + ".example/list");
  private final CanonicalUrl product = CanonicalUrl.of("https://product-" + run + ".example");
  private final Instant read = Instant.now().truncatedTo(ChronoUnit.SECONDS);
  private final Instant checked = read.plus(8, ChronoUnit.DAYS);

  private static UUID idOf(CanonicalUrl url) {
    return ResourceId.of(url).getValue();
  }

  @AfterEach
  void clean() {
    List.of(product, curatedPage)
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

  private void namedAndRatedBy(CanonicalUrl list) {
    directoryLists.replace(
        new DirectoryListIndex.DirectoryList(
            list, "Best AI editors", List.of(new DirectoryListIndex.Entry(product, "Editors"))));
    directoryRatings.replace(
        new ListRatings(
            list,
            host,
            List.of(product),
            List.of(
                new Reading(product, list, StatedFigures.checked(4.6, 5, 800).orElseThrow(), read)),
            List.of()));
  }

  private int directoryListsNaming(CanonicalUrl resource) {
    return jdbc.sql("SELECT count(*) FROM directory_list_entry WHERE resource_id = :id")
        .param("id", idOf(resource))
        .query(Integer.class)
        .single();
  }

  private void theCuratedPageNamesTheProduct() {
    resource(curatedPage);
    jdbc.sql("INSERT INTO source (id, mode) VALUES (:source, 'INDEXED') ON CONFLICT DO NOTHING")
        .param("source", CollectorIds.CURATED_WEB_LISTS)
        .update();
    jdbc.sql(
            """
            INSERT INTO source_record (id, source_id, external_id, resource_id, source_url, canonical_url,
                                       title, type, retrieved_at, fingerprint, extra)
            VALUES (:id, :source, :url, :rid, :url, :url, 'A list', 'AWESOME_LIST', now(), 'fp', '{}'::jsonb)
            """)
        .param("id", UUID.randomUUID())
        .param("source", CollectorIds.CURATED_WEB_LISTS)
        .param("url", curatedPage.getValue())
        .param("rid", idOf(curatedPage))
        .update();
    index.replace(
        new AwesomeListText(
            curatedPage,
            "Editors",
            List.of(new AwesomeListText.Entry(product, "a tool", "Editors"))));
  }

  private int entriesOfTheCuratedPage() {
    return jdbc.sql("SELECT count(*) FROM awesome_list_entry WHERE list_id = :list")
        .param("list", idOf(curatedPage))
        .query(Integer.class)
        .single();
  }

  private Boolean readAsArchived(CanonicalUrl resource) {
    var stated = rows.statedVitality(new UUID[] {idOf(resource)}).get(idOf(resource));
    return stated == null ? null : stated.getArchived();
  }

  // UC-030 BR-012: a list page of a directory host that is gone names nothing any more, and the
  // public count of directory lists follows.
  @Test
  void aGoneDirectoryListNoLongerNamesItsResources() {
    resource(product);
    namedAndRatedBy(directoryList);
    namedAndRatedBy(otherList);

    GoneLists.Forgotten forgotten = gone.forget(directoryList, checked);

    assertThat(forgotten.lists()).isEqualTo(1);
    assertThat(forgotten.noLongerNamed()).containsExactly(idOf(product));
    assertThat(directoryListsNaming(product)).isEqualTo(1);
  }

  // UC-030 BR-012: the ratings that came from a gone list page go with it; a resource whose only
  // directory is gone has no rating, so its page says "Not rated yet" (UC-007).
  @Test
  void theRatingOfAResourceWhoseOnlyDirectoryListIsGoneIsRemoved() {
    resource(product);
    namedAndRatedBy(directoryList);
    assertThat(ratings.stated(idOf(product))).hasSize(1);

    gone.forget(directoryList, checked);

    assertThat(ratings.stated(idOf(product))).isEmpty();
    assertThat(directoryListsNaming(product)).isZero();
  }

  // UC-030 BR-012: the rating another list page of the same directory gave stays.
  @Test
  void theRatingFromAnotherListPageStays() {
    resource(product);
    namedAndRatedBy(directoryList);
    namedAndRatedBy(otherList);

    gone.forget(directoryList, checked);

    assertThat(ratings.stated(idOf(product)))
        .singleElement()
        .satisfies(stated -> assertThat(stated.page()).isEqualTo(otherList.getValue()));
  }

  // UC-030 BR-012: a curated web page that is gone loses its entries and its row of the index;
  // its own resource stays and is read as archived (UC-018 BR-009).
  @Test
  void aGoneCuratedWebPageNoLongerNamesItsResourcesAndItsOwnResourceIsReadAsArchived() {
    resource(product);
    theCuratedPageNamesTheProduct();
    assertThat(readAsArchived(curatedPage)).isNull();

    GoneLists.Forgotten forgotten = gone.forget(curatedPage, checked);

    assertThat(forgotten.lists()).isEqualTo(1);
    assertThat(forgotten.noLongerNamed()).containsExactly(idOf(product));
    assertThat(entriesOfTheCuratedPage()).isZero();
    assertThat(readAsArchived(curatedPage)).isTrue();
    assertThat(
            jdbc.sql("SELECT count(*) FROM resource WHERE id IN (:list, :named)")
                .param("list", idOf(curatedPage))
                .param("named", idOf(product))
                .query(Integer.class)
                .single())
        .isEqualTo(2);
  }

  // UC-030 BR-012: a candidate a gone curated web page proposed, accepted later, is not named by
  // it (BR-008).
  @Test
  void aGoneCuratedWebPageDoesNotNameACandidateAcceptedLater() {
    resource(product);
    theCuratedPageNamesTheProduct();
    gone.forget(curatedPage, checked);

    boolean named = namings.byCuratedList(curatedPage, idOf(product), "a tool", "Editors");

    assertThat(named).isFalse();
    assertThat(entriesOfTheCuratedPage()).isZero();
  }

  // UC-030 BR-012: a page that is forgotten twice is counted once; the second time finds nothing.
  @Test
  void forgettingAgainFindsNothing() {
    resource(product);
    theCuratedPageNamesTheProduct();
    namedAndRatedBy(directoryList);
    gone.forget(curatedPage, checked);
    gone.forget(directoryList, checked);

    assertThat(gone.forget(curatedPage, checked).lists()).isZero();
    assertThat(gone.forget(directoryList, checked).lists()).isZero();
  }

  // UC-030 BR-012: only the page that is gone is touched.
  @Test
  void aPageThatIsNotGoneKeepsWhatItNamed() {
    resource(product);
    theCuratedPageNamesTheProduct();
    namedAndRatedBy(directoryList);

    gone.forget(otherList, checked);

    assertThat(entriesOfTheCuratedPage()).isEqualTo(1);
    assertThat(directoryListsNaming(product)).isEqualTo(1);
    assertThat(ratings.stated(idOf(product))).hasSize(1);
    assertThat(readAsArchived(curatedPage)).isNull();
  }
}
