package ai.helper2.guide.internal;

import ai.helper2.guide.AnswerLimits;
import ai.helper2.guide.CallTimeLimit;
import ai.helper2.guide.Candidate;
import ai.helper2.guide.Clarification;
import ai.helper2.guide.ComparisonProposal;
import ai.helper2.guide.ConstraintQuote;
import ai.helper2.guide.ExplanationProposal;
import ai.helper2.guide.GuideBudgetExhausted;
import ai.helper2.guide.GuideIntent;
import ai.helper2.guide.GuideModel;
import ai.helper2.guide.GuideProgress;
import ai.helper2.guide.GuideProposal;
import ai.helper2.guide.GuideSearchPlan;
import ai.helper2.guide.GuideUnavailable;
import ai.helper2.guide.GuideValidator;
import ai.helper2.guide.ModelActivity;
import ai.helper2.guide.ModelStep;
import ai.helper2.guide.Need;
import ai.helper2.guide.PlannedQueries;
import ai.helper2.guide.PlannedWindow;
import ai.helper2.guide.ProposedCell;
import ai.helper2.guide.ProposedPick;
import ai.helper2.guide.ProposedRow;
import ai.helper2.guide.ProposedSentence;
import ai.helper2.guide.ReasonClaim;
import ai.helper2.guide.ReasonVerdict;
import ai.helper2.guide.WindowDirection;
import ai.helper2.guide.WindowUnit;
import ai.helper2.guide.config.ModelStream;
import ai.helper2.platform.llm.BudgetedChat;
import ai.helper2.platform.llm.CallOptions;
import ai.helper2.platform.llm.ChatStream;
import ai.helper2.platform.llm.CutAnswerWatch;
import ai.helper2.platform.llm.DailyBudget;
import ai.helper2.platform.llm.LlmPolicies;
import ai.helper2.platform.llm.ModelAnswers;
import ai.helper2.platform.llm.ModelCallMetrics;
import ai.helper2.platform.llm.ModelCallMetrics.FallbackReason;
import ai.helper2.platform.llm.ModelErrors;
import ai.helper2.platform.llm.UntrustedText;
import ai.helper2.platform.prompt.PromptTemplate;
import ai.helper2.platform.resilience.CallRefused;
import ai.helper2.platform.resilience.Resilience;
import ai.helper2.shared.AudiencePath;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import org.springframework.ai.chat.client.AdvisorParams;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ResponseEntity;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.ai.openai.OpenAiChatOptions;
import reactor.core.publisher.Flux;

class SpringAiGuideModel implements GuideModel {

  private static final org.slf4j.Logger log =
      org.slf4j.LoggerFactory.getLogger(SpringAiGuideModel.class);

  static String planSystem() {
    return PromptTemplate.fromClasspath("guide/plan.txt")
        .render(
            Map.of(
                "maxQueryWords", PlannedQueries.MAX_QUERY_WORDS,
                "maxWebQueryWords", PlannedQueries.MAX_WEB_QUERY_WORDS));
  }

  static final int REASON_TARGET_CHARS = 300;

  static String security() {
    return PromptTemplate.fromClasspath("guide/security.txt").render().strip();
  }

  static String chooseSystem() {
    return PromptTemplate.fromClasspath("guide/choose.txt")
        .render(
            Map.of(
                "security", security(),
                "maxPicks", GuideValidator.MAX_PICKS,
                "minQuoteChars", GuideValidator.MIN_QUOTE_CHARS,
                "minQuoteWords", GuideValidator.MIN_QUOTE_WORDS,
                "maxQuoteChars", GuideValidator.MAX_QUOTE_CHARS,
                "reasonTargetChars", REASON_TARGET_CHARS));
  }

  static String compareSystem() {
    AnswerLimits limits = AnswerLimits.DEFAULT;
    return PromptTemplate.fromClasspath("guide/compare.txt")
        .render(
            Map.of(
                "security",
                security(),
                "minQuoteChars",
                GuideValidator.MIN_QUOTE_CHARS,
                "minQuoteWords",
                GuideValidator.MIN_QUOTE_WORDS,
                "maxQuoteChars",
                GuideValidator.MAX_QUOTE_CHARS,
                "maxRows",
                limits.getMaxRows(),
                "maxAspectChars",
                limits.getMaxAspectChars(),
                "maxCellChars",
                limits.getMaxCellChars()));
  }

  static String explainSystem() {
    AnswerLimits limits = AnswerLimits.DEFAULT;
    return PromptTemplate.fromClasspath("guide/explain.txt")
        .render(
            Map.of(
                "security",
                security(),
                "maxSentences",
                limits.getMaxSentences(),
                "maxSentenceChars",
                limits.getMaxSentenceChars(),
                "minQuoteChars",
                GuideValidator.MIN_QUOTE_CHARS,
                "minQuoteWords",
                GuideValidator.MIN_QUOTE_WORDS,
                "maxQuoteChars",
                GuideValidator.MAX_QUOTE_CHARS));
  }

  static String verifySystem() {
    return PromptTemplate.fromClasspath("guide/verify-reasons.txt")
        .render(Map.of("security", security()));
  }

  record Verdicts(List<Verdict> verdicts) {
    record Verdict(String key, Boolean claimsSupported, Boolean fitsNeed) {}
  }

  record Plan(
      String intent,
      List<String> subjects,
      String searchQuery,
      List<String> constraints,
      Boolean wantsReading,
      String webQuery,
      Window window,
      Clarify clarify) {
    record Window(Integer amount, String unit, String direction) {}

    record Clarify(String question, List<String> options) {}
  }

  record Compared(List<Row> rows) {
    record Row(String aspect, List<Cell> cells) {}

    record Cell(String subjectKey, String text, String quote) {}
  }

  record Explained(List<Sentence> sentences) {
    record Sentence(String text, String candidateKey, String quote) {}
  }

  record Choice(List<Pick> picks) {
    record Pick(String candidateKey, String reason, String quote, List<Check> checks) {}

    record Check(String constraint, String quote) {}
  }

  private static final int MAX_CANDIDATE_CHARS = 800;

  private final ChatClient chat;
  private final CallTimeLimit timeLimit;
  private final Clock clock;
  private final String planSystem;
  private final String chooseSystem;
  private final String verifySystem;
  private final String compareSystem;
  private final String explainSystem;
  private final String provider;
  private final String fastModel;
  private final String reasoningModel;
  private final boolean nativeSchemaForChoose;
  private final int chooseMaxTokens;
  private final ModelCallMetrics metrics;
  private final Thinking thinking;
  private final BudgetedChat calls;
  private final DailyBudget budget;
  private final ModelStream stream;

  record Thinking(int plan, int choose, int verify, int browse) {
    static final Thinking DEFAULT = new Thinking(0, 0, 0, 0);

    int chooseOrBrowse(boolean browse) {
      return browse ? Math.max(choose, this.browse) : choose;
    }
  }

  SpringAiGuideModel(
      ChatModel chatModel, String provider, String fastModel, String reasoningModel) {
    this(
        chatModel,
        provider,
        fastModel,
        reasoningModel,
        true,
        DEFAULT_CHOOSE_MAX_TOKENS,
        Thinking.DEFAULT,
        new DailyBudget(Integer.MAX_VALUE, Clock.systemUTC(), "guide"),
        ModelCallMetrics.none(),
        Resilience.NONE);
  }

  static final int DEFAULT_CHOOSE_MAX_TOKENS = 8192;

  SpringAiGuideModel(
      ChatModel chatModel,
      String provider,
      String fastModel,
      String reasoningModel,
      boolean nativeSchemaForChoose,
      int chooseMaxTokens,
      Thinking thinking,
      DailyBudget budget,
      ModelCallMetrics metrics,
      Resilience resilience) {
    this(
        chatModel,
        provider,
        fastModel,
        reasoningModel,
        nativeSchemaForChoose,
        chooseMaxTokens,
        thinking,
        budget,
        metrics,
        resilience,
        CallTimeLimit.NONE,
        Clock.systemUTC());
  }

  SpringAiGuideModel(
      ChatModel chatModel,
      String provider,
      String fastModel,
      String reasoningModel,
      boolean nativeSchemaForChoose,
      int chooseMaxTokens,
      Thinking thinking,
      DailyBudget budget,
      ModelCallMetrics metrics,
      Resilience resilience,
      CallTimeLimit timeLimit,
      Clock clock) {
    this(
        chatModel,
        provider,
        fastModel,
        reasoningModel,
        nativeSchemaForChoose,
        chooseMaxTokens,
        thinking,
        budget,
        metrics,
        resilience,
        timeLimit,
        clock,
        ModelStream.OFF);
  }

  SpringAiGuideModel(
      ChatModel chatModel,
      String provider,
      String fastModel,
      String reasoningModel,
      boolean nativeSchemaForChoose,
      int chooseMaxTokens,
      Thinking thinking,
      DailyBudget budget,
      ModelCallMetrics metrics,
      Resilience resilience,
      CallTimeLimit timeLimit,
      Clock clock,
      ModelStream stream) {
    this.stream = stream;
    this.timeLimit = timeLimit;
    this.clock = clock;
    this.metrics = metrics;
    this.calls = new BudgetedChat(LlmPolicies.GUIDE, budget, metrics, resilience);
    this.budget = budget;
    this.chat = ChatClient.builder(chatModel).build();
    this.planSystem = planSystem();
    this.chooseSystem = chooseSystem();
    this.verifySystem = verifySystem();
    this.compareSystem = compareSystem();
    this.explainSystem = explainSystem();
    this.thinking = thinking;
    this.provider = provider;
    this.fastModel = fastModel;
    this.reasoningModel = reasoningModel;
    this.nativeSchemaForChoose = nativeSchemaForChoose;
    this.chooseMaxTokens = chooseMaxTokens;
  }

  @Override
  public String getProvider() {
    return provider;
  }

  @Override
  public String getFastModel() {
    return fastModel;
  }

  @Override
  public String getReasoningModel() {
    return reasoningModel;
  }

  @Override
  public String getConfigFingerprint() {
    return String.join(
        "\u0000",
        planSystem,
        chooseSystem,
        verifySystem,
        compareSystem,
        explainSystem,
        thinking.toString(),
        "nativeSchema=" + nativeSchemaForChoose,
        "chooseMaxTokens=" + chooseMaxTokens,
        "planMaxTokens=" + PLAN_MAX_TOKENS,
        "verifyMaxTokens=" + VERIFY_MAX_TOKENS,
        "maxCandidateChars=" + MAX_CANDIDATE_CHARS);
  }

  @Override
  public GuideSearchPlan plan(String request, AudiencePath path) {
    return plan(request, path, null);
  }

  static final int MAX_ANSWER_CHARS = 300;

  @Override
  public GuideSearchPlan plan(String request, AudiencePath path, String clarification) {
    String answer = answer(clarification);
    Plan plan =
        call(
            fastModel,
            planSystem,
            "Audience: " + (path == null ? "any" : path.name()) + "\n" + need(request) + answer,
            Plan.class,
            false,
            PLAN_MAX_TOKENS,
            ModelStep.PLAN,
            thinking.plan());
    if (plan == null) {
      return new GuideSearchPlan("");
    }
    List<String> constraints =
        plan.constraints() == null
            ? List.of()
            : plan.constraints().stream().filter(c -> c != null && !c.isBlank()).toList();
    List<String> subjects =
        plan.subjects() == null
            ? List.of()
            : plan.subjects().stream().filter(c -> c != null && !c.isBlank()).toList();
    Clarification clarify =
        plan.clarify() == null || clarification != null
            ? null
            : Clarification.of(plan.clarify().question(), plan.clarify().options());
    return new GuideSearchPlan(
        plan.searchQuery() == null ? "" : plan.searchQuery(),
        constraints,
        Boolean.TRUE.equals(plan.wantsReading()),
        plan.webQuery() == null ? "" : plan.webQuery(),
        enumOf(GuideIntent.class, plan.intent()),
        subjects,
        window(plan.window()),
        clarify);
  }

  private static PlannedWindow window(Plan.Window w) {
    if (w == null || w.amount() == null || w.amount() < 1) {
      return null;
    }
    WindowUnit unit = enumOf(WindowUnit.class, w.unit());
    if (unit == null) {
      return null;
    }
    WindowDirection direction = enumOf(WindowDirection.class, w.direction());
    return new PlannedWindow(
        w.amount(), unit, direction == null ? WindowDirection.PAST : direction);
  }

  private static <E extends Enum<E>> E enumOf(Class<E> type, String name) {
    if (name == null || name.isBlank()) {
      return null;
    }
    try {
      return Enum.valueOf(type, name.strip().toUpperCase(java.util.Locale.ROOT));
    } catch (IllegalArgumentException e) {
      return null;
    }
  }

  @Override
  public ComparisonProposal compare(Need request, List<Candidate> subjects) {
    String list =
        subjects.stream()
            .map(c ->
                "<subject key=\""
                    + c.getKey()
                    + "\" type=\""
                    + c.getResult().getType()
                    + "\""
                    + (c.getResult().getLicense() == null
                        ? ""
                        : " license=\""
                            + untrusted(c.getResult().getLicense()).replace("\"", "'")
                            + "\"")
                    + ">\n"
                    + untrusted(c.getText())
                    + "\n</subject>")
            .collect(Collectors.joining("\n"));
    Compared answer =
        call(
            reasoningModel,
            compareSystem,
            need(request) + "\n\nSUBJECTS:\n" + list,
            Compared.class,
            nativeSchemaForChoose,
            chooseMaxTokens,
            ModelStep.COMPARE,
            thinking.choose());
    if (answer == null || answer.rows() == null) {
      return new ComparisonProposal(List.of());
    }
    return new ComparisonProposal(
        answer.rows().stream()
            .filter(r -> r != null && r.aspect() != null)
            .map(r ->
                new ProposedRow(
                    r.aspect(),
                    r.cells() == null
                        ? List.of()
                        : r.cells().stream()
                            .filter(c -> c != null && c.subjectKey() != null)
                            .map(c -> new ProposedCell(c.subjectKey(), nz(c.text()), nz(c.quote())))
                            .toList()))
            .toList());
  }

  @Override
  public ExplanationProposal explain(Need request, String subject, List<Candidate> candidates) {
    String list =
        candidates.stream()
            .map(c ->
                "<candidate key=\""
                    + c.getKey()
                    + "\" type=\""
                    + c.getResult().getType()
                    + "\">\n"
                    + untrusted(c.getText())
                    + "\n</candidate>")
            .collect(Collectors.joining("\n"));
    Explained answer =
        call(
            reasoningModel,
            explainSystem,
            need(request)
                + "\n\n<subject-name>\n"
                + untrusted(subject, MAX_NEED_CHARS)
                + "\n</subject-name>"
                + "\n\nCANDIDATES:\n"
                + list,
            Explained.class,
            nativeSchemaForChoose,
            chooseMaxTokens,
            ModelStep.EXPLAIN,
            thinking.choose());
    if (answer == null || answer.sentences() == null) {
      return new ExplanationProposal(List.of());
    }
    return new ExplanationProposal(
        answer.sentences().stream()
            .filter(x -> x != null && x.candidateKey() != null)
            .map(x -> new ProposedSentence(nz(x.text()), x.candidateKey(), nz(x.quote())))
            .toList());
  }

  @Override
  public GuideProposal choose(Need request, List<Candidate> candidates, List<String> constraints) {
    return choose(request, candidates, constraints, false);
  }

  @Override
  public GuideProposal choose(
      Need request, List<Candidate> candidates, List<String> constraints, boolean think) {
    String list =
        candidates.stream()
            .map(c ->
                "<candidate key=\""
                    + c.getKey()
                    + "\" type=\""
                    + c.getResult().getType()
                    + "\""
                    + (c.getResult().getLicense() == null
                        ? ""
                        : " license=\""
                            + untrusted(c.getResult().getLicense()).replace("\"", "'")
                            + "\"")
                    + created(c)
                    + ">\n"
                    + untrusted(c.getText())
                    + "\n</candidate>")
            .collect(Collectors.joining("\n"));
    String constraintList =
        constraints.isEmpty()
            ? "(none)"
            : constraints.stream()
                .map(c -> untrusted(c, MAX_NEED_CHARS))
                .collect(Collectors.joining("; "));
    Choice choice =
        call(
            reasoningModel,
            chooseSystem,
            need(request)
                + "\n\n<constraints>\n"
                + constraintList
                + "\n</constraints>\n\nCANDIDATES:\n"
                + list,
            Choice.class,
            nativeSchemaForChoose,
            chooseMaxTokens,
            ModelStep.CHOOSE,
            thinking.chooseOrBrowse(think));
    if (choice == null) {
      return new GuideProposal(List.of());
    }
    List<ProposedPick> picks =
        choice.picks() == null
            ? List.of()
            : choice.picks().stream()
                .filter(p -> p != null && p.candidateKey() != null)
                .map(p ->
                    new ProposedPick(
                        p.candidateKey(), nz(p.reason()), nz(p.quote()), checks(p.checks())))
                .toList();
    return new GuideProposal(picks);
  }

  @Override
  public Map<String, ReasonVerdict> verifyReasons(Need request, List<ReasonClaim> claims) {
    String picks =
        claims.stream()
            .map(c ->
                "<pick key=\""
                    + c.getKey()
                    + "\">\n<reason>"
                    + untrusted(c.getReason())
                    + "</reason>\n<evidence>\n"
                    + "Text: "
                    + untrusted(c.getText())
                    + "\nQuote: "
                    + untrusted(c.getQuote())
                    + (c.getConfirmed().isEmpty()
                        ? ""
                        : "\nConfirmed requirements: "
                            + c.getConfirmed().stream()
                                .map(k ->
                                    untrusted(k.getConstraint())
                                        + " ("
                                        + untrusted(String.valueOf(k.getQuote()))
                                        + ")")
                                .collect(Collectors.joining("; ")))
                    + "\n</evidence>\n</pick>")
            .collect(Collectors.joining("\n"));
    Verdicts answer =
        call(
            reasoningModel,
            verifySystem,
            need(request) + "\n\nPICKS:\n" + picks,
            Verdicts.class,
            nativeSchemaForChoose,
            VERIFY_MAX_TOKENS,
            ModelStep.VERIFY,
            thinking.verify());
    if (answer == null || answer.verdicts() == null) {
      return Map.of();
    }
    var keys = claims.stream().map(ReasonClaim::getKey).collect(Collectors.toSet());
    return answer.verdicts().stream()
        .filter(v ->
            v != null
                && v.key() != null
                && v.claimsSupported() != null
                && keys.contains(v.key().strip()))
        .collect(
            Collectors.toMap(v -> v.key().strip(), this::verdictOf, SpringAiGuideModel::stricter));
  }

  private ReasonVerdict verdictOf(Verdicts.Verdict v) {
    if (v.fitsNeed() == null) {
      metrics.missingField("verify", "fitsNeed");
    }
    return new ReasonVerdict(v.claimsSupported(), !Boolean.FALSE.equals(v.fitsNeed()));
  }

  private static ReasonVerdict stricter(ReasonVerdict a, ReasonVerdict b) {
    return new ReasonVerdict(
        a.getClaimsSupported() && b.getClaimsSupported(), a.getFitsNeed() && b.getFitsNeed());
  }

  static final int PLAN_MAX_TOKENS = 4096;

  static final int VERIFY_MAX_TOKENS = 4096;

  private <T> T call(
      String model,
      String system,
      String user,
      Class<T> type,
      boolean nativeSchema,
      Integer maxTokens,
      ModelStep modelStep,
      int thinkingTokens) {
    String step = modelStep.getLabel();
    Activity activity = new Activity(ModelActivity.watcher(), modelStep);
    RuntimeException last = null;
    FallbackReason fallback = null;
    int thinkingLeft = thinkingTokens;
    BeanOutputConverter<T> converter =
        new BeanOutputConverter<>(type, null, text -> ModelAnswers.jsonObject(text).orElse(""));
    String version = ModelCallMetrics.promptVersion(system);
    for (int attempt = 1; attempt <= 2; attempt++) {
      Optional<Duration> window =
          fallback == FallbackReason.TIME
              ? timeLimit.forTheCallAfterThinking(clock)
              : timeLimit.forACallStartingNow(clock);
      if (window.isEmpty()) {
        log.info("guide {} call not started: too little of the question's time is left", step);
        if (fallback == FallbackReason.CUT) {
          throw cutAtTheLimit(step);
        }
        if (last != null) {
          break;
        }
        throw new GuideUnavailable(
            "not enough time left for the " + step + " call", GuideUnavailable.DEADLINE);
      }
      if (fallback != null) {
        metrics.thinkingFallback(step, fallback);
      }
      CutAnswerWatch watch = new CutAnswerWatch();
      int thinkingNow = thinkingLeft;
      Asking<T> asking =
          new Asking<>(
              step,
              system,
              user,
              converter,
              nativeSchema,
              CallOptions.thinking(model, maxTokens, thinkingNow),
              watch);
      BudgetedChat.Call call = new BudgetedChat.Call(step, model, version);
      ChatStream.Limits limits = limitsOf(window.get(), attempt == 1 && thinkingNow > 0);
      try {
        T answer =
            stream == ModelStream.ON
                ? calls.streamed(
                    call,
                    this::budgetSpent,
                    () -> streamWithin(limits, asking, activity),
                    whole -> read(whole, asking))
                : calls.entity(call, this::budgetSpent, () -> askWithin(window.get(), asking));
        log.debug("guide {} answer: {}", step, answer);
        return answer;
      } catch (GuideBudgetExhausted e) {
        throw e;
      } catch (ChatStream.ThinkingTimeout e) {
        log.warn(
            "guide {} was still thinking after {}: stopped and repeated without thinking",
            step,
            limits.firstTextWithin());
        thinkingLeft = 0;
        fallback = FallbackReason.TIME;
      } catch (DeadlineTimeout e) {
        log.warn(
            "guide {} call stopped after {}: the question's deadline is near", step, window.get());
        if (fallback == FallbackReason.CUT) {
          throw cutAtTheLimit(step);
        }
        throw new GuideUnavailable(
            "the " + step + " call was stopped before the question's deadline",
            GuideUnavailable.DEADLINE);
      } catch (CallRefused e) {
        log.info("guide model call refused: {} is {}", e.policy(), e.reason());
        throw new GuideUnavailable("model call refused: " + e.reason(), "circuit_open");
      } catch (RuntimeException e) {
        if (ModelErrors.isInterrupt(e)) {
          Thread.currentThread().interrupt();
          throw new GuideUnavailable(
              "the question was cancelled during the " + step + " call",
              GuideUnavailable.CANCELLED);
        }
        last = e;
        log.warn(
            "guide model call failed (attempt {} of 2, model {}): {} ({})",
            attempt,
            model,
            ModelErrors.category(e),
            e.getClass().getSimpleName());
        if (ModelErrors.isProviderError(e)) {
          break;
        }
        if (watch.answerWasCut() && thinkingNow > 0 && attempt < 2) {
          log.warn(
              "guide {} answer was cut with {} thinking tokens: repeated without thinking",
              step,
              thinkingNow);
          thinkingLeft = 0;
          fallback = FallbackReason.CUT;
          continue;
        }
        if (watch.answerWasCut()) {
          log.warn(
              "guide {} answer was cut at {} tokens: the same call is not repeated",
              step,
              maxTokens);
          throw cutAtTheLimit(step);
        }
      }
    }
    throw new GuideUnavailable(
        "model call failed: " + last.getClass().getSimpleName(), ModelErrors.category(last));
  }

  private GuideBudgetExhausted budgetSpent() {
    return new GuideBudgetExhausted(budget.opensMoreToday(), budget.opensAgainAt());
  }

  private static GuideUnavailable cutAtTheLimit(String step) {
    return new GuideUnavailable("the " + step + " answer was cut at the token limit", "cut");
  }

  private record Asking<T>(
      String step,
      String system,
      String user,
      BeanOutputConverter<T> converter,
      boolean nativeSchema,
      OpenAiChatOptions.Builder options,
      CutAnswerWatch watch) {}

  private <T> ResponseEntity<ChatResponse, T> askWithin(Duration window, Asking<T> asking) {
    if (window.isPositive()) {
      asking.options().timeout(window);
    }
    var prompt =
        chat.prompt()
            .options(asking.options())
            .advisors(asking.watch())
            .system(asking.system())
            .user(asking.user());
    if (asking.nativeSchema()) {
      prompt = prompt.advisors(AdvisorParams.ENABLE_NATIVE_STRUCTURED_OUTPUT);
    }
    try {
      return prompt.call().responseEntity(asking.converter());
    } catch (RuntimeException e) {
      if (stoppedByTheDeadline(window, e)) {
        throw new DeadlineTimeout(asking.step());
      }
      throw e;
    }
  }

  private ChatStream.Limits limitsOf(Duration window, boolean thinkingWithACallToSpare) {
    ChatStream.Limits limits = ChatStream.Limits.within(window);
    if (!thinkingWithACallToSpare) {
      return limits;
    }
    return timeLimit.forThinking(clock).map(limits::thinkingFor).orElse(limits);
  }

  private <T> ChatStream.Answer streamWithin(
      ChatStream.Limits limits, Asking<T> asking, Activity activity) {
    Duration window = limits.window();
    if (window.isPositive()) {
      asking.options().timeout(window);
    }
    Flux<ChatResponse> chunks =
        chat
            .prompt()
            .options(asking.options())
            .system(asking.system())
            .user(askingForTheSchema(asking))
            .stream()
            .chatResponse();
    try {
      return ChatStream.collect(chunks, limits, activity);
    } catch (ChatStream.ThinkingTimeout e) {
      throw e;
    } catch (RuntimeException e) {
      if (stoppedByTheDeadline(window, e)) {
        throw new DeadlineTimeout(asking.step());
      }
      throw e;
    }
  }

  private static <T> String askingForTheSchema(Asking<T> asking) {
    if (asking.nativeSchema()) {
      asking.options().outputSchema(asking.converter().getJsonSchema());
      return asking.user();
    }
    return asking.user() + System.lineSeparator() + asking.converter().getFormat();
  }

  private static <T> T read(ChatStream.Answer whole, Asking<T> asking) {
    asking.watch().sawFinishReason(whole.finishReason());
    return asking.converter().convert(whole.text());
  }

  private static final class Activity implements ChatStream.Watch {
    private final GuideProgress progress;
    private final ModelStep step;
    private boolean reasoningSaid;
    private boolean writingSaid;

    Activity(GuideProgress progress, ModelStep step) {
      this.progress = progress;
      this.step = step;
    }

    @Override
    public void reasoning() {
      if (!reasoningSaid) {
        reasoningSaid = true;
        progress.reasoning(step);
      }
    }

    @Override
    public void writing() {
      if (!writingSaid) {
        writingSaid = true;
        progress.writing(step);
      }
    }
  }

  private boolean stoppedByTheDeadline(Duration window, RuntimeException e) {
    return timeLimit.shortens(window)
        && !ModelErrors.isInterrupt(e)
        && "timeout".equals(ModelErrors.category(e));
  }

  private static String created(Candidate c) {
    var published = c.getResult().getPublished().getValue();
    return published == null ? "" : " created=\"" + published.toString().substring(0, 10) + "\"";
  }

  private static List<ConstraintQuote> checks(List<Choice.Check> checks) {
    return checks == null
        ? List.of()
        : checks.stream()
            .filter(c -> c != null && c.constraint() != null && c.quote() != null)
            .map(c -> new ConstraintQuote(c.constraint(), c.quote()))
            .toList();
  }

  static String untrusted(String text) {
    return untrusted(text, MAX_CANDIDATE_CHARS);
  }

  private static String untrusted(String text, int maxChars) {
    return UntrustedText.untrusted(text, maxChars);
  }

  static final int MAX_NEED_CHARS = 500;

  static String need(String request) {
    return "<need>\n" + untrusted(request, MAX_NEED_CHARS) + "\n</need>";
  }

  static String need(Need need) {
    return need(need.getRequest()) + answer(need.getAnswer());
  }

  private static String answer(String clarification) {
    return clarification == null
        ? ""
        : "\n<answer>\n" + untrusted(clarification, MAX_ANSWER_CHARS) + "\n</answer>";
  }

  private static String nz(String s) {
    return s == null ? "" : s;
  }
}
