package ai.helper2.sources.classifier;

import ai.helper2.platform.llm.BudgetedChat;
import ai.helper2.platform.llm.DailyBudget;
import ai.helper2.platform.llm.LlmPolicies;
import ai.helper2.platform.llm.ModelAnswers;
import ai.helper2.platform.llm.ModelCallMetrics;
import ai.helper2.platform.llm.TextCall;
import ai.helper2.platform.prompt.PromptTemplate;
import ai.helper2.platform.resilience.Resilience;
import ai.helper2.scout.LlmUnavailable;
import ai.helper2.shared.ResourceType;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.apache.commons.lang3.EnumUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.ai.chat.model.ChatModel;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

public final class SpringAiRepoClassifier implements RepoClassifier {

  static final Set<ResourceType> ALLOWED =
      EnumSet.of(
          ResourceType.MCP_SERVER,
          ResourceType.SKILL,
          ResourceType.AGENT,
          ResourceType.PROMPT,
          ResourceType.MODEL,
          ResourceType.DATASET,
          ResourceType.PRODUCT,
          ResourceType.LIBRARY,
          ResourceType.ARTICLE,
          ResourceType.AWESOME_LIST,
          ResourceType.OTHER);

  static String systemPrompt() {
    return PromptTemplate.fromClasspath("sources/classify-repos.txt")
        .render(
            Map.of(
                "allowedTypes",
                ALLOWED.stream().map(Enum::name).collect(Collectors.joining(", "))));
  }

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final int MAX_FIELD = 200;
  private static final String STEP = "classify";

  private final TextCall call;

  public SpringAiRepoClassifier(
      ChatModel chatModel,
      String model,
      int maxTokens,
      DailyBudget budget,
      ModelCallMetrics metrics,
      Resilience resilience) {
    this.call =
        new TextCall(
            chatModel,
            new BudgetedChat(LlmPolicies.CHAT, budget, metrics, resilience),
            STEP,
            model,
            maxTokens,
            systemPrompt());
  }

  @Override
  public String name() {
    return call.model();
  }

  @Override
  public Map<String, Verdict> classify(List<Facts> repos) {
    if (repos.isEmpty()) {
      return Map.of();
    }
    String user =
        repos.stream().map(SpringAiRepoClassifier::line).collect(Collectors.joining("\n"));
    String answer = call.answer(user, "daily classifier budget used up", LlmUnavailable::new);
    return parse(answer, repos.stream().map(Facts::id).collect(Collectors.toSet()));
  }

  static String line(Facts f) {
    return f.id()
        + ": "
        + f.fullName()
        + " | section: "
        + untrusted(f.listSection())
        + " | topics: "
        + untrusted(String.join(",", f.topics()))
        + " | description: "
        + untrusted(f.description());
  }

  static Map<String, Verdict> parse(String answer, Set<String> ids) {
    Map<String, Verdict> out = new HashMap<>();
    Optional<String> json = ModelAnswers.jsonObject(answer);
    if (json.isEmpty()) {
      return out;
    }
    JsonNode root;
    try {
      root = JSON.readTree(json.get());
    } catch (JacksonException e) {
      return out;
    }
    for (JsonNode item : root.path("items")) {
      String id = item.path("id").asString("");
      JsonNode ai = item.path("ai");
      ResourceType type = typeOf(item.path("type").asString(""));
      if (ids.contains(id) && ai.isBoolean() && type != null) {
        out.put(id, new Verdict(ai.asBoolean(), type));
      }
    }
    return out;
  }

  private static ResourceType typeOf(String value) {
    ResourceType type = EnumUtils.getEnum(ResourceType.class, StringUtils.strip(value));
    return type != null && ALLOWED.contains(type) ? type : null;
  }

  private static String untrusted(String text) {
    if (text == null) {
      return "";
    }
    String flat = text.replaceAll("[\\r\\n|]+", " ").strip();
    return flat.length() <= MAX_FIELD ? flat : flat.substring(0, MAX_FIELD);
  }
}
