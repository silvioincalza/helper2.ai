package ai.helper2.testing;

import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

public final class ConfigBinding {

  private ConfigBinding() {}

  public static ApplicationContextRunner of(Class<?> configuration) {
    return new ApplicationContextRunner()
        .withInitializer(new ConfigDataApplicationContextInitializer())
        .withUserConfiguration(configuration);
  }
}
