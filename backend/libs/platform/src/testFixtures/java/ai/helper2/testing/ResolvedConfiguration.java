package ai.helper2.testing;

import java.util.Map;
import java.util.TreeMap;
import org.springframework.boot.bootstrap.DefaultBootstrapContext;
import org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor;
import org.springframework.boot.env.OriginTrackedMapPropertySource;
import org.springframework.core.env.AbstractEnvironment;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.DefaultResourceLoader;

public final class ResolvedConfiguration {

  private static final String DOCUMENT_ACTIVATION = "spring.config.activate.on-profile";
  private static final String NO_DOTENV = "optional:file:build/no-dotenv[.properties]";

  private final ConfigurableEnvironment environment;

  private ResolvedConfiguration(ConfigurableEnvironment environment) {
    this.environment = environment;
  }

  public static ResolvedConfiguration ofClasspath(String profiles) {
    return load(Map.of("H2_PROFILES", profiles, "helper2.dotenv-import", NO_DOTENV));
  }

  public static ResolvedConfiguration ofFile(String file, String profiles) {
    return load(
        Map.of(
            "H2_PROFILES",
            profiles,
            "helper2.dotenv-import",
            NO_DOTENV,
            "spring.config.location",
            "file:" + file));
  }

  public Map<String, String> settings() {
    Map<String, String> settings = new TreeMap<>();
    for (PropertySource<?> source : environment.getPropertySources()) {
      if (source instanceof OriginTrackedMapPropertySource yaml) {
        for (String key : yaml.getPropertyNames()) {
          if (!key.equals(DOCUMENT_ACTIVATION)) {
            settings.putIfAbsent(key, writtenAndResolved(yaml, key));
          }
        }
      }
    }
    return settings;
  }

  private static ResolvedConfiguration load(Map<String, Object> outside) {
    ConfigurableEnvironment environment = withoutTheMachineEnvironment(outside);
    ConfigDataEnvironmentPostProcessor.applyTo(
        environment, new DefaultResourceLoader(), new DefaultBootstrapContext());
    return new ResolvedConfiguration(environment);
  }

  private static ConfigurableEnvironment withoutTheMachineEnvironment(Map<String, Object> outside) {
    ConfigurableEnvironment environment = new AbstractEnvironment() {};
    environment.getPropertySources().addFirst(new MapPropertySource("outside", outside));
    return environment;
  }

  private String writtenAndResolved(OriginTrackedMapPropertySource yaml, String key) {
    String written = oneLine(String.valueOf(yaml.getProperty(key)));
    String resolved = resolved(key);
    return written.equals(resolved) ? written : written + "  =>  " + resolved;
  }

  private String resolved(String key) {
    try {
      return oneLine(environment.getProperty(key));
    } catch (IllegalArgumentException unresolvedPlaceholder) {
      return "<needs a variable>";
    }
  }

  private static String oneLine(String value) {
    return value.isEmpty() ? "<empty>" : value.replace("\\", "\\\\").replace("\n", "\\n");
  }
}
