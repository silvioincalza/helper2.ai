package ai.helper2.setup.internal;

import ai.helper2.platform.llm.CallOptions;
import ai.helper2.platform.llm.DailyBudget;
import ai.helper2.platform.llm.ModelAnswers;
import ai.helper2.platform.llm.ModelCallMetrics;
import ai.helper2.platform.llm.ModelErrors;
import ai.helper2.platform.llm.UntrustedText;
import ai.helper2.platform.prompt.PromptTemplate;
import ai.helper2.setup.Markdown;
import ai.helper2.setup.Material;
import ai.helper2.setup.ProposedGuide;
import ai.helper2.setup.ProposedSection;
import ai.helper2.setup.ProposedStep;
import ai.helper2.setup.ToolGuideModel;
import ai.helper2.setup.ToolGuideUnavailable;
import ai.helper2.setup.ToolRef;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.AdvisorParams;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.converter.BeanOutputConverter;

/**
 * Spring AI adapter for the tool guide: one structured-output call with the tool's documents (the useful
 * sections of long READMEs, see {@link Markdown#relevant}). ToolGuideValidator decides what is shown. Every
 * attempt takes one call from the tool guide's own daily budget.
 */
class SpringAiToolGuideModel implements ToolGuideModel {

    private static final Logger log = LoggerFactory.getLogger(SpringAiToolGuideModel.class);

    /** Per document sent to the model; the validator still checks quotes and commands against the full text. */
    static final int MAX_DOCUMENT_CHARS = 14_000;
    static final int MAX_QUESTION_CHARS = 500;
    static final int MAX_TOKENS = 8192;

    record Answer(List<Section> sections) {
        record Section(String kind, List<Step> steps) {
        }

        record Step(String text, String source, String quote, String code, String language) {
        }
    }

    private final ChatClient chat;
    private final String system;
    private final String model;
    private final boolean nativeSchema;
    private final DailyBudget budget;
    private final ModelCallMetrics metrics;

    SpringAiToolGuideModel(ChatModel chatModel, String model, boolean nativeSchema, DailyBudget budget, ModelCallMetrics metrics) {
        this.chat = ChatClient.builder(chatModel).build();
        this.system = PromptTemplate.fromClasspath("setup/tool-guide.txt").render(Map.of());
        this.model = model;
        this.nativeSchema = nativeSchema;
        this.budget = budget;
        this.metrics = metrics;
    }

    @Override
    public String getModel() {
        return model;
    }

    @Override
    public String getConfigFingerprint() {
        return String.join("\u0000", system, model, "nativeSchema=" + nativeSchema, "maxDocumentChars=" + MAX_DOCUMENT_CHARS);
    }

    @Override
    public ProposedGuide write(ToolRef ref, List<Material> materials, String question) {
        String docs = materials.stream()
            .map(m -> "<document key=\"" + m.getKey() + "\" kind=\"" + m.getKind() + "\">\n"
                + UntrustedText.untrusted(Markdown.INSTANCE.relevant(m.getText(), MAX_DOCUMENT_CHARS), MAX_DOCUMENT_CHARS + 200)
                + "\n</document>")
            .collect(Collectors.joining("\n"));
        String user = "TOOL: " + UntrustedText.untrusted(ref.getTitle(), 200) + " (" + ref.getType() + ")\n"
            + "QUESTION: " + (question == null ? "(none)" : "<question>" + UntrustedText.untrusted(question, MAX_QUESTION_CHARS) + "</question>")
            + "\n\nDOCUMENTS:\n" + docs;
        Answer answer = call(user);
        if (log.isDebugEnabled()) {
            log.debug("tool guide proposal for {}: {}", ref.getTitle(), answer);
        }
        if (answer == null || answer.sections() == null) {
            return new ProposedGuide(List.of());
        }
        return new ProposedGuide(answer.sections().stream().filter(Objects::nonNull)
            .map(s -> new ProposedSection(s.kind() == null ? "" : s.kind(), s.steps() == null ? List.of() : s.steps().stream()
                .filter(Objects::nonNull)
                .map(st -> new ProposedStep(nz(st.text()), nz(st.source()), nz(st.quote()), st.code(), st.language()))
                .toList()))
            .toList());
    }

    private Answer call(String user) {
        BeanOutputConverter<Answer> converter = new BeanOutputConverter<>(Answer.class, null,
            text -> ModelAnswers.jsonObject(text).orElse(""));
        for (int attempt = 1; attempt <= 2; attempt++) {
            if (!budget.tryAcquire()) {
                throw new ToolGuideUnavailable("budget");
            }
            try {
                var prompt = chat.prompt().options(CallOptions.of(model, MAX_TOKENS, false)).system(system).user(user);
                if (nativeSchema) {
                    prompt = prompt.advisors(AdvisorParams.ENABLE_NATIVE_STRUCTURED_OUTPUT);
                }
                var spec = prompt.call();
                return metrics.observe("tool-guide", model, () -> {
                    var response = spec.responseEntity(converter);
                    metrics.tokens("tool-guide", response.response());
                    return response.entity();
                });
            } catch (RuntimeException e) {
                log.warn("tool guide model call failed (attempt {} of 2, model {}): {} ({})", attempt, model,
                    ModelErrors.category(e), e.getClass().getSimpleName());
                if (ModelErrors.isProviderError(e)) {
                    break;
                }
            }
        }
        throw new ToolGuideUnavailable("error");
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
