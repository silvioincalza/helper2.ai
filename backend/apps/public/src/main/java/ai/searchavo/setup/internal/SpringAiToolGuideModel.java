package ai.searchavo.setup.internal;

import ai.searchavo.platform.llm.BudgetedChat;
import ai.searchavo.platform.llm.CallOptions;
import ai.searchavo.platform.llm.CutAnswerWatch;
import ai.searchavo.platform.llm.DailyBudget;
import ai.searchavo.platform.llm.LlmPolicies;
import ai.searchavo.platform.llm.ModelAnswers;
import ai.searchavo.platform.llm.ModelCallMetrics;
import ai.searchavo.platform.llm.ModelErrors;
import ai.searchavo.platform.llm.UntrustedText;
import ai.searchavo.platform.prompt.PromptTemplate;
import ai.searchavo.platform.resilience.CallRefused;
import ai.searchavo.platform.resilience.Resilience;
import ai.searchavo.setup.Markdown;
import ai.searchavo.setup.Material;
import ai.searchavo.setup.ProposedGuide;
import ai.searchavo.setup.ProposedSection;
import ai.searchavo.setup.ProposedStep;
import ai.searchavo.setup.StepClaim;
import ai.searchavo.setup.ToolGuideModel;
import ai.searchavo.setup.ToolGuideUnavailable;
import ai.searchavo.setup.ToolGuideUnavailable.Reason;
import ai.searchavo.setup.ToolGuideValidator;
import ai.searchavo.setup.ToolRef;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.AdvisorParams;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.converter.BeanOutputConverter;

class SpringAiToolGuideModel implements ToolGuideModel {

  private static final Logger log = LoggerFactory.getLogger(SpringAiToolGuideModel.class);

  static final int MAX_DOCUMENT_CHARS = 14_000;

  static final int MAX_TOKENS = 8192;

  static final int TEXT_TARGET_CHARS = 200;

  static final int QUOTE_TARGET_CHARS = 300;

  static final int MAX_NAME_CHARS = 200;

  static final int VERIFY_MAX_TOKENS = 4096;

  private static final String WRITE_STEP = "tool-guide";

  private static final String VERIFY_STEP = "tool-guide-verify";

  record Answer(List<Section> sections) {
    record Section(String kind, List<Step> steps) {}

    record Step(String text, String source, String quote, String code, String language) {}
  }

  record Verdicts(List<Verdict> verdicts) {
    record Verdict(String key, Boolean supported) {}
  }

  static String verifySystemPrompt() {
    return PromptTemplate.fromClasspath("setup/verify-steps.txt").render(Map.of());
  }

  static String systemPrompt() {
    return PromptTemplate.fromClasspath("setup/tool-guide.txt")
        .render(
            Map.of(
                "textTargetChars", TEXT_TARGET_CHARS,
                "minQuoteChars", ToolGuideValidator.MIN_QUOTE_CHARS,
                "minQuoteWords", ToolGuideValidator.MIN_QUOTE_WORDS,
                "minLineQuoteWords", ToolGuideValidator.MIN_LINE_QUOTE_WORDS,
                "quoteTargetChars", QUOTE_TARGET_CHARS,
                "maxCodeLines", ToolGuideValidator.MAX_CODE_LINES,
                "maxSteps", ToolGuideValidator.MAX_STEPS_PER_SECTION));
  }

  private final ChatClient chat;
  private final String system;
  private final String verifySystem;
  private final String model;
  private final boolean nativeSchema;
  private final BudgetedChat calls;

  SpringAiToolGuideModel(
      ChatModel chatModel,
      String model,
      boolean nativeSchema,
      DailyBudget budget,
      ModelCallMetrics metrics,
      Resilience resilience) {
    this.chat = ChatClient.builder(chatModel).build();
    this.system = systemPrompt();
    this.verifySystem = verifySystemPrompt();
    this.model = model;
    this.nativeSchema = nativeSchema;
    this.calls = new BudgetedChat(LlmPolicies.CHAT, budget, metrics, resilience);
  }

  @Override
  public String getModel() {
    return model;
  }

  @Override
  public String getConfigFingerprint() {
    return String.join(
        "\u0000",
        system,
        verifySystem,
        model,
        "nativeSchema=" + nativeSchema,
        "maxDocumentChars=" + MAX_DOCUMENT_CHARS);
  }

  @Override
  public ProposedGuide write(ToolRef ref, List<Material> materials) {
    String docs =
        materials.stream()
            .map(m ->
                "<document key=\""
                    + m.getKey()
                    + "\" kind=\""
                    + m.getKind()
                    + "\">\n"
                    + UntrustedText.untrusted(
                        Markdown.INSTANCE.relevant(m.getText(), MAX_DOCUMENT_CHARS),
                        MAX_DOCUMENT_CHARS + 200)
                    + "\n</document>")
            .collect(Collectors.joining("\n"));
    Answer answer = call(WRITE_STEP, system, user(ref, docs), Answer.class, MAX_TOKENS);
    if (log.isDebugEnabled()) {
      log.debug("tool guide proposal for {}: {}", ref.getTitle(), answer);
    }
    if (answer == null || answer.sections() == null) {
      return new ProposedGuide(List.of());
    }
    return new ProposedGuide(
        answer.sections().stream()
            .filter(Objects::nonNull)
            .map(s ->
                new ProposedSection(
                    s.kind() == null ? "" : s.kind(),
                    s.steps() == null
                        ? List.of()
                        : s.steps().stream()
                            .filter(Objects::nonNull)
                            .map(st ->
                                new ProposedStep(
                                    nz(st.text()),
                                    nz(st.source()),
                                    nz(st.quote()),
                                    st.code(),
                                    st.language()))
                            .toList()))
            .toList());
  }

  static String user(ToolRef ref, String docs) {
    return "<tool-name>\n"
        + UntrustedText.untrusted(ref.getTitle(), MAX_NAME_CHARS)
        + "\n</tool-name>\n\nTYPE: "
        + ref.getType()
        + "\n\nDOCUMENTS:\n"
        + docs;
  }

  @Override
  public Set<String> confirm(ToolRef ref, List<StepClaim> steps) {
    if (steps.isEmpty()) {
      return Set.of();
    }
    try {
      Verdicts answer =
          call(
              VERIFY_STEP, verifySystem, verifyUser(ref, steps), Verdicts.class, VERIFY_MAX_TOKENS);
      return supportedKeys(answer, steps);
    } catch (ToolGuideUnavailable e) {
      log.info("tool guide step texts not checked: {}", e.getReason());
      throw e;
    }
  }

  static String verifyUser(ToolRef ref, List<StepClaim> steps) {
    return "<tool-name>\n"
        + UntrustedText.untrusted(ref.getTitle(), MAX_NAME_CHARS)
        + "\n</tool-name>\n\nSTEPS:\n"
        + steps.stream().map(SpringAiToolGuideModel::step).collect(Collectors.joining("\n"));
  }

  private static String step(StepClaim s) {
    return "<step key=\""
        + s.getKey()
        + "\">\n<text>"
        + UntrustedText.untrusted(s.getText(), ToolGuideValidator.MAX_TEXT_CHARS)
        + "</text>\n<quote>"
        + UntrustedText.untrusted(s.getQuote(), ToolGuideValidator.MAX_QUOTE_CHARS)
        + "</quote>"
        + (s.getCode() == null
            ? ""
            : "\n<command>\n"
                + UntrustedText.untrusted(s.getCode(), MAX_COMMAND_CHARS)
                + "\n</command>")
        + "\n</step>";
  }

  static final int MAX_COMMAND_CHARS = 4000;

  private static Set<String> supportedKeys(Verdicts answer, List<StepClaim> steps) {
    if (answer == null || answer.verdicts() == null) {
      return Set.of();
    }
    Set<String> asked = steps.stream().map(StepClaim::getKey).collect(Collectors.toSet());
    Set<String> refused =
        answer.verdicts().stream()
            .filter(v -> v != null && v.key() != null && !Boolean.TRUE.equals(v.supported()))
            .map(v -> v.key().strip())
            .collect(Collectors.toSet());
    return answer.verdicts().stream()
        .filter(v -> v != null && v.key() != null && Boolean.TRUE.equals(v.supported()))
        .map(v -> v.key().strip())
        .filter(k -> asked.contains(k) && !refused.contains(k))
        .collect(Collectors.toSet());
  }

  private <T> T call(String step, String systemText, String user, Class<T> type, int maxTokens) {
    String version = ModelCallMetrics.promptVersion(systemText);
    BeanOutputConverter<T> converter =
        new BeanOutputConverter<>(type, null, text -> ModelAnswers.jsonObject(text).orElse(""));
    for (int attempt = 1; attempt <= 2; attempt++) {
      CutAnswerWatch watch = new CutAnswerWatch();
      try {
        return calls.entity(
            new BudgetedChat.Call(step, model, version),
            () -> new ToolGuideUnavailable(Reason.BUDGET),
            () -> {
              var prompt =
                  chat.prompt()
                      .options(CallOptions.of(model, maxTokens))
                      .advisors(watch)
                      .system(systemText)
                      .user(user);
              if (nativeSchema) {
                prompt = prompt.advisors(AdvisorParams.ENABLE_NATIVE_STRUCTURED_OUTPUT);
              }
              return prompt.call().responseEntity(converter);
            });
      } catch (ToolGuideUnavailable e) {
        throw e;
      } catch (CallRefused e) {
        log.info("{} model call refused: {} is {}", step, e.policy(), e.reason());
        throw new ToolGuideUnavailable(Reason.PROVIDER);
      } catch (RuntimeException e) {
        log.warn(
            "{} model call failed (attempt {} of 2, model {}): {} ({})",
            step,
            attempt,
            model,
            ModelErrors.category(e),
            e.getClass().getSimpleName());
        if (ModelErrors.isProviderError(e)) {
          throw new ToolGuideUnavailable(Reason.PROVIDER);
        }
        if (watch.answerWasCut()) {
          log.warn(
              "{} answer was cut at {} tokens: the same call is not repeated", step, maxTokens);
          break;
        }
      }
    }
    throw new ToolGuideUnavailable(Reason.UNREADABLE);
  }

  private static String nz(String s) {
    return s == null ? "" : s;
  }
}
