package ai.helper2.sources.classifier;

import ai.helper2.shared.ResourceType;
import ai.helper2.sources.support.Classification;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public interface RepoClassifier {

  record Facts(
      String id, String fullName, String description, List<String> topics, String listSection) {}

  record Verdict(boolean ai, ResourceType type) {}

  Map<String, Verdict> classify(List<Facts> repos);

  String name();

  final class Heuristic implements RepoClassifier {
    @Override
    public Map<String, Verdict> classify(List<Facts> repos) {
      Map<String, Verdict> out = new HashMap<>();
      for (Facts f : repos) {
        out.put(
            f.id(),
            new Verdict(
                Classification.isAiRelated(f.fullName(), f.description(), f.topics()),
                Classification.typeOf(
                    f.fullName(), f.description(), f.topics(), ResourceType.LIBRARY)));
      }
      return out;
    }

    @Override
    public String name() {
      return "heuristic";
    }
  }
}
