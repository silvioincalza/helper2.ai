package ai.helper2.guide.internal;

import ai.helper2.guide.AnswerLimits;
import ai.helper2.guide.PlannedQueries;
import ai.helper2.guide.Candidate;
import ai.helper2.guide.ConstraintQuote;
import ai.helper2.guide.GuideBudgetExhausted;
import ai.helper2.guide.GuideModel;
import ai.helper2.guide.GuideProposal;
import ai.helper2.guide.GuideSearchPlan;
import ai.helper2.guide.GuideUnavailable;
import ai.helper2.guide.GuideValidator;
import ai.helper2.guide.ProposedPick;
import ai.helper2.guide.ReasonClaim;
import ai.helper2.guide.Clarification;
import ai.helper2.guide.ComparisonProposal;
import ai.helper2.guide.ExplanationProposal;
import ai.helper2.guide.GuideIntent;
import ai.helper2.guide.PlannedWindow;
import ai.helper2.guide.ProposedCell;
import ai.helper2.guide.ProposedRow;
import ai.helper2.guide.ProposedSentence;
import ai.helper2.guide.WindowDirection;
import ai.helper2.guide.WindowUnit;
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
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.ai.chat.client.AdvisorParams;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.converter.BeanOutputConverter;

/**
 * Spring AI adapter for the guide. Three calls per question: a fast model (e.g. Nemotron Nano) plans the
 * search; a reasoning model (e.g. Nemotron 3 Super) chooses among real candidates, then checks each kept
 * reason. All use structured output; GuideValidator still decides what is shown. Every attempt, retries
 * included, takes one call from the daily budget (ADR 0017), and runs under the {@code llm-chat} circuit (ADR 0033).
 */
class SpringAiGuideModel implements GuideModel {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(SpringAiGuideModel.class);

    /** The planner's instructions, with the word limits AskGuide enforces. */
    static String planSystem() {
        return PromptTemplate.fromClasspath("guide/plan.txt").render(Map.of(
            "maxQueryWords", PlannedQueries.MAX_QUERY_WORDS,
            "maxWebQueryWords", PlannedQueries.MAX_WEB_QUERY_WORDS));
    }

    /**
     * Reason length the prompt asks for: below GuideValidator.MAX_REASON_CHARS on purpose, because models
     * count characters loosely and an overlong reason deletes the whole pick.
     */
    static final int REASON_TARGET_CHARS = 300;

    /** The chooser's instructions, with the limits GuideValidator enforces. */
    static String chooseSystem() {
        return PromptTemplate.fromClasspath("guide/choose.txt").render(Map.of(
            "maxPicks", GuideValidator.MAX_PICKS,
            "minQuoteChars", GuideValidator.MIN_QUOTE_CHARS,
            "minQuoteWords", GuideValidator.MIN_QUOTE_WORDS,
            "maxQuoteChars", GuideValidator.MAX_QUOTE_CHARS,
            "reasonTargetChars", REASON_TARGET_CHARS));
    }

    /** The comparer's instructions (ADR 0032), with the limits AskGuide enforces. */
    static String compareSystem() {
        AnswerLimits limits = AnswerLimits.DEFAULT;
        return PromptTemplate.fromClasspath("guide/compare.txt").render(Map.of(
            "maxRows", limits.getMaxRows(), "maxAspectChars", limits.getMaxAspectChars(), "maxCellChars", limits.getMaxCellChars()));
    }

    /** The explainer's instructions (ADR 0032), with the limits AskGuide and GuideValidator enforce. */
    static String explainSystem() {
        AnswerLimits limits = AnswerLimits.DEFAULT;
        return PromptTemplate.fromClasspath("guide/explain.txt").render(Map.of(
            "maxSentences", limits.getMaxSentences(), "maxSentenceChars", limits.getMaxSentenceChars(),
            "minQuoteChars", GuideValidator.MIN_QUOTE_CHARS,
            "minQuoteWords", GuideValidator.MIN_QUOTE_WORDS,
            "maxQuoteChars", GuideValidator.MAX_QUOTE_CHARS));
    }

    /** The reason checker's instructions. */
    static String verifySystem() {
        return PromptTemplate.fromClasspath("guide/verify-reasons.txt").render(Map.of());
    }

    record Verdicts(List<Verdict> verdicts) {
        record Verdict(String key, Boolean supported) {
        }
    }

    record Plan(String intent, List<String> subjects, String searchQuery, List<String> constraints, Boolean wantsReading,
                String webQuery, Window window, Clarify clarify) {
        record Window(Integer amount, String unit, String direction) {
        }

        record Clarify(String question, List<String> options) {
        }
    }

    record Compared(List<Row> rows) {
        record Row(String aspect, List<Cell> cells) {
        }

        record Cell(String subjectKey, String text, String quote) {
        }
    }

    record Explained(List<Sentence> sentences) {
        record Sentence(String text, String candidateKey, String quote) {
        }
    }

    record Choice(List<Pick> picks, String prompt, List<String> promptBasedOn) {
        record Pick(String candidateKey, String reason, String quote, List<Check> checks) {
        }

        record Check(String constraint, String quote) {
        }
    }

    /** Longest candidate text sent to the model; the validator still matches quotes against the full text. */
    private static final int MAX_CANDIDATE_CHARS = 800;

    private final ChatClient chat;
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
    private final DailyBudget budget;
    private final Resilience resilience;

    /**
     * Which calls may think before answering (see CallOptions). Plan and reason check are short,
     * well-specified tasks; thinking only made them slower (27 Oct 2026). Choose is configurable.
     */
    record Thinking(boolean plan, boolean choose, boolean verify) {
        static final Thinking DEFAULT = new Thinking(false, false, false);
    }

    /** No daily cap, no meters, no circuit: for tests of the calls themselves. */
    SpringAiGuideModel(ChatModel chatModel, String provider, String fastModel, String reasoningModel) {
        this(chatModel, provider, fastModel, reasoningModel, true, DEFAULT_CHOOSE_MAX_TOKENS, Thinking.DEFAULT,
            new DailyBudget(Integer.MAX_VALUE, Clock.systemUTC(), "guide"), ModelCallMetrics.none(), Resilience.NONE);
    }

    /**
     * Nemotron 3 Super is a reasoning model, and on Token Factory its reasoning tokens count against
     * max_tokens (measured 2026-10-26: 408 of 567 completion tokens on a small prompt). With 12 candidates
     * the global 2048 limit cut the JSON off (UnexpectedEndOfInputException), so the choose call has its own.
     */
    static final int DEFAULT_CHOOSE_MAX_TOKENS = 8192;

    SpringAiGuideModel(ChatModel chatModel, String provider, String fastModel, String reasoningModel,
                       boolean nativeSchemaForChoose, int chooseMaxTokens, Thinking thinking,
                       DailyBudget budget, ModelCallMetrics metrics, Resilience resilience) {
        resilience.require(LlmPolicies.CHAT);
        this.metrics = metrics;
        this.budget = budget;
        this.resilience = resilience;
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

    /** The rendered prompts and the settings that shape an answer; the cache key changes when any of them does. */
    @Override
    public String getConfigFingerprint() {
        return String.join("\u0000", planSystem, chooseSystem, verifySystem, compareSystem, explainSystem, thinking.toString(),
            "nativeSchema=" + nativeSchemaForChoose, "chooseMaxTokens=" + chooseMaxTokens, "verifyMaxTokens=" + VERIFY_MAX_TOKENS,
            "maxCandidateChars=" + MAX_CANDIDATE_CHARS);
    }

    @Override
    public GuideSearchPlan plan(String request, AudiencePath path) {
        return plan(request, path, null);
    }

    /** Longest clarification answer sent to the model: the API's own limit (openapi.yaml, GuideRequest.clarification). */
    static final int MAX_ANSWER_CHARS = 300;

    @Override
    public GuideSearchPlan plan(String request, AudiencePath path, String clarification) {
        String answer = clarification == null ? "" : "\n<answer>\n" + untrusted(clarification, MAX_ANSWER_CHARS) + "\n</answer>";
        Plan plan = call(fastModel, planSystem,
            "Audience: " + (path == null ? "any" : path.name()) + "\n" + need(request) + answer, Plan.class, false, null,
            "plan", thinking.plan());
        if (plan == null) {
            return new GuideSearchPlan("");
        }
        List<String> constraints = plan.constraints() == null ? List.of()
            : plan.constraints().stream().filter(c -> c != null && !c.isBlank()).toList();
        List<String> subjects = plan.subjects() == null ? List.of()
            : plan.subjects().stream().filter(c -> c != null && !c.isBlank()).toList();
        Clarification clarify = plan.clarify() == null || clarification != null ? null
            : Clarification.of(plan.clarify().question(), plan.clarify().options());
        return new GuideSearchPlan(plan.searchQuery() == null ? "" : plan.searchQuery(), constraints,
            Boolean.TRUE.equals(plan.wantsReading()), plan.webQuery() == null ? "" : plan.webQuery(),
            enumOf(GuideIntent.class, plan.intent()), subjects, window(plan.window()), clarify);
    }

    /** The model's period, or null when any part is missing or unknown: a wrong window drops every result. */
    private static PlannedWindow window(Plan.Window w) {
        if (w == null || w.amount() == null || w.amount() < 1) {
            return null;
        }
        WindowUnit unit = enumOf(WindowUnit.class, w.unit());
        if (unit == null) {
            return null;
        }
        WindowDirection direction = enumOf(WindowDirection.class, w.direction());
        return new PlannedWindow(w.amount(), unit, direction == null ? WindowDirection.PAST : direction);
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
    public ComparisonProposal compare(String request, List<Candidate> subjects) {
        String list = subjects.stream()
            .map(c -> "<subject key=\"" + c.getKey() + "\" type=\"" + c.getResult().getType() + "\""
                + (c.getResult().getLicense() == null ? "" : " license=\"" + untrusted(c.getResult().getLicense()).replace("\"", "'") + "\"")
                + ">\n" + untrusted(c.getText()) + "\n</subject>")
            .collect(Collectors.joining("\n"));
        Compared answer = call(reasoningModel, compareSystem, need(request) + "\n\nSUBJECTS:\n" + list, Compared.class,
            nativeSchemaForChoose, chooseMaxTokens, "compare", thinking.choose());
        if (answer == null || answer.rows() == null) {
            return new ComparisonProposal(List.of());
        }
        return new ComparisonProposal(answer.rows().stream()
            .filter(r -> r != null && r.aspect() != null)
            .map(r -> new ProposedRow(r.aspect(), r.cells() == null ? List.of() : r.cells().stream()
                .filter(c -> c != null && c.subjectKey() != null)
                .map(c -> new ProposedCell(c.subjectKey(), nz(c.text()), nz(c.quote())))
                .toList()))
            .toList());
    }

    @Override
    public ExplanationProposal explain(String request, String subject, List<Candidate> candidates) {
        String list = candidates.stream()
            .map(c -> "<candidate key=\"" + c.getKey() + "\" type=\"" + c.getResult().getType() + "\">\n"
                + untrusted(c.getText()) + "\n</candidate>")
            .collect(Collectors.joining("\n"));
        Explained answer = call(reasoningModel, explainSystem,
            need(request) + "\n\nSUBJECT: " + untrusted(subject, MAX_NEED_CHARS) + "\n\nCANDIDATES:\n" + list, Explained.class,
            nativeSchemaForChoose, chooseMaxTokens, "explain", thinking.choose());
        if (answer == null || answer.sentences() == null) {
            return new ExplanationProposal(List.of());
        }
        return new ExplanationProposal(answer.sentences().stream()
            .filter(x -> x != null && x.candidateKey() != null)
            .map(x -> new ProposedSentence(nz(x.text()), x.candidateKey(), nz(x.quote())))
            .toList());
    }

    @Override
    public GuideProposal choose(String request, List<Candidate> candidates, List<String> constraints) {
        return choose(request, candidates, constraints, false);
    }

    @Override
    public GuideProposal choose(String request, List<Candidate> candidates, List<String> constraints, boolean think) {
        String list = candidates.stream()
            .map(c -> "<candidate key=\"" + c.getKey() + "\" type=\"" + c.getResult().getType() + "\""
                + (c.getResult().getLicense() == null ? "" : " license=\"" + untrusted(c.getResult().getLicense()).replace("\"", "'") + "\"")
                + created(c)
                + ">\n"
                + untrusted(c.getText()) + "\n</candidate>")
            .collect(Collectors.joining("\n"));
        String constraintList = constraints.isEmpty() ? "(none)"
            : constraints.stream().map(c -> untrusted(c, MAX_NEED_CHARS)).collect(Collectors.joining("; "));
        Choice choice = call(reasoningModel, chooseSystem,
            need(request) + "\n\nCONSTRAINTS:\n" + constraintList + "\n\nCANDIDATES:\n" + list, Choice.class,
            nativeSchemaForChoose, chooseMaxTokens, "choose", thinking.choose() || think);
        if (choice == null) {
            return new GuideProposal(List.of(), null, List.of());
        }
        List<ProposedPick> picks = choice.picks() == null ? List.of() : choice.picks().stream()
            .filter(p -> p != null && p.candidateKey() != null)
            .map(p -> new ProposedPick(p.candidateKey(), nz(p.reason()), nz(p.quote()), checks(p.checks())))
            .toList();
        return new GuideProposal(picks, choice.prompt(), choice.promptBasedOn() == null ? List.of() : choice.promptBasedOn());
    }

    /**
     * Reasoning model (thinking off by default): one call for all picks. Unknown keys and missing answers
     * are dropped, so they stay NOT_CHECKED.
     */
    @Override
    public Map<String, Boolean> verifyReasons(String request, List<ReasonClaim> claims) {
        String picks = claims.stream()
            .map(c -> "<pick key=\"" + c.getKey() + "\">\n<reason>" + untrusted(c.getReason()) + "</reason>\n<evidence>\n"
                + "Text: " + untrusted(c.getText()) + "\nQuote: " + untrusted(c.getQuote())
                + (c.getConfirmed().isEmpty() ? "" : "\nConfirmed requirements: " + c.getConfirmed().stream()
                    .map(k -> untrusted(k.getConstraint()) + " (" + untrusted(String.valueOf(k.getQuote())) + ")")
                    .collect(Collectors.joining("; ")))
                + "\n</evidence>\n</pick>")
            .collect(Collectors.joining("\n"));
        Verdicts answer = call(reasoningModel, verifySystem, need(request) + "\n\nPICKS:\n" + picks, Verdicts.class,
            nativeSchemaForChoose, VERIFY_MAX_TOKENS, "verify", thinking.verify());
        if (answer == null || answer.verdicts() == null) {
            return Map.of();
        }
        var keys = claims.stream().map(ReasonClaim::getKey).collect(Collectors.toSet());
        return answer.verdicts().stream()
            .filter(v -> v != null && v.key() != null && v.supported() != null && keys.contains(v.key().strip()))
            .collect(Collectors.toMap(v -> v.key().strip(), Verdicts.Verdict::supported, (a, b) -> a && b));
    }

    /** Thinking off, three short verdicts: 1024 leaves room; with thinking on, the reasoning tokens count too. */
    static final int VERIFY_MAX_TOKENS = 4096;

    /**
     * One retry, only for an answer we could not use (e.g. JSON that does not match the schema): one
     * malformed structured answer should not lose the question. Provider errors are not retried: the SDK's own
     * retry is off by default (spring.ai.openai.chat.max-retries, H2_LLM_MAX_RETRIES=0) so no call escapes the
     * budget, and a rejected call (400, 401) will not change. Each attempt takes one call from the daily budget
     * before it is sent, inside the {@code llm-chat} circuit: while the circuit is open, the call fails at once as
     * {@code circuit_open}, without reaching the provider or taking a call from the budget.
     * Reasoning in {@code <think>} blocks is removed before the JSON is read (ModelAnswers).
     * [nativeSchema] sends the JSON schema as {@code response_format: json_schema} (guided decoding) instead of
     * only describing it in the prompt. Measured on Token Factory (2026-10-26): Nemotron 3 Super returns clean
     * JSON with it, while Nemotron Nano loops on whitespace until the token limit, so it is per call.
     */
    private <T> T call(String model, String system, String user, Class<T> type, boolean nativeSchema, Integer maxTokens,
                       String step, boolean think) {
        RuntimeException last = null;
        BeanOutputConverter<T> converter = new BeanOutputConverter<>(type, null, text -> ModelAnswers.jsonObject(text).orElse(""));
        String version = ModelCallMetrics.promptVersion(system);
        for (int attempt = 1; attempt <= 2; attempt++) {
            try {
                return resilience.call(LlmPolicies.CHAT, () -> {
                    if (!budget.tryAcquire()) {
                        throw new GuideBudgetExhausted();
                    }
                    var prompt = chat.prompt()
                        .options(CallOptions.of(model, maxTokens, think))
                        .system(system)
                        .user(user);
                    if (nativeSchema) {
                        prompt = prompt.advisors(AdvisorParams.ENABLE_NATIVE_STRUCTURED_OUTPUT);
                    }
                    // One observation per attempt: a retry is a second call to the provider, with its own latency.
                    var spec = prompt.call();
                    return metrics.observe(step, model, version, () -> {
                        var response = spec.responseEntity(converter);
                        metrics.tokens(step, version, response.response());
                        // Model output only (no user text); off unless this logger is set to DEBUG.
                        log.debug("guide {} answer: {}", step, response.entity());
                        return response.entity();
                    });
                });
            } catch (GuideBudgetExhausted e) {
                throw e;
            } catch (CallRefused e) {
                log.info("guide model call refused: {} is {}", e.policy(), e.reason());
                throw new GuideUnavailable("model call refused: " + e.reason(), "circuit_open");
            } catch (RuntimeException e) {
                last = e;
                log.warn("guide model call failed (attempt {} of 2, model {}): {} ({})", attempt, model, ModelErrors.category(e),
                    e.getClass().getSimpleName());
                if (ModelErrors.isProviderError(e)) {
                    break;
                }
            }
        }
        throw new GuideUnavailable("model call failed: " + last.getClass().getSimpleName(), ModelErrors.category(last));
    }

    /** The creation date its source states, as a day; code's fact, not candidate text. Empty when unknown. */
    private static String created(Candidate c) {
        var published = c.getResult().getPublished().getValue();
        return published == null ? "" : " created=\"" + published.toString().substring(0, 10) + "\"";
    }

    private static List<ConstraintQuote> checks(List<Choice.Check> checks) {
        return checks == null ? List.of() : checks.stream()
            .filter(c -> c != null && c.constraint() != null && c.quote() != null)
            .map(c -> new ConstraintQuote(c.constraint(), c.quote()))
            .toList();
    }

    /** Third-party text cannot close our delimiter or open a new one; long text is cut. */
    static String untrusted(String text) {
        return untrusted(text, MAX_CANDIDATE_CHARS);
    }

    private static String untrusted(String text, int maxChars) {
        return UntrustedText.untrusted(text, maxChars);
    }

    /** Longest need sent to a model: the API's own limit for a question (openapi.yaml, GuideRequest.request). */
    static final int MAX_NEED_CHARS = 500;

    /**
     * The user's need between {@code <need>} tags, escaped like third-party text. A shared link can ask a question
     * for someone else, so the need is data too: it cannot close the tag, forge a candidate or a pick, or pose as
     * our instructions.
     */
    static String need(String request) {
        return "<need>\n" + untrusted(request, MAX_NEED_CHARS) + "\n</need>";
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
