package ai.helper2.architecture;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.core.importer.Location;
import java.util.regex.Pattern;

/**
 * Leaves out the test fixtures of the libraries (ai.helper2.testing, search's Fixtures.kt): test
 * code, though ArchUnit's own test filter only knows test class folders. The rules check production
 * code only, as when the helpers were test classes of one project.
 */
final class DoNotIncludeTestFixtures implements ImportOption {

  private static final Pattern TEST_FIXTURES =
      Pattern.compile(".*(/build/classes/[^/]+/testFixtures/|-test-fixtures\\.jar).*");

  private static final ImportOption TESTS = ImportOption.Predefined.DO_NOT_INCLUDE_TESTS;

  @Override
  public boolean includes(Location location) {
    return TESTS.includes(location) && !location.matches(TEST_FIXTURES);
  }
}
