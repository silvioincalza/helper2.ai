package ai.helper2.sources.classifier;

import ai.helper2.shared.ResourceType;
import ai.helper2.sources.support.Classification;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Decides, from GitHub facts only, whether a repository found in an awesome list is an AI resource
 * and what kind. Only a gate and a type: descriptions stay the ones GitHub returns, and the Scout's
 * evidence-checked enrichment still runs on what is kept.
 */
public interface RepoClassifier {

    record Facts(String id, String fullName, String description, List<String> topics, String listSection) {
    }

    record Verdict(boolean ai, ResourceType type) {
    }

    /** Verdicts by {@link Facts#id()}. A missing id means "not answered"; the caller falls back. */
    Map<String, Verdict> classify(List<Facts> repos);

    /** Model id, or "heuristic". Logged with each pass. */
    String name();

    /** Keyword rules: used without a model, and for repositories the model did not answer. */
    final class Heuristic implements RepoClassifier {
        @Override
        public Map<String, Verdict> classify(List<Facts> repos) {
            Map<String, Verdict> out = new HashMap<>();
            for (Facts f : repos) {
                out.put(f.id(), new Verdict(Classification.isAiRelated(f.fullName(), f.description(), f.topics()),
                    Classification.typeOf(f.fullName(), f.description(), f.topics(), ResourceType.LIBRARY)));
            }
            return out;
        }

        @Override
        public String name() {
            return "heuristic";
        }
    }
}
