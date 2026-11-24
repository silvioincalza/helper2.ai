package ai.searchavo.guide.internal;

import ai.searchavo.guide.ClaimKind;
import ai.searchavo.guide.GuideJudge;
import ai.searchavo.guide.Need;
import ai.searchavo.guide.ReasonCheck;
import ai.searchavo.guide.ReasonClaim;
import ai.searchavo.platform.llm.BudgetedChat;
import ai.searchavo.platform.llm.CallOptions;
import ai.searchavo.platform.llm.DailyBudget;
import ai.searchavo.platform.llm.LlmPolicies;
import ai.searchavo.platform.llm.ModelCallMetrics;
import ai.searchavo.platform.llm.ModelErrors;
import ai.searchavo.platform.resilience.Resilience;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Executor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.evaluation.FactCheckingEvaluator;
import org.springframework.ai.chat.evaluation.RelevancyEvaluator;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.document.Document;
import org.springframework.ai.evaluation.EvaluationRequest;
import org.springframework.ai.evaluation.Evaluator;

final class SpringAiGuideJudge implements GuideJudge {

  static final String METER = "searchavo.guide.judge";

  static final int ANSWER_TOKENS = 16;

  private static final org.slf4j.Logger log =
      org.slf4j.LoggerFactory.getLogger(SpringAiGuideJudge.class);

  enum Evaluation {
    FACT,
    RELEVANCE;

    final String label = name().toLowerCase(Locale.ROOT);

    Evaluator evaluator(ChatClient.Builder chat) {
      return this == FACT
          ? FactCheckingEvaluator.builder(chat).build()
          : RelevancyEvaluator.builder().chatClientBuilder(chat).build();
    }

    EvaluationRequest request(Need need, ReasonClaim claim) {
      return this == FACT
          ? new EvaluationRequest(
              List.of(document(SpringAiGuideModel.evidence(claim))),
              SpringAiGuideModel.untrusted(claim.getReason()))
          : new EvaluationRequest(
              SpringAiGuideModel.need(need),
              List.of(document(SpringAiGuideModel.untrusted(claim.getText()))),
              SpringAiGuideModel.untrusted(claim.getReason()));
    }

    String step() {
      return "judge-" + label;
    }
  }

  enum Verdict {
    PASS,
    FAIL,
    UNCLEAR,
    ERROR,
    SKIPPED;

    final String label = name().toLowerCase(Locale.ROOT);
  }

  private final ChatModel chatModel;
  private final String model;
  private final BudgetedChat calls;
  private final MeterRegistry meters;
  private final Executor executor;
  private final Map<Evaluation, String> promptLabels;

  SpringAiGuideJudge(
      ChatModel chatModel,
      String model,
      DailyBudget budget,
      ModelCallMetrics metrics,
      Resilience resilience,
      MeterRegistry meters,
      Executor executor) {
    this.chatModel = chatModel;
    this.model = model;
    this.calls = new BudgetedChat(LlmPolicies.GUIDE_JUDGE, budget, metrics, resilience);
    this.meters = meters;
    this.executor = executor;
    this.promptLabels = promptLabels();
  }

  @Override
  public void judge(Need need, List<ReasonClaim> claims, Map<String, ReasonCheck> checks) {
    List<ReasonClaim> judged = List.copyOf(claims);
    Map<String, ReasonCheck> verified = Map.copyOf(checks);
    executor.execute(() -> judgeAll(need, judged, verified));
  }

  private void judgeAll(Need need, List<ReasonClaim> claims, Map<String, ReasonCheck> checks) {
    for (ReasonClaim claim : claims) {
      for (Evaluation evaluation : Evaluation.values()) {
        Verdict verdict = verdict(evaluation, need, claim);
        count(evaluation, claim, checks.get(claim.getKey()), verdict);
      }
    }
  }

  private Verdict verdict(Evaluation evaluation, Need need, ReasonClaim claim) {
    Answer answer = new Answer(evaluation);
    try {
      boolean pass =
          evaluation.evaluator(chatOn(answer)).evaluate(evaluation.request(need, claim)).isPass();
      return pass ? Verdict.PASS : answer.saidNo() ? Verdict.FAIL : Verdict.UNCLEAR;
    } catch (BudgetSpent e) {
      return Verdict.SKIPPED;
    } catch (RuntimeException e) {
      log.warn(
          "guide judge {} failed: {} ({})",
          evaluation.label,
          ModelErrors.category(e),
          e.getClass().getSimpleName());
      return Verdict.ERROR;
    }
  }

  private ChatClient.Builder chatOn(ChatModel answer) {
    return ChatClient.builder(answer).defaultOptions(CallOptions.of(model, ANSWER_TOKENS));
  }

  private void count(Evaluation evaluation, ReasonClaim claim, ReasonCheck check, Verdict verdict) {
    meters
        .counter(
            METER,
            "kind",
            ClaimKind.of(claim.getKey()).getLabel(),
            "evaluator",
            evaluation.label,
            "verdict",
            verdict.label,
            "verify",
            check == null ? "unchecked" : check.name().toLowerCase(Locale.ROOT),
            "prompt",
            promptLabels.get(evaluation))
        .increment();
  }

  String promptLabel(Evaluation evaluation) {
    return promptLabels.get(evaluation);
  }

  static Map<Evaluation, String> promptLabels() {
    Need need = new Need("need");
    ReasonClaim claim = new ReasonClaim("c1", "reason", "quote", "text", List.of());
    Map<Evaluation, String> labels = new java.util.EnumMap<>(Evaluation.class);
    for (Evaluation evaluation : Evaluation.values()) {
      Probe probe = new Probe();
      evaluation.evaluator(ChatClient.builder(probe)).evaluate(evaluation.request(need, claim));
      labels.put(evaluation, ModelCallMetrics.promptVersion(probe.prompt));
    }
    return Map.copyOf(labels);
  }

  private static Document document(String text) {
    return new Document(text.isBlank() ? "(no text)" : text);
  }

  private static final class BudgetSpent extends RuntimeException {
    BudgetSpent() {
      super("the guide judge's daily calls are spent", null, false, false);
    }
  }

  private static final class Probe implements ChatModel {
    private String prompt = "";

    @Override
    public ChatResponse call(Prompt prompt) {
      this.prompt = prompt.getContents();
      return reply("yes");
    }
  }

  private final class Answer implements ChatModel {
    private final Evaluation evaluation;
    private String word = "";

    Answer(Evaluation evaluation) {
      this.evaluation = evaluation;
    }

    @Override
    public ChatResponse call(Prompt prompt) {
      ChatResponse response =
          calls.response(
              new BudgetedChat.Call(evaluation.step(), model, promptLabels.get(evaluation)),
              BudgetSpent::new,
              () -> chatModel.call(prompt));
      word = firstWord(response);
      return word.isEmpty() ? response : reply(word);
    }

    boolean saidNo() {
      return word.equals("no");
    }
  }

  static String firstWord(ChatResponse response) {
    if (response == null || response.getResult() == null) {
      return "";
    }
    String text = response.getResult().getOutput().getText();
    if (text == null) {
      return "";
    }
    String first = text.strip().split("[^\\p{L}]+", 2)[0].toLowerCase(Locale.ROOT);
    return first.equals("yes") || first.equals("no") ? first : "";
  }

  private static ChatResponse reply(String text) {
    return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
  }
}
