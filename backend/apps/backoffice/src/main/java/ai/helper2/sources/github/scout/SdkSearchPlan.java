package ai.helper2.sources.github.scout;

import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;

public record SdkSearchPlan(List<Query> queries) {

  public record LanguageFloor(String language, int minStars) {

    public static LanguageFloor parse(String entry) {
      int colon = entry.lastIndexOf(':');
      return new LanguageFloor(
          entry.substring(0, colon).strip(), Integer.parseInt(entry.substring(colon + 1).strip()));
    }
  }

  public record Query(String text, @Nullable String language) {}

  public SdkSearchPlan {
    queries = List.copyOf(queries);
  }

  public static SdkSearchPlan of(
      List<String> topics, List<LanguageFloor> languages, List<String> orgs, int orgMinStars) {
    List<Query> queries = new ArrayList<>();
    for (LanguageFloor floor : languages) {
      for (String topic : topics) {
        queries.add(
            new Query(
                "topic:"
                    + topic
                    + " language:\""
                    + floor.language()
                    + "\" stars:>="
                    + floor.minStars()
                    + " fork:false archived:false",
                floor.language()));
      }
    }
    for (String org : orgs) {
      queries.add(
          new Query("org:" + org + " stars:>=" + orgMinStars + " fork:false archived:false", null));
    }
    return new SdkSearchPlan(queries);
  }

  public List<Query> slice(int from, int count) {
    int start = Math.min(Math.max(from, 0), queries.size());
    return queries.subList(start, Math.min(queries.size(), start + count));
  }

  public int size() {
    return queries.size();
  }
}
