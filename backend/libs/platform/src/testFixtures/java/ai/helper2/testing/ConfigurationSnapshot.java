package ai.helper2.testing;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

public final class ConfigurationSnapshot {

  public static final String NO_PROFILE = "";
  public static final String EVERY_PROFILE = "dev,oauth-github,llm-nebius,demo";

  private final Path folder;

  public ConfigurationSnapshot(Path folder) {
    this.folder = folder;
  }

  public List<String> now(String profiles) {
    List<String> lines = lines(ResolvedConfiguration.ofClasspath(profiles).settings());
    if (profiles.equals(NO_PROFILE)) {
      return lines;
    }
    List<String> withoutProfiles = now(NO_PROFILE);
    return lines.stream().filter(line -> !withoutProfiles.contains(line)).toList();
  }

  public List<String> recorded(String profiles) {
    Path file = folder.resolve(fileName(profiles));
    try {
      if (Files.notExists(file)) {
        Files.createDirectories(folder);
        Files.write(file, now(profiles));
        throw new AssertionError("recorded " + file + ": read it, commit it and run again");
      }
      return Files.readAllLines(file);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static String fileName(String profiles) {
    if (profiles.equals(NO_PROFILE)) {
      return "no-profile.txt";
    }
    return profiles.equals(EVERY_PROFILE) ? "every-profile.txt" : profiles + ".txt";
  }

  private static List<String> lines(Map<String, String> settings) {
    return settings.entrySet().stream().map(e -> e.getKey() + " = " + e.getValue()).toList();
  }
}
