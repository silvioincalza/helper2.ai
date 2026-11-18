package ai.helper2.research;

import org.jspecify.annotations.Nullable;

public interface ResearchTool {

  String name();

  String description();

  String parameters();

  Output call(String arguments);

  default int maxCallsPerRun() {
    return Integer.MAX_VALUE;
  }

  record Output(String text, @Nullable String url) {

    public static Output error(String message) {
      return new Output("error: " + message, null);
    }
  }
}
