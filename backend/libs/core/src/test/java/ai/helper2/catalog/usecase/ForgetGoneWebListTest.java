package ai.helper2.catalog.usecase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.helper2.catalog.CategoryLabels;
import ai.helper2.catalog.GoneLists;
import ai.helper2.catalog.GoneWebLists;
import ai.helper2.catalog.usecase.ForgetGoneListsTest.OneUnitOfWork;
import ai.helper2.catalog.usecase.ForgetGoneListsTest.RefreshedCategories;
import ai.helper2.shared.CanonicalUrl;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ForgetGoneWebListTest {

  static final CanonicalUrl LIST = CanonicalUrl.of("https://directory.example/best-ai-editors");
  static final Instant NOW = Instant.parse("2026-11-21T10:00:00Z");
  static final UUID NAMED = UUID.randomUUID();

  private final OneUnitOfWork unitOfWork = new OneUnitOfWork();

  // UC-030 BR-012: the address of the page that is gone, and the time of the check, reach the rule.
  @Test
  void thePageThatIsGoneIsHandedToTheRuleWithTheTimeOfTheCheck() {
    List<Object> asked = new ArrayList<>();
    GoneWebLists gone =
        (list, at) -> {
          asked.add(list);
          asked.add(at);
          return new GoneLists.Forgotten(0, List.of());
        };

    boolean held =
        new ForgetGoneWebList(gone, new RefreshedCategories(), unitOfWork).forget(LIST, NOW);

    assertThat(asked).containsExactly(LIST, NOW);
    assertThat(held).isFalse();
  }

  // UC-030 BR-012: a resource a gone web page named loses the categories the list gave it.
  @Test
  void aResourceAGoneWebPageNamedGetsItsCategoriesRefreshed() {
    RefreshedCategories categories = new RefreshedCategories();
    GoneWebLists gone = (list, at) -> new GoneLists.Forgotten(1, List.of(NAMED));

    boolean held = new ForgetGoneWebList(gone, categories, unitOfWork).forget(LIST, NOW);

    assertThat(held).isTrue();
    assertThat(categories.refreshed).containsExactly(NAMED);
  }

  // UC-030 BR-012: a list that named nothing but had its own record marked gone still counts.
  @Test
  void aGoneListThatNamedNothingRefreshesNoCategory() {
    RefreshedCategories categories = new RefreshedCategories();
    GoneWebLists gone = (list, at) -> new GoneLists.Forgotten(1, List.of());

    boolean held = new ForgetGoneWebList(gone, categories, unitOfWork).forget(LIST, NOW);

    assertThat(held).isTrue();
    assertThat(categories.refreshed).isEmpty();
  }

  // UC-030 BR-012: the rows of the list and the categories of what it named change together, so
  // a failed refresh leaves the list as it was and the next check tries again.
  @Test
  void whatTheListNamedAndTheCategoriesChangeInOneUnitOfWork() {
    List<Boolean> insideTheUnitOfWork = new ArrayList<>();
    GoneWebLists gone =
        (list, at) -> {
          insideTheUnitOfWork.add(unitOfWork.open);
          return new GoneLists.Forgotten(1, List.of(NAMED));
        };
    CategoryLabels categories =
        resourceIds -> {
          insideTheUnitOfWork.add(unitOfWork.open);
          return resourceIds.size();
        };

    new ForgetGoneWebList(gone, categories, unitOfWork).forget(LIST, NOW);

    assertThat(insideTheUnitOfWork).containsExactly(true, true);
  }

  @Test
  void aRefreshThatFailsUndoesTheRemoval() {
    GoneWebLists gone = (list, at) -> new GoneLists.Forgotten(1, List.of(NAMED));
    CategoryLabels failing =
        resourceIds -> {
          throw new IllegalStateException("the refresh fails");
        };
    ForgetGoneWebList forget = new ForgetGoneWebList(gone, failing, unitOfWork);

    assertThatThrownBy(() -> forget.forget(LIST, NOW)).isInstanceOf(IllegalStateException.class);
    assertThat(unitOfWork.rolledBack).isTrue();
  }
}
