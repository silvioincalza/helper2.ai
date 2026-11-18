package ai.helper2.research;

import java.util.List;

public record Evidence(String id, String tool, String url, String text, List<String> recordOf) {

  public Evidence {
    recordOf = List.copyOf(recordOf);
  }

  public Evidence(String id, String tool, String url, String text) {
    this(id, tool, url, text, List.of());
  }
}
