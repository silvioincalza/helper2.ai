package ai.helper2.admin.usecase;

import ai.helper2.catalog.KnownTarget;
import java.util.List;
import org.jspecify.annotations.Nullable;

public sealed interface ReviewDetails {

  record Text(String text) implements ReviewDetails {}

  record Candidate(
      String revision,
      String url,
      String title,
      String type,
      List<String> tags,
      @Nullable String snippet,
      List<String> foundBy,
      List<String> edited)
      implements ReviewDetails {}

  record KnownTargetProposal(
      String name,
      KnownTarget.Kind kind,
      String rule,
      int count,
      List<String> examples,
      List<String> otherNames,
      boolean edited)
      implements ReviewDetails {}
}
