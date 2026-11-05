package ai.helper2.testing;

import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * The base of every settings binding test (ADR 0007): a small context that loads the real {@code
 * application.yaml}, so a test sees the one set of defaults the app runs with, and the {@code H2_}
 * mapping next to them.
 *
 * <p>A test passes a configuration that enables only the record it checks:
 *
 * <pre>{@code
 * @Configuration(proxyBeanMethods = false)
 * @EnableConfigurationProperties(SetupProperties.class)
 * static class Binding {}
 *
 * ConfigBinding.of(Binding.class).run(c -> ...);
 * ConfigBinding.of(Binding.class).withSystemProperties("H2_SETUP_HOURLY_FETCHES=5").run(c -> ...);
 * }</pre>
 *
 * <p>A system property with the real {@code H2_} name stands in for the environment variable: the
 * yaml placeholders resolve both the same way, so it proves the mapping. The Gradle test task
 * already replaces the {@code .env} import, so a developer's keys never reach these tests.
 */
public final class ConfigBinding {

  private ConfigBinding() {}

  /** A runner with {@code application.yaml} loaded and the given configuration registered. */
  public static ApplicationContextRunner of(Class<?> configuration) {
    return new ApplicationContextRunner()
        .withInitializer(new ConfigDataApplicationContextInitializer())
        .withUserConfiguration(configuration);
  }
}
