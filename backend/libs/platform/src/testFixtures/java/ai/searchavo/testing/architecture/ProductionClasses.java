package ai.searchavo.testing.architecture;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;

public final class ProductionClasses {

  private static final String BASE_PACKAGE = "ai.searchavo";

  private ProductionClasses() {}

  public static JavaClasses onTheClasspath() {
    return Imported.CLASSES;
  }

  private static final class Imported {
    private static final JavaClasses CLASSES =
        new ClassFileImporter()
            .withImportOption(new DoNotIncludeTestFixtures())
            .importPackages(BASE_PACKAGE);
  }
}
