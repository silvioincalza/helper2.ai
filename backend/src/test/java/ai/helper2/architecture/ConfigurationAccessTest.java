package ai.helper2.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaCodeUnit;
import com.tngtech.archunit.core.domain.JavaMethodCall;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.core.env.PropertyResolver;

/**
 * Typed settings (ADR 0007 point 8): the code reads configuration only through
 * {@code @ConfigurationProperties} records. No {@code @Value} on a field, method or parameter, and
 * no {@code Environment.getProperty}. {@code @Scheduled} and {@code @ConditionalOnProperty}
 * placeholders are not {@code @Value} and stay allowed.
 */
class ConfigurationAccessTest {

  private static final JavaClasses CLASSES =
      new ClassFileImporter()
          .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
          .importPackages("ai.helper2");

  /**
   * FROZEN: the classes that still read settings with {@code @Value}. The list only shrinks: each
   * module's PR moves its classes to records and removes them here. Never add a class.
   */
  private static final Set<String> NOT_YET_TYPED =
      Set.of(
          "ai.helper2.guide.internal.GuideConfiguration",
          "ai.helper2.guide.web.GuideLimits",
          "ai.helper2.guide.web.GuideStreamController",
          "ai.helper2.setup.internal.SetupConfiguration");

  @Test
  void settingsAreReadOnlyThroughRecords() {
    noUntypedSettings().check(CLASSES);
  }

  /** A class that moved to records leaves the list, so the list cannot hide a new one later. */
  @Test
  void theAllowListHasNoClassThatIsAlreadyTyped() {
    Set<String> stillUntyped = new TreeSet<>();
    for (JavaClass type : CLASSES) {
      if (!violations(type).isEmpty()) {
        stillUntyped.add(topLevelName(type));
      }
    }
    assertThat(NOT_YET_TYPED).containsExactlyInAnyOrderElementsOf(stillUntyped);
  }

  @Test
  void theRuleFailsForANewClass() {
    JavaClasses offenders =
        new ClassFileImporter().importClasses(ValueField.class, ValueParameter.class, Lookup.class);

    assertThat(noUntypedSettings().evaluate(offenders).getFailureReport().getDetails())
        .hasSize(3)
        .anySatisfy(line -> assertThat(line).contains("ValueField.timeout"))
        .anySatisfy(line -> assertThat(line).contains("ValueParameter.<init>"))
        .anySatisfy(line -> assertThat(line).contains("getProperty"));
  }

  private static ArchRule noUntypedSettings() {
    return classes()
        .that(
            DescribedPredicate.describe(
                "are not on the frozen @Value list",
                type -> !NOT_YET_TYPED.contains(topLevelName(type))))
        .should(readNoSettingsByName())
        .allowEmptyShould(true);
  }

  private static ArchCondition<JavaClass> readNoSettingsByName() {
    return new ArchCondition<>("read no setting by @Value or Environment.getProperty") {
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
    }
    for (JavaMethodCall call : type.getMethodCallsFromSelf()) {
      if (readsAPropertyByName(call)) {
        found.add(call.getDescription());
      }
    }
    return found;
  }

  private static boolean readsAPropertyByName(JavaMethodCall call) {
    String name = call.getTarget().getName();
    return (name.equals("getProperty") || name.equals("getRequiredProperty"))
        && call.getTargetOwner().isAssignableTo(PropertyResolver.class);
  }

  private static String topLevelName(JavaClass type) {
    String name = type.getName();
    int nested = name.indexOf('$');
    return nested < 0 ? name : name.substring(0, nested);
  }

  // Offenders for theRuleFailsForANewClass; test classes are not imported by the main rule.

  static class ValueField {
    @Value("${helper2.example.timeout:1s}")
    String timeout;
  }

  static class ValueParameter {
    ValueParameter(@Value("${helper2.example.limit:1}") int limit) {}
  }

  static class Lookup {
    String read(Environment environment) {
      return environment.getProperty("helper2.example.key");
    }
  }
}
