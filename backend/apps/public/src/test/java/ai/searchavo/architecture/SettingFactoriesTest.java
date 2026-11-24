package ai.searchavo.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

import ai.searchavo.platform.settings.Setting;
import ai.searchavo.testing.architecture.ProductionClasses;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.Test;

class SettingFactoriesTest {

  @Test
  void onlyTheFactoriesBuildASetting() {
    onlyFactoriesBuildASetting().check(ProductionClasses.onTheClasspath());
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

  static class Builder {
    Setting build() {
      return new Setting("a.b", "B", Setting.Kind.MODEL, "x", null, null);
    }
  }
}
