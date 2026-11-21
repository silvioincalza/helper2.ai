package ai.helper2.catalog.usecase;

import static org.assertj.core.api.Assertions.assertThat;

import ai.helper2.catalog.ListNamings;
import ai.helper2.catalog.usecase.NameResourceByList.NamingList;
import ai.helper2.shared.CanonicalUrl;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class NameResourceByListTest {

  static final UUID PRODUCT = UUID.randomUUID();
  static final CanonicalUrl DIRECTORY_LIST =
      CanonicalUrl.of("https://futurepedia.io/best-ai-coding-tools");
  static final CanonicalUrl CURATED_LIST = CanonicalUrl.of("https://github.com/acme/awesome-llm");

  static class Namings implements ListNamings {
    final List<String> written = new ArrayList<>();
    boolean curatedListKnown = true;

    @Override
    public boolean byCuratedList(
        CanonicalUrl list, UUID resourceId, String text, String headingPath) {
      written.add("curated %s %s [%s] under %s".formatted(list, resourceId, text, headingPath));
      return curatedListKnown;
    }

    @Override
    public void byDirectoryList(
        CanonicalUrl list, String title, UUID resourceId, String headingPath) {
      written.add("directory %s (%s) %s under %s".formatted(list, title, resourceId, headingPath));
    }
  }

  final Namings namings = new Namings();
  final IndexAwesomeListsTest.RefreshedCategories categories =
      new IndexAwesomeListsTest.RefreshedCategories();
  final NameResourceByList name = new NameResourceByList(namings, categories);

  @Test
  void uc030Br008AProductAcceptedFromADirectoryListIsNamedByItsAddressAndTitle() {
    name.name(PRODUCT, "Cursor", new NamingList(DIRECTORY_LIST, "Editors", "Best AI coding tools"));

    assertThat(namings.written)
        .containsExactly(
            "directory %s (Best AI coding tools) %s under Editors"
                .formatted(DIRECTORY_LIST, PRODUCT));
    assertThat(categories.refreshed).isEmpty();
  }

  @Test
  void uc030Br008AProductAcceptedFromACuratedListIsNamedByItAndGetsItsCategories() {
    name.name(PRODUCT, "Cursor", new NamingList(CURATED_LIST, "Tools > Editors", null));

    assertThat(namings.written)
        .containsExactly(
            "curated %s %s [Cursor] under Tools > Editors".formatted(CURATED_LIST, PRODUCT));
    assertThat(categories.refreshed).containsExactly(PRODUCT);
  }

  @Test
  void uc030Br008ACuratedListThatIsNotInTheCatalogChangesNoCategory() {
    namings.curatedListKnown = false;

    name.name(PRODUCT, "Cursor", new NamingList(CURATED_LIST, "Tools", null));

    assertThat(categories.refreshed).isEmpty();
  }
}
