package ai.searchavo.api;

import static org.assertj.core.api.Assertions.assertThat;

import ai.searchavo.examples.DraftQueue;
import ai.searchavo.examples.DraftSources;
import ai.searchavo.examples.ExampleDrafter;
import ai.searchavo.examples.ExampleSearch;
import ai.searchavo.examples.HomeExamples;
import ai.searchavo.examples.ResourceTitles;
import ai.searchavo.examples.ServedExamples;
import ai.searchavo.examples.usecase.AcceptExampleDraft;
import ai.searchavo.examples.usecase.AddHomeExample;
import ai.searchavo.examples.usecase.ChangeHomeExample;
import ai.searchavo.examples.usecase.CheckHomeExamples;
import ai.searchavo.examples.usecase.CountHomeExamples;
import ai.searchavo.examples.usecase.DraftHomeExamples;
import ai.searchavo.examples.usecase.ListAdminHomeExamples;
import ai.searchavo.examples.usecase.ListHomeExamples;
import ai.searchavo.examples.usecase.ReadExampleDraft;
import ai.searchavo.examples.usecase.RemoveHomeExample;
import ai.searchavo.platform.llm.DailyBudget;
import ai.searchavo.platform.settings.SettingsGroup;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;

class NoBackOfficeExamplesIT extends ApiTestBase {

  @Autowired ApplicationContext context;

  @Autowired MeterRegistry meters;

  @Test
  void thePublicAppHasNoBackOfficeUseCaseOrAdapterOfTheExamples() {
    List<Class<?>> backOffice =
        List.of(
            AcceptExampleDraft.class,
            AddHomeExample.class,
            ChangeHomeExample.class,
            CheckHomeExamples.class,
            CountHomeExamples.class,
            DraftHomeExamples.class,
            ListAdminHomeExamples.class,
            ReadExampleDraft.class,
            RemoveHomeExample.class,
            HomeExamples.class,
            DraftQueue.class,
            DraftSources.class,
            ExampleDrafter.class,
            ExampleSearch.class,
            ResourceTitles.class);

    assertThat(backOffice)
        .allSatisfy(
            type -> assertThat(context.getBeanNamesForType(type)).as(type.getName()).isEmpty());
    assertThat(context.getBeanNamesForType(DailyBudget.class)).doesNotContain("exampleDraftBudget");
    assertThat(context.getBeansOfType(SettingsGroup.class).values())
        .extracting(SettingsGroup::id)
        .doesNotContain("examples");
    assertThat(meters.find("searchavo.examples.draft_budget.remaining").gauge()).isNull();
    assertThat(context.getBeanNamesForType(ListHomeExamples.class)).hasSize(1);
    assertThat(context.getBeanNamesForType(ServedExamples.class)).hasSize(1);
  }
}
