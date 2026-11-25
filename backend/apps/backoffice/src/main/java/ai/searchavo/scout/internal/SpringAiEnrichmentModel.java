package ai.searchavo.scout.internal;

import ai.searchavo.platform.llm.BudgetedChat;
import ai.searchavo.platform.llm.DailyBudget;
import ai.searchavo.platform.llm.LlmPolicies;
import ai.searchavo.platform.llm.ModelAnswers;
import ai.searchavo.platform.llm.ModelCallMetrics;
import ai.searchavo.platform.llm.ModelErrors;
import ai.searchavo.platform.llm.TextCall;
import ai.searchavo.platform.llm.UntrustedText;
import ai.searchavo.platform.prompt.PromptTemplate;
import ai.searchavo.platform.resilience.Resilience;
import ai.searchavo.scout.Citation;
import ai.searchavo.scout.EnrichmentInput;
import ai.searchavo.scout.EnrichmentModel;
import ai.searchavo.scout.EnrichmentProposal;
import ai.searchavo.scout.EvidenceValidator;
import ai.searchavo.scout.FieldsByType;
import ai.searchavo.scout.LlmUnavailable;
import ai.searchavo.scout.Material;
import ai.searchavo.scout.ModelAnswerInvalid;
import ai.searchavo.scout.ProposedClaim;
import ai.searchavo.scout.ProposedField;
import ai.searchavo.scout.ProposedRelationship;
import ai.searchavo.scout.UntrustedPageText;
import ai.searchavo.shared.ResourceType;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.converter.BeanOutputConverter;

class SpringAiEnrichmentModel implements EnrichmentModel {

  static final int MAX_MATERIAL_CHARS = UntrustedPageText.MAX_PAGE_CHARS;

  static final String PROMPT = "scout/enrich.txt";

  private static final String STEP = "enrich";

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

  static String promptVersion() {
    return ModelCallMetrics.promptVersion(systemPrompt());
  }

  record Output(List<Field> fields, List<Rel> relationships, List<Claim> summary) {
    record Cite(String evidenceId, String excerpt) {}

    record Field(String field, String value, List<Cite> citations) {}

    record Rel(String kind, String target, String targetKind, List<Cite> citations) {}

    record Claim(String text, List<Cite> citations) {}
  }

  private final BeanOutputConverter<Output> converter = new BeanOutputConverter<>(Output.class);
  private final String provider;
  private final TextCall call;

  SpringAiEnrichmentModel(
      ChatModel chatModel,
      String provider,
      String model,
      int maxTokens,
      DailyBudget budget,
      ModelCallMetrics metrics,
      Resilience resilience) {
    this.provider = provider;
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
  public String getProvider() {
    return provider;
  }

  @Override
  public String getModel() {
    return call.model();
  }

  @Override
  public String getPromptVersion() {
    return call.promptVersion();
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
        ask(UntrustedText.untrusted(input.getResourceTitle(), 300), input.getType(), material);
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

  private String ask(String title, ResourceType type, String material) {
    try {
      return call.answer(
          u ->
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
                  .param("format", converter.getFormat()),
          "daily enrichment budget used up",
          LlmUnavailable::new);
    } catch (LlmUnavailable e) {
      throw e;
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
