package ai.helper2.scout.internal;

import ai.helper2.platform.llm.CallOptions;
import ai.helper2.platform.llm.DailyBudget;
import ai.helper2.platform.llm.ModelAnswers;
import ai.helper2.platform.llm.ModelCallMetrics;
import ai.helper2.platform.llm.ModelErrors;
import ai.helper2.platform.llm.UntrustedText;
import ai.helper2.platform.prompt.PromptTemplate;
import ai.helper2.scout.Citation;
import ai.helper2.scout.EnrichmentInput;
import ai.helper2.scout.EnrichmentModel;
import ai.helper2.scout.EnrichmentProposal;
import ai.helper2.scout.EvidenceValidator;
import ai.helper2.scout.LlmUnavailable;
import ai.helper2.scout.ModelAnswerInvalid;
import ai.helper2.scout.Material;
import ai.helper2.scout.ProposedClaim;
import ai.helper2.scout.ProposedField;
import ai.helper2.scout.ProposedRelationship;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.converter.BeanOutputConverter;

/**
 * Spring AI adapter. Structured output is requested as a JSON schema derived from {@link Output};
 * the result is still only a proposal — {@code EvidenceValidator} decides what is published.
 * Thinking is off (ADR 0014): the task is extraction with a strict schema. Each call takes one unit of
 * the Scout's own daily budget; when it is spent the call is LLM_UNAVAILABLE, so the resource stays in
 * the backlog for a later day.
 */
class SpringAiEnrichmentModel implements EnrichmentModel {

    /**
     * The prompt marks the material as untrusted data, takes the excerpt length from the validator, and needs the
     * value of license, openSource and platforms in the excerpt. Stored with every run.
     */
    static final String PROMPT_VERSION = "enrich-v3";

    /** Longest material text sent per source record; the validator matches excerpts against the full text. */
    static final int MAX_MATERIAL_CHARS = 4000;

    /** The file is named after {@link #PROMPT_VERSION}: a new wording needs a new version, so provenance cannot drift. */
    static String systemPrompt() {
        return PromptTemplate.fromClasspath("scout/" + PROMPT_VERSION + ".txt")
            .render(Map.of("allowedValues", EvidenceValidator.allowedValues(),
                "minExcerptChars", EvidenceValidator.MIN_EXCERPT_CHARS,
                "maxExcerptChars", EvidenceValidator.MAX_EXCERPT_CHARS,
                "maxSummaryClaims", EvidenceValidator.MAX_SUMMARY_CLAIMS));
    }

    /** Schema the model must follow. */
    record Output(List<Field> fields, List<Rel> relationships, List<Claim> summary) {
        record Cite(String evidenceId, String excerpt) {
        }

        record Field(String field, String value, List<Cite> citations) {
        }

        record Rel(String kind, String target, List<Cite> citations) {
        }

        record Claim(String text, List<Cite> citations) {
        }
    }

    private final BeanOutputConverter<Output> converter = new BeanOutputConverter<>(Output.class);
    private final ChatClient chat;
    private final String system;
    private final String provider;
    private final String model;
    private final int maxTokens;
    private final DailyBudget budget;
    private final ModelCallMetrics metrics;

    /**
     * Same limit as the guide's choose call: Nemotron 3 Super's reasoning tokens count against max_tokens on
     * Token Factory. With the global 2048, 18 of 120 enrichment runs failed (7 empty answers: reasoning used
     * the whole budget; ~10 truncated JSON). Measured in the local enrichment_run table, 2026-10-26.
     */
    static final int DEFAULT_MAX_TOKENS = 8192;

    SpringAiEnrichmentModel(ChatModel chatModel, String provider, String model, int maxTokens, DailyBudget budget,
                            ModelCallMetrics metrics) {
        this.chat = ChatClient.builder(chatModel).build();
        this.system = systemPrompt();
        this.provider = provider;
        this.model = model;
        this.maxTokens = maxTokens;
        this.budget = budget;
        this.metrics = metrics;
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
        String material = input.getMaterials().stream()
            .map((Material m) -> "<material evidenceId=\"" + m.getEvidenceId() + "\" url=\"" + m.getSourceUrl() + "\">\n"
                + UntrustedText.untrusted(m.getText(), MAX_MATERIAL_CHARS) + "\n</material>")
            .collect(Collectors.joining("\n"));
        String answer = call(UntrustedText.untrusted(input.getResourceTitle(), 300), material);
        Output out = parse(answer);
        if (out == null) {
            return new EnrichmentProposal(List.of(), List.of(), List.of());
        }
        return new EnrichmentProposal(
            nn(out.fields()).stream().map(f -> new ProposedField(s(f.field()), s(f.value()), cites(f.citations()))).toList(),
            nn(out.relationships()).stream().map(r -> new ProposedRelationship(s(r.kind()), s(r.target()), cites(r.citations()))).toList(),
            nn(out.summary()).stream().map(c -> new ProposedClaim(s(c.text()), cites(c.citations()))).toList());
    }

    /**
     * Only provider-side trouble is LLM_UNAVAILABLE (retried on every later run): no connection, a
     * timeout, a 429 or a 5xx. Anything else the provider returns, e.g. a 400 for an over-long input,
     * will not fix itself and is a FAILED attempt.
     */
    private String call(String title, String material) {
        if (!budget.tryAcquire()) {
            throw new LlmUnavailable("daily enrichment budget used up");
        }
        try {
            return metrics.observe("enrich", model, () -> {
                var response = chat.prompt()
                    .options(CallOptions.of(model, maxTokens, false))
                    .system(system)
                    .user(u -> u.text("Resource: {title}\n\nSOURCE MATERIAL:\n{material}\n\n{format}")
                        .param("title", title).param("material", material).param("format", converter.getFormat()))
                    .call()
                    .chatResponse();
                metrics.tokens("enrich", response);
                return response == null || response.getResult() == null ? null : response.getResult().getOutput().getText();
            });
        } catch (RuntimeException e) {
            String what = e.getClass().getSimpleName() + ": " + abbreviate(e.getMessage());
            if (ModelErrors.isTransient(e)) {
                throw new LlmUnavailable("provider unavailable: " + what, e);
            }
            throw new ModelAnswerInvalid("provider rejected the call: " + what, e);
        }
    }

    /** The answer must be the requested JSON; text around it (reasoning, a preamble, code fences) is dropped. */
    Output parse(String answer) {
        if (answer == null || answer.isBlank()) {
            throw new ModelAnswerInvalid("empty answer");
        }
        String json = ModelAnswers.jsonObject(answer).orElseThrow(() ->
            new ModelAnswerInvalid("answer contains no JSON object (" + answer.length() + " chars)"));
        try {
            return converter.convert(json);
        } catch (RuntimeException e) {
            throw new ModelAnswerInvalid("answer is not the requested JSON (" + answer.length() + " chars): "
                + e.getClass().getSimpleName(), e);
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
