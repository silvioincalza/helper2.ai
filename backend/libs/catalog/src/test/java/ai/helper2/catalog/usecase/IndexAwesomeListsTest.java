package ai.helper2.catalog.usecase;

import static org.assertj.core.api.Assertions.assertThat;

import ai.helper2.catalog.AwesomeListIndex;
import ai.helper2.catalog.AwesomeListText;
import ai.helper2.catalog.CategoryLabels;
import ai.helper2.shared.CanonicalUrl;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

class IndexAwesomeListsTest {

  static final UUID STILL_LISTED = UUID.randomUUID();
  static final UUID NO_LONGER_LISTED = UUID.randomUUID();

  static class RefreshedCategories implements CategoryLabels {
    final List<UUID> refreshed = new ArrayList<>();

    @Override
    public List<UUID> resourceIdsAfter(@Nullable UUID after, int limit) {
      return List.of();
    }

    @Override
    public int refreshCategories(Collection<UUID> resourceIds) {
      refreshed.addAll(resourceIds);
      return resourceIds.size();
    }
  }

  static AwesomeListText list(String url) {
    return new AwesomeListText(CanonicalUrl.of(url), "Agents", List.of());
  }

  @Test
  void aResourceTheListNoLongerNamesGetsItsCategoriesRefreshedToo() {
    RefreshedCategories categories = new RefreshedCategories();
    AwesomeListIndex index =
        list -> new AwesomeListIndex.Replaced(List.of(STILL_LISTED), List.of(NO_LONGER_LISTED));

    int written =
        new IndexAwesomeLists(index, categories)
            .replace(List.of(list("https://github.com/acme/awesome-agents")));

    assertThat(written).isEqualTo(1);
    assertThat(categories.refreshed).containsExactly(STILL_LISTED, NO_LONGER_LISTED);
  }

  @Test
  void aListThatFailsDoesNotStopTheNextOne() {
    RefreshedCategories categories = new RefreshedCategories();
    AwesomeListIndex index =
        list -> {
          if (list.url().getValue().endsWith("broken")) {
            throw new IllegalStateException("the index fails");
          }
          return new AwesomeListIndex.Replaced(List.of(STILL_LISTED), List.of());
        };

    int written =
        new IndexAwesomeLists(index, categories)
            .replace(
                List.of(
                    list("https://github.com/acme/broken"),
                    list("https://github.com/acme/awesome-agents")));

    assertThat(written).isEqualTo(1);
    assertThat(categories.refreshed).containsExactly(STILL_LISTED);
  }
}
