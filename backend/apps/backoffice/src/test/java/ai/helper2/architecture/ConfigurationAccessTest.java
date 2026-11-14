package ai.helper2.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaCodeUnit;
import com.tngtech.archunit.core.domain.JavaMethodCall;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.core.env.PropertyResolver;
import org.springframework.scheduling.annotation.Scheduled;

class ConfigurationAccessTest {

  private static final JavaClasses CLASSES =
      new ClassFileImporter()
          .withImportOption(new DoNotIncludeTestFixtures())
          .importPackages("ai.helper2");

  @Test
  void settingsAreReadOnlyThroughRecords() {
    noUntypedSettings().check(CLASSES);
  }

  @Test
  void theRuleFailsForANewClass() {
    JavaClasses offenders =
        new ClassFileImporter()
            .importClasses(
                ValueField.class, ValueParameter.class, Lookup.class, ScheduledByName.class);

    assertThat(noUntypedSettings().evaluate(offenders).getFailureReport().getDetails())
        .hasSize(4)
        .anySatisfy(line -> assertThat(line).contains("ValueField.timeout"))
        .anySatisfy(line -> assertThat(line).contains("ScheduledByName.run"))
        .anySatisfy(line -> assertThat(line).contains("ValueParameter.<init>"))
        .anySatisfy(line -> assertThat(line).contains("getProperty"));
  }

  private static ArchRule noUntypedSettings() {
    return classes().should(readNoSettingsByName());
  }

  private static ArchCondition<JavaClass> readNoSettingsByName() {
    return new ArchCondition<>(
        "read no setting by @Value, Environment.getProperty or a @Scheduled placeholder") {
      @Override
      public void check(JavaClass type, ConditionEvents events) {
        violations(type).forEach(v -> events.add(SimpleConditionEvent.violated(type, v)));
      }
    };
  }

  private static List<String> violations(JavaClass type) {
    List<String> found = new ArrayList<>();
    type.getFields().stream()
        .filter(f -> f.isAnnotatedWith(Value.class))
        .forEach(f -> found.add("@Value on " + type.getName() + "." + f.getName()));
    for (JavaCodeUnit unit : type.getCodeUnits()) {
      if (unit.isAnnotatedWith(Value.class)) {
        found.add("@Value on " + unit.getFullName());
      }
      if (unit.getParameters().stream().anyMatch(p -> p.isAnnotatedWith(Value.class))) {
        found.add("@Value on a parameter of " + unit.getFullName());
      }
      if (unit.isAnnotatedWith(Scheduled.class)
          && readsAPlaceholder(unit.getAnnotationOfType(Scheduled.class))) {
        found.add("@Scheduled placeholder on " + unit.getFullName());
      }
    }
    for (JavaMethodCall call : type.getMethodCallsFromSelf()) {
      if (readsAPropertyByName(call)) {
        found.add(call.getDescription());
      }
    }
    return found;
  }

  private static boolean readsAPlaceholder(Scheduled scheduled) {
    return List.of(
            scheduled.cron(),
            scheduled.zone(),
            scheduled.fixedDelayString(),
            scheduled.fixedRateString(),
            scheduled.initialDelayString())
        .stream()
        .anyMatch(v -> v.contains("${"));
  }

  private static boolean readsAPropertyByName(JavaMethodCall call) {
    String name = call.getTarget().getName();
    return (name.equals("getProperty") || name.equals("getRequiredProperty"))
        && call.getTargetOwner().isAssignableTo(PropertyResolver.class);
  }

  static class ValueField {
    @Value("${helper2.example.timeout:1s}")
    String timeout;
  }

  static class ValueParameter {
    ValueParameter(@Value("${helper2.example.limit:1}") int limit) {}
  }

  static class ScheduledByName {
    @Scheduled(cron = "${helper2.example.cron}")
    void run() {}
  }

  static class Lookup {
    String read(Environment environment) {
      return environment.getProperty("helper2.example.key");
    }
  }
}
