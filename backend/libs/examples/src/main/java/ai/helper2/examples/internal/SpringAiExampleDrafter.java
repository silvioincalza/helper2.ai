package ai.helper2.examples.internal;

import ai.helper2.examples.DraftSource;
import ai.helper2.examples.DraftsUnavailable;
import ai.helper2.examples.DraftsUnavailable.Reason;
import ai.helper2.examples.ExampleDrafter;
import ai.helper2.examples.ProposedDraft;
import ai.helper2.platform.llm.CallOptions;
import ai.helper2.platform.llm.DailyBudget;
import ai.helper2.platform.llm.LlmPolicies;
import ai.helper2.platform.llm.ModelAnswers;
import ai.helper2.platform.llm.ModelCallMetrics;
import ai.helper2.platform.llm.ModelErrors;
import ai.helper2.platform.llm.UntrustedText;
import ai.helper2.platform.prompt.PromptTemplate;
import ai.helper2.platform.resilience.CallRefused;
import ai.helper2.platform.resilience.Resilience;
import ai.helper2.shared.AudiencePath;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.converter.BeanOutputConverter;

/**
 * Spring AI adapter for example drafts (ADR 0049 §6): one structured-output call with public facts
 * about catalog resources, inside the {@code llm-chat} circuit, one unit of the drafts' own daily
 * budget per attempt. DraftRules decides what a person sees.
 */
class SpringAiExampleDrafter implements ExampleDrafter {

  private static final Logger log = LoggerFactory.getLogger(SpringAiExampleDrafter.class);

  static final int MAX_TOKENS = 2048;
  static final int MAX_DESCRIPTION_CHARS = 300;

  record Answer(@Nullable List<Draft> drafts) {
    record Draft(
        @Nullable String resourceId,
        @Nullable String label,
        @Nullable String query,
        @Nullable String path) {}
  }

  private final ChatClient chat;
  private final String system;
  private final String version;
  private final String model;
  private final DailyBudget budget;
  private final ModelCallMetrics metrics;
  private final Resilience resilience;

  SpringAiExampleDrafter(
      ChatModel chatModel,
      String model,
      DailyBudget budget,
      ModelCallMetrics metrics,
      Resilience resilience) {
    resilience.require(LlmPolicies.CHAT);
    this.chat = ChatClient.builder(chatModel).build();
    this.system = PromptTemplate.fromClasspath("examples/draft.txt").render(Map.of());
    this.version = ModelCallMetrics.promptVersion(system);
    this.model = model;
    this.budget = budget;
    this.metrics = metrics;
    this.resilience = resilience;
  }

  @Override
  public String getModel() {
    return model;
  }

  @Override
  public List<ProposedDraft> draft(List<DraftSource> sources) {
    Answer answer = call(user(sources));
    if (answer == null || answer.drafts() == null) {
      return List.of();
    }
    return answer.drafts().stream()
        .filter(Objects::nonNull)
        .map(d -> new ProposedDraft(uuid(d.resourceId()), d.label(), d.query(), path(d.path())))
        .toList();
  }

  /** Public facts only, each fenced as untrusted text: a description is data, not instructions. */
  static String user(List<DraftSource> sources) {
    return sources.stream()
        .map(s ->
            "<resource id=\""
                + s.getResourceId()
                + "\" type=\""
                + s.getType()
                + "\" audience=\""
                + s.getAudience()
                + "\">\n"
                + UntrustedText.untrusted(s.getTitle(), 200)
                + (s.getDescription() == null
                    ? ""
                    : "\n" + UntrustedText.untrusted(s.getDescription(), MAX_DESCRIPTION_CHARS))
                + "\n</resource>")
        .collect(Collectors.joining("\n"));
  }

  /**
   * One retry, only for an answer we could not use. The budget unit is taken inside the circuit, so
   * a call the open circuit refuses costs nothing.
   */
  private @Nullable Answer call(String user) {
    BeanOutputConverter<Answer> converter =
        new BeanOutputConverter<>(
            Answer.class, null, text -> ModelAnswers.jsonObject(text).orElse(""));
    for (int attempt = 1; attempt <= 2; attempt++) {
      try {
        return resilience.call(
            LlmPolicies.CHAT,
            () -> {
              if (!budget.tryAcquire()) {
                throw new DraftsUnavailable(Reason.BUDGET);
              }
              var spec =
                  chat.prompt()
                      .options(CallOptions.of(model, MAX_TOKENS, false))
                      .system(system)
                      .user(user)
                      .call();
              return metrics.observe(
                  "example-drafts",
                  model,
                  version,
                  () -> {
                    var response = spec.responseEntity(converter);
                    metrics.tokens("example-drafts", version, response.response());
                    return response.entity();
                  });
            });
      } catch (DraftsUnavailable e) {
        throw e;
      } catch (CallRefused e) {
        log.info("example draft call refused: {} is {}", e.policy(), e.reason());
        throw new DraftsUnavailable(Reason.PROVIDER);
      } catch (RuntimeException e) {
        log.warn(
            "example draft call failed (attempt {} of 2, model {}): {} ({})",
            attempt,
            model,
            ModelErrors.category(e),
            e.getClass().getSimpleName());
        if (ModelErrors.isProviderError(e)) {
          throw new DraftsUnavailable(Reason.PROVIDER);
        }
      }
    }
    throw new DraftsUnavailable(Reason.UNREADABLE);
  }

  private static @Nullable UUID uuid(@Nullable String id) {
    try {
      return id == null ? null : UUID.fromString(id.strip());
    } catch (IllegalArgumentException e) {
      return null;
    }
  }

  private static @Nullable AudiencePath path(@Nullable String path) {
    try {
      return path == null ? null : AudiencePath.valueOf(path.strip());
    } catch (IllegalArgumentException e) {
      return null;
    }
  }
}
