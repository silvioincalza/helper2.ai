package ai.helper2.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

import ai.helper2.platform.settings.Setting;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.Test;

/**
 * The back office shows a setting only through one factory per kind of value (ADR 0026 (part was
 * 0045) point 7). A record's constructor must be public, so this rule keeps {@code new Setting(}
 * inside {@link Setting}: a module cannot build a setting that skips the factories.
 */
class SettingFactoriesTest {

  private static final JavaClasses CLASSES =
      new ClassFileImporter()
          .withImportOption(new DoNotIncludeTestFixtures())
          .importPackages("ai.helper2");

  @Test
  void onlyTheFactoriesBuildASetting() {
    onlyFactoriesBuildASetting().check(CLASSES);
  }

  @Test
  void theRuleFailsForANewClass() {
    JavaClasses offenders = new ClassFileImporter().importClasses(Builder.class);

    assertThat(onlyFactoriesBuildASetting().evaluate(offenders).hasViolation()).isTrue();
  }

  private static ArchRule onlyFactoriesBuildASetting() {
    return noClasses()
        .that()
        .doNotHaveFullyQualifiedName(Setting.class.getName())
        .should()
        .callConstructor(
            Setting.class,
            String.class,
            String.class,
            Setting.Kind.class,
            String.class,
            String.class,
            String.class)
        .as("only the factories of Setting build a Setting");
  }

  // Offender for theRuleFailsForANewClass; test classes are not imported by the main rule.

  static class Builder {
    Setting build() {
      return new Setting("a.b", "B", Setting.Kind.MODEL, "x", null, null);
    }
  }
}
