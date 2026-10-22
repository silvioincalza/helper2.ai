package ai.helper2.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import ai.helper2.Helper2Application;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;

/** Architecture rules as tests: module boundaries (Spring Modulith) and hexagonal direction (ArchUnit). */
class ModularityTest {

    private static final ApplicationModules MODULES = ApplicationModules.of(Helper2Application.class);
    private static final JavaClasses CLASSES = new ClassFileImporter()
        .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
        .importPackages("ai.helper2");

    /** Packages that hold the business rules (Kotlin): the top-level packages and their use cases — not their web/cache/config/internal adapters. */
    private static final String[] DOMAIN = {"ai.helper2.shared", "ai.helper2.search", "ai.helper2.scout", "ai.helper2.guide", "ai.helper2.setup",
        "ai.helper2.search.usecase", "ai.helper2.scout.usecase", "ai.helper2.guide.usecase"};

    @Test
    void modulesHaveNoCyclesAndUseOnlyPublicApis() {
        MODULES.verify();
    }

    @Test
    void domainIsFrameworkFree() {
        noClasses().that().resideInAnyPackage(DOMAIN)
            .and().haveSimpleNameNotEndingWith("package-info")
            .should().dependOnClassesThat().resideInAnyPackage("org.springframework..", "tools.jackson..", "java.sql..")
            .check(CLASSES);
    }

    @Test
    void domainDoesNotDependOnItsAdapters() {
        noClasses().that().resideInAnyPackage(DOMAIN)
            .should().dependOnClassesThat().resideInAnyPackage("ai.helper2..web..", "ai.helper2..cache..", "ai.helper2..config..", "ai.helper2..internal..")
            .check(CLASSES);
    }
}
