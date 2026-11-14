package ai.helper2.scout.internal;

import ai.helper2.platform.llm.BudgetedChat;
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
import ai.helper2.scout.Citation;
import ai.helper2.scout.EnrichmentInput;
import ai.helper2.scout.EnrichmentModel;
import ai.helper2.scout.EnrichmentProposal;
import ai.helper2.scout.EvidenceValidator;
import ai.helper2.scout.FieldsByType;
import ai.helper2.scout.LlmUnavailable;
import ai.helper2.scout.Material;
import ai.helper2.scout.ModelAnswerInvalid;
import ai.helper2.scout.ProposedClaim;
import ai.helper2.scout.ProposedField;
import ai.helper2.scout.ProposedRelationship;
import ai.helper2.scout.UntrustedPageText;
import ai.helper2.shared.ResourceType;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.converter.BeanOutputConverter;

class SpringAiEnrichmentModel implements EnrichmentModel {
  static final String PROMPT_VERSION = "enrich-v10";

  static final int MAX_MATERIAL_CHARS = UntrustedPageText.MAX_PAGE_CHARS;

  static final String PROMPT = "scout/enrich.txt";

  static String systemPrompt() {
    return PromptTemplate.fromClasspath(PROMPT)
        .render(
            Map.of(
                "allowedValues",
                EvidenceValidator.allowedValues(),
                "minExcerptChars",
                EvidenceValidator.MIN_EXCERPT_CHARS,
                "maxExcerptChars",
                EvidenceValidator.MAX_EXCERPT_CHARS,
                "maxSummaryClaims",
                EvidenceValidator.MAX_SUMMARY_CLAIMS,
                "maxUseCases",
                EvidenceValidator.MAX_USE_CASES,
                "maxSelfDeclaredRelationships",
                EvidenceValidator.MAX_SELF_DECLARED_RELATIONSHIPS,
                "maxAbout",
                FieldsByType.MAX_ABOUT));
  }

  record Output(List<Field> fields, List<Rel> relationships, List<Claim> summary) {
    record Cite(String evidenceId, String excerpt) {}

    record Field(String field, String value, List<Cite> citations) {}

    record Rel(String kind, String target, String targetKind, List<Cite> citations) {}

    record Claim(String text, List<Cite> citations) {}
  }

  private final BeanOutputConverter<Output> converter = new BeanOutputConverter<>(Output.class);
  private final ChatClient chat;
  private final String system;
  private final String version;
  private final String provider;
  private final String model;
  private final int maxTokens;
  private final BudgetedChat calls;

  SpringAiEnrichmentModel(
      ChatModel chatModel,
      String provider,
      String model,
      int maxTokens,
      DailyBudget budget,
      ModelCallMetrics metrics,
      Resilience resilience) {
    this.chat = ChatClient.builder(chatModel).build();
    this.system = systemPrompt();
    this.version = ModelCallMetrics.promptVersion(system);
    this.provider = provider;
    this.model = model;
    this.maxTokens = maxTokens;
    this.calls = new BudgetedChat(LlmPolicies.CHAT, budget, metrics, resilience);
  }

  @Override
  public String getProvider() {
    return provider;
  }

  @Override
  public String getModel() {
    return model;
  }

  @Override
  public String getPromptVersion() {
    return PROMPT_VERSION;
  }

  @Override
  public EnrichmentProposal propose(EnrichmentInput input) {
    String material =
        input.getMaterials().stream()
            .map((Material m) ->
                "<material evidenceId=\""
                    + m.getEvidenceId()
                    + "\" kind=\""
                    + m.getKind()
                    + "\" url=\""
                    + m.getSourceUrl()
                    + "\">\n"
                    + UntrustedText.untrusted(m.getText(), MAX_MATERIAL_CHARS)
                    + "\n</material>")
            .collect(Collectors.joining("\n"));
    String answer =
        call(UntrustedText.untrusted(input.getResourceTitle(), 300), input.getType(), material);
    Output out = parse(answer);
    if (out == null) {
      return new EnrichmentProposal(List.of(), List.of(), List.of());
    }
    return new EnrichmentProposal(
        nn(out.fields()).stream()
            .map(f -> new ProposedField(s(f.field()), s(f.value()), cites(f.citations())))
            .toList(),
        nn(out.relationships()).stream()
            .map(r ->
                new ProposedRelationship(
                    s(r.kind()), s(r.target()), cites(r.citations()), r.targetKind()))
            .toList(),
        nn(out.summary()).stream()
            .map(c -> new ProposedClaim(s(c.text()), cites(c.citations())))
            .toList());
  }

  private String call(String title, ResourceType type, String material) {
    try {
      return calls.text(
          new BudgetedChat.Call("enrich", model, version),
          () -> new LlmUnavailable("daily enrichment budget used up"),
          () ->
              chat.prompt()
                  .options(CallOptions.of(model, maxTokens))
                  .system(system)
                  .user(u ->
                      u.text(
                              "Resource: {title}\nType: {type}\n"
                                  + "Fields for this type: {fields}\n"
                                  + "Relationship kinds for this type: {kinds}\n\n"
                                  + "SOURCE MATERIAL:\n{material}\n\n{format}")
                          .param("title", title)
                          .param("type", type.name())
                          .param("fields", String.join(", ", FieldsByType.fieldsFor(type)))
                          .param("kinds", String.join(", ", FieldsByType.kindsFor(type)))
                          .param("material", material)
                          .param("format", converter.getFormat()))
                  .call()
                  .chatResponse());
    } catch (LlmUnavailable e) {
      throw e;
    } catch (CallRefused e) {
      throw new LlmUnavailable("model call refused: " + e.policy() + " circuit is " + e.reason());
    } catch (RuntimeException e) {
      String what = e.getClass().getSimpleName() + ": " + abbreviate(e.getMessage());
      if (ModelErrors.isTransient(e)) {
        throw new LlmUnavailable("provider unavailable: " + what, e);
      }
      throw new ModelAnswerInvalid("provider rejected the call: " + what, e);
    }
  }

  Output parse(String answer) {
    if (answer == null || answer.isBlank()) {
      throw new ModelAnswerInvalid("empty answer");
    }
    String json =
        ModelAnswers.jsonObject(answer)
            .orElseThrow(() ->
                new ModelAnswerInvalid(
                    "answer contains no JSON object (" + answer.length() + " chars)"));
    try {
      return converter.convert(json);
    } catch (RuntimeException e) {
      throw new ModelAnswerInvalid(
          "answer is not the requested JSON ("
              + answer.length()
              + " chars): "
              + e.getClass().getSimpleName(),
          e);
    }
  }

  private static String abbreviate(String message) {
    if (message == null) {
      return "";
    }
    String oneLine = message.replaceAll("\\s+", " ");
    return oneLine.length() <= 160 ? oneLine : oneLine.substring(0, 157) + "...";
  }

  private static List<Citation> cites(List<Output.Cite> c) {
    return nn(c).stream().map(x -> new Citation(s(x.evidenceId()), s(x.excerpt()))).toList();
  }

  private static <T> List<T> nn(List<T> l) {
    return l == null ? List.of() : l;
  }

  private static String s(String v) {
    return v == null ? "" : v;
  }
}
