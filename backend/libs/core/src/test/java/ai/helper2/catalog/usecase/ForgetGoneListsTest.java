package ai.helper2.catalog.usecase;

import static org.assertj.core.api.Assertions.assertThat;

import ai.helper2.catalog.CategoryLabels;
import ai.helper2.catalog.GoneLists;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ForgetGoneListsTest {

  static final UUID LIST_RECORD = UUID.randomUUID();
  static final UUID NAMED = UUID.randomUUID();

  static class RefreshedCategories implements CategoryLabels {
    final List<UUID> refreshed = new ArrayList<>();

    @Override
    public int refreshCategories(Collection<UUID> resourceIds) {
      refreshed.addAll(resourceIds);
      return resourceIds.size();
    }
  }

  // UC-030 BR-010: the count of visits is the one of the configuration, and it reaches the rule.
  @Test
  void theRecordsNotFoundAreAskedWithTheNumberOfVisitsThatMakeAListGone() {
    List<Object> asked = new ArrayList<>();
    GoneLists gone =
        (records, visits) -> {
          asked.add(records);
          asked.add(visits);
          return new GoneLists.Forgotten(0, List.of());
        };

    new ForgetGoneLists(gone, new RefreshedCategories()).forget(List.of(LIST_RECORD), 3);

    assertThat(asked).containsExactly(List.of(LIST_RECORD), 3);
  }

  // UC-030 BR-010: a resource a gone list named loses the categories the list gave it.
  @Test
  void aResourceAGoneListNamedGetsItsCategoriesRefreshed() {
    RefreshedCategories categories = new RefreshedCategories();
    GoneLists gone = (records, visits) -> new GoneLists.Forgotten(1, List.of(NAMED));

    int lists = new ForgetGoneLists(gone, categories).forget(List.of(LIST_RECORD), 3);

    assertThat(lists).isEqualTo(1);
    assertThat(categories.refreshed).containsExactly(NAMED);
  }

  @Test
  void noRecordNotFoundAsksNothing() {
    GoneLists gone =
        (records, visits) -> {
          throw new IllegalStateException("not asked");
        };

    int lists = new ForgetGoneLists(gone, new RefreshedCategories()).forget(List.of(), 3);

    assertThat(lists).isZero();
  }
}
