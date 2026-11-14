package ai.helper2.examples.usecase;

import ai.helper2.examples.CheckOutcome;
import ai.helper2.examples.HomeExample;
import ai.helper2.examples.HomeExamples;
import ai.helper2.examples.ResourceTitles;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

public class ListAdminHomeExamples {

  private final HomeExamples examples;
  private final ResourceTitles titles;

  public ListAdminHomeExamples(HomeExamples examples, ResourceTitles titles) {
    this.examples = examples;
    this.titles = titles;
  }

  public List<ExampleView> list(@Nullable CheckOutcome outcome) {
    List<HomeExample> all = examples.all(outcome);
    Map<UUID, String> known =
        titles.of(all.stream().flatMap(e -> e.getExpectedResourceIds().stream()).toList());
    return all.stream().map(e -> view(e, known)).toList();
  }

  static ExampleView view(HomeExample example, Map<UUID, String> titles) {
    return new ExampleView(
        example,
        example.getExpectedResourceIds().stream()
            .map(id -> new ExampleView.Expected(id, titles.get(id)))
            .toList());
  }

  static ExampleView view(HomeExample example, ResourceTitles titles) {
    Collection<UUID> ids = example.getExpectedResourceIds();
    return view(example, titles.of(ids));
  }
}
