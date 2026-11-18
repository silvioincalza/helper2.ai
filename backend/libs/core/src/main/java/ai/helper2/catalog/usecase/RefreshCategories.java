package ai.helper2.catalog.usecase;

import ai.helper2.catalog.CategoryAssignments;
import ai.helper2.catalog.CategoryAssignments.Assignment;
import ai.helper2.catalog.CategoryInputs;
import ai.helper2.catalog.CategoryInputs.Categorizable;
import ai.helper2.catalog.CategoryLabels;
import ai.helper2.shared.CategoryMatcher;
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
