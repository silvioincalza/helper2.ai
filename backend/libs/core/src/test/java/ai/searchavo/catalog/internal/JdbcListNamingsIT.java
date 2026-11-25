package ai.searchavo.catalog.internal;

import static org.assertj.core.api.Assertions.assertThat;

import ai.searchavo.TestcontainersConfiguration;
import ai.searchavo.catalog.AwesomeListEntries;
import ai.searchavo.catalog.AwesomeListIndex;
import ai.searchavo.catalog.AwesomeListText;
import ai.searchavo.catalog.DirectoryListIndex;
import ai.searchavo.catalog.ListNamings;
import ai.searchavo.catalog.ResourceDetail;
import ai.searchavo.shared.CanonicalUrl;
import ai.searchavo.shared.ResourceId;
import ai.searchavo.testing.AsBackOffice;
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
class JdbcListNamingsIT {

  @Autowired ListNamings namings;
  @Autowired AwesomeListIndex lists;
  @Autowired DirectoryListIndex directoryLists;
  @Autowired AwesomeListEntries entries;
  @Autowired JdbcClient jdbc;

  private final String run = UUID.randomUUID().toString().substring(0, 8);
  private final String directoryList = "https://directory-" + run + ".example/best-ai-editors";
  private final String curatedList = "https://github.com/acme/awesome-" + run;
  private final String product = "https://product-" + run + ".example";
  private final String course = "https://learn-" + run + ".example/courses/agents";
  private final String article = "https://blog-" + run + ".example/blog/how-we-built-it";

  private static UUID idOf(String url) {
    return ResourceId.of(CanonicalUrl.of(url)).getValue();
  }

  @AfterEach
  void clean() {
    List.of(curatedList, product, course, article)
        .forEach(
            url -> jdbc.sql("DELETE FROM resource WHERE id = :id").param("id", idOf(url)).update());
  }

  private void resource(String url, String type) {
    jdbc.sql(
            """
            INSERT INTO resource (id, canonical_url, url, title, description_origin, type, audience, data_origin)
            VALUES (:id, :url, :url, 'Listed', 'SOURCE_PROVIDED', :type, 'BUILD_WITH_AI', 'INDEXED')
            """)
        .param("id", idOf(url))
        .param("url", CanonicalUrl.of(url).getValue())
        .param("type", type)
        .update();
  }

  @Test
  void uc030Br008AnAcceptedProductIsNamedByTheDirectoryListThatProposedIt() {
    resource(product, "PRODUCT");

    namings.byDirectoryList(
        CanonicalUrl.of(directoryList), "Best AI editors", idOf(product), "Editors");
    namings.byDirectoryList(
        CanonicalUrl.of(directoryList), "Best AI editors", idOf(product), "Editors");

    assertThat(entries.naming(idOf(product), 10).directoryLists()).isEqualTo(1);
    assertThat(entries.directoryListsNaming(idOf(product)))
        .containsExactly(
            new AwesomeListEntries.DirectoryList("Best AI editors", directoryList, "Editors"));
  }

  @Test
  void uc030Br008AListReadAgainNamesTheProductTheCourseAndTheArticleItsLinksPointTo() {
    resource(product, "PRODUCT");
    resource(course, "COURSE");
    resource(article, "ARTICLE");

    directoryLists.replace(
        new DirectoryListIndex.DirectoryList(
            CanonicalUrl.of(directoryList),
            "Best AI editors",
            List.of(product, course, article).stream()
                .map(url -> new DirectoryListIndex.Entry(CanonicalUrl.of(url), "Editors"))
                .toList()));

    assertThat(List.of(product, course, article))
        .allSatisfy(url -> assertThat(entries.naming(idOf(url), 10).directoryLists()).isEqualTo(1));
  }

  @Test
  void uc030Br008AnAcceptedProductIsNamedByTheCuratedListThatProposedIt() {
    resource(curatedList, "ARTICLE");
    resource(product, "PRODUCT");
    lists.replace(new AwesomeListText(CanonicalUrl.of(curatedList), "Editors", List.of()));

    boolean first =
        namings.byCuratedList(CanonicalUrl.of(curatedList), idOf(product), "Cursor", "Editors");
    boolean again =
        namings.byCuratedList(CanonicalUrl.of(curatedList), idOf(product), "Cursor", "Editors");

    assertThat(first).isTrue();
    assertThat(again).isFalse();
    assertThat(entries.naming(idOf(product), 10).lists())
        .containsExactly(new ResourceDetail.ListEntry(idOf(curatedList), "Listed", "Editors"));
  }

  @Test
  void uc030Br008AListOrAResourceThatIsNotInTheCatalogNamesNothing() {
    resource(product, "PRODUCT");

    assertThat(
            namings.byCuratedList(CanonicalUrl.of(curatedList), idOf(product), "Cursor", "Editors"))
        .isFalse();
    namings.byDirectoryList(
        CanonicalUrl.of(directoryList), "Best AI editors", idOf(course), "Courses");

    assertThat(
            jdbc.sql("SELECT count(*) FROM directory_list_entry WHERE list_url = :url")
                .param("url", directoryList)
                .query(Integer.class)
                .single())
        .isZero();
  }
}
