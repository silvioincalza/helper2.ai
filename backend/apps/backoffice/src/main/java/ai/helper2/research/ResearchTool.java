package ai.helper2.research;

import org.jspecify.annotations.Nullable;

public interface ResearchTool {

  String name();

  String description();

  String parameters();

  Output call(String arguments);

  record Output(String text, @Nullable String url) {

    public static Output error(String message) {
      return new Output("error: " + message, null);
    }
  }
}
