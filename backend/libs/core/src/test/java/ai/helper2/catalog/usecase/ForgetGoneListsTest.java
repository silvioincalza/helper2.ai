package ai.helper2.catalog.usecase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.helper2.catalog.CategoryLabels;
import ai.helper2.catalog.GoneLists;
import ai.helper2.shared.UnitOfWork;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

class ForgetGoneListsTest {

  static final UUID LIST_RECORD = UUID.randomUUID();
  static final UUID NAMED = UUID.randomUUID();

  /** One unit of work: it says whether it is open, and whether it ended by a failure. */
  static class OneUnitOfWork implements UnitOfWork {
    boolean open;
    boolean rolledBack;

    @Override
    public <T> T run(Supplier<T> work) {
      open = true;
      try {
        return work.get();
      } catch (RuntimeException e) {
        rolledBack = true;
        throw e;
      } finally {
        open = false;
      }
    }
  }

  private final OneUnitOfWork unitOfWork = new OneUnitOfWork();

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

    new ForgetGoneLists(gone, new RefreshedCategories(), unitOfWork)
        .forget(List.of(LIST_RECORD), 3);

    assertThat(asked).containsExactly(List.of(LIST_RECORD), 3);
  }

  // UC-030 BR-010: a resource a gone list named loses the categories the list gave it.
  @Test
  void aResourceAGoneListNamedGetsItsCategoriesRefreshed() {
    RefreshedCategories categories = new RefreshedCategories();
    GoneLists gone = (records, visits) -> new GoneLists.Forgotten(1, List.of(NAMED));

    int lists = new ForgetGoneLists(gone, categories, unitOfWork).forget(List.of(LIST_RECORD), 3);

    assertThat(lists).isEqualTo(1);
    assertThat(categories.refreshed).containsExactly(NAMED);
  }

  // UC-030 BR-010: the rows of the list and the categories of what it named change together, so
  // a failed refresh leaves the list as it was and the next visit tries again.
  @Test
  void whatTheListNamedAndTheCategoriesChangeInOneUnitOfWork() {
    List<Boolean> insideTheUnitOfWork = new ArrayList<>();
    GoneLists gone =
        (records, visits) -> {
          insideTheUnitOfWork.add(unitOfWork.open);
          return new GoneLists.Forgotten(1, List.of(NAMED));
        };
    CategoryLabels categories =
        resourceIds -> {
          insideTheUnitOfWork.add(unitOfWork.open);
          return resourceIds.size();
        };

    new ForgetGoneLists(gone, categories, unitOfWork).forget(List.of(LIST_RECORD), 3);

    assertThat(insideTheUnitOfWork).containsExactly(true, true);
  }

  @Test
  void aRefreshThatFailsUndoesTheRemoval() {
    GoneLists gone = (records, visits) -> new GoneLists.Forgotten(1, List.of(NAMED));
    CategoryLabels failing =
        resourceIds -> {
          throw new IllegalStateException("the refresh fails");
        };
    ForgetGoneLists forget = new ForgetGoneLists(gone, failing, unitOfWork);

    assertThatThrownBy(() -> forget.forget(List.of(LIST_RECORD), 3))
        .isInstanceOf(IllegalStateException.class);
    assertThat(unitOfWork.rolledBack).isTrue();
  }

  @Test
  void noRecordNotFoundAsksNothing() {
    GoneLists gone =
        (records, visits) -> {
          throw new IllegalStateException("not asked");
        };

    int lists =
        new ForgetGoneLists(gone, new RefreshedCategories(), unitOfWork).forget(List.of(), 3);

    assertThat(lists).isZero();
  }
}
