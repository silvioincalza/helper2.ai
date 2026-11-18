package ai.helper2.research;

import java.util.List;
import org.jspecify.annotations.Nullable;

public interface ResearchTool {

  String name();

  String description();

  String parameters();

  Output call(String arguments);

  default int maxCallsPerRun() {
    return Integer.MAX_VALUE;
  }

  record Output(String text, @Nullable String url, List<String> recordOf) {

    public Output {
      recordOf = List.copyOf(recordOf);
    }

    public Output(String text, @Nullable String url) {
      this(text, url, List.of());
    }

    public static Output error(String message) {
      return new Output("error: " + message, null);
    }
  }
}
