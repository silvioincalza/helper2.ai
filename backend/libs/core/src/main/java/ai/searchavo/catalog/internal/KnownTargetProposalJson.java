package ai.searchavo.catalog.internal;

import ai.searchavo.catalog.KnownTarget;
import ai.searchavo.catalog.KnownTargetCandidates;
import ai.searchavo.catalog.KnownTargetCandidates.Proposal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.apache.commons.lang3.EnumUtils;
import org.jspecify.annotations.Nullable;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class KnownTargetProposalJson {

  private final ObjectMapper json;

  KnownTargetProposalJson(ObjectMapper json) {
    this.json = json;
  }

  Optional<Proposal> read(@Nullable String details) {
    if (details == null || details.isBlank()) {
      return Optional.empty();
    }
    try {
      return proposal(json.readTree(details));
    } catch (JacksonException e) {
      return Optional.empty();
    }
  }

  private static Optional<Proposal> proposal(JsonNode d) {
    KnownTarget.Kind kind = kind(d.path("kind"));
    if (!d.path("name").isString()
        || kind == null
        || !ownerRule(d.path("rule"))
        || !d.path("count").isNumber()) {
      return Optional.empty();
    }
    return Optional.of(
        new Proposal(
            d.path("name").asString(),
            kind,
            KnownTargetCandidates.OWNER_RULE,
            d.path("count").asInt(),
            exampleTitles(d.path("examples")),
            texts(d.path("otherNames")),
            d.path("edited").isBoolean() && d.path("edited").asBoolean()));
  }

  private static KnownTarget.@Nullable Kind kind(JsonNode kind) {
    return kind.isString() ? EnumUtils.getEnum(KnownTarget.Kind.class, kind.asString()) : null;
  }

  private static boolean ownerRule(JsonNode rule) {
    return rule.isString() && KnownTargetCandidates.OWNER_RULE.equals(rule.asString());
  }

  private static List<String> exampleTitles(JsonNode examples) {
    List<String> titles = new ArrayList<>();
    if (examples.isArray()) {
      examples.forEach(e -> {
        if (e.path("title").isString()) {
          titles.add(e.path("title").asString());
        }
      });
    }
    return List.copyOf(titles);
  }

  private static List<String> texts(JsonNode values) {
    List<String> texts = new ArrayList<>();
    if (values.isArray()) {
      values.forEach(v -> {
        if (v.isString()) {
          texts.add(v.asString());
        }
      });
    }
    return List.copyOf(texts);
  }
}
