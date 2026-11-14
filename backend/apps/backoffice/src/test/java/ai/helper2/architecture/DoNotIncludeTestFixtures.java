package ai.helper2.architecture;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.core.importer.Location;
import java.util.regex.Pattern;

final class DoNotIncludeTestFixtures implements ImportOption {

  private static final Pattern TEST_FIXTURES =
      Pattern.compile(".*(/build/classes/[^/]+/testFixtures/|-test-fixtures\\.jar).*");

  private static final ImportOption TESTS = ImportOption.Predefined.DO_NOT_INCLUDE_TESTS;

  @Override
  public boolean includes(Location location) {
    return TESTS.includes(location) && !location.matches(TEST_FIXTURES);
  }
}
