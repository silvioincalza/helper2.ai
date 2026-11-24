package ai.searchavo.catalog.usecase;

import ai.searchavo.catalog.CategoryAssignments;
import ai.searchavo.catalog.CategoryAssignments.Assignment;
import ai.searchavo.catalog.CategoryInputs;
import ai.searchavo.catalog.CategoryInputs.Categorizable;
import ai.searchavo.catalog.CategoryLabels;
import ai.searchavo.shared.CategoryMatcher;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

public class RefreshCategories implements CategoryLabels {

  private final CategoryInputs inputs;
  private final CategoryAssignments assignments;
  private final CategoryMatcher matcher;

  public RefreshCategories(
      CategoryInputs inputs, CategoryAssignments assignments, CategoryMatcher matcher) {
    this.inputs = inputs;
    this.assignments = assignments;
    this.matcher = matcher;
  }

  @Override
  public int refreshCategories(Collection<UUID> resourceIds) {
    if (resourceIds.isEmpty()) {
      return 0;
    }
    List<Assignment> found = new ArrayList<>();
    int categorized = 0;
    for (Categorizable resource : inputs.of(resourceIds)) {
      List<String> categories = matcher.match(resource.input());
      categories.forEach(category -> found.add(new Assignment(resource.resourceId(), category)));
      if (!categories.isEmpty()) {
        categorized++;
      }
    }
    assignments.replace(resourceIds, found);
    return categorized;
  }
}
