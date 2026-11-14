package ai.helper2.api;

import static org.assertj.core.api.Assertions.assertThat;

import ai.helper2.examples.DraftQueue;
import ai.helper2.examples.DraftSources;
import ai.helper2.examples.ExampleDrafter;
import ai.helper2.examples.ExampleSearch;
import ai.helper2.examples.HomeExamples;
import ai.helper2.examples.ResourceTitles;
import ai.helper2.examples.ServedExamples;
import ai.helper2.examples.UnitOfWork;
import ai.helper2.examples.usecase.AcceptExampleDraft;
import ai.helper2.examples.usecase.AddHomeExample;
import ai.helper2.examples.usecase.ChangeHomeExample;
import ai.helper2.examples.usecase.CheckHomeExamples;
import ai.helper2.examples.usecase.CountHomeExamples;
import ai.helper2.examples.usecase.DraftHomeExamples;
import ai.helper2.examples.usecase.ListAdminHomeExamples;
import ai.helper2.examples.usecase.ListHomeExamples;
import ai.helper2.examples.usecase.ReadExampleDraft;
import ai.helper2.examples.usecase.RemoveHomeExample;
import ai.helper2.platform.llm.DailyBudget;
import ai.helper2.platform.settings.SettingsGroup;
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
            ResourceTitles.class,
            UnitOfWork.class);

    assertThat(backOffice)
        .allSatisfy(
            type -> assertThat(context.getBeanNamesForType(type)).as(type.getName()).isEmpty());
    assertThat(context.getBeanNamesForType(DailyBudget.class)).doesNotContain("exampleDraftBudget");
    assertThat(context.getBeansOfType(SettingsGroup.class).values())
        .extracting(SettingsGroup::id)
        .doesNotContain("examples");
    assertThat(meters.find("helper2.examples.draft_budget.remaining").gauge()).isNull();
    assertThat(context.getBeanNamesForType(ListHomeExamples.class)).hasSize(1);
    assertThat(context.getBeanNamesForType(ServedExamples.class)).hasSize(1);
  }
}
