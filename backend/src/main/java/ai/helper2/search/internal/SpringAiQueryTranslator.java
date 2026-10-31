package ai.helper2.search.internal;

import ai.helper2.platform.cache.SharedCache;
import ai.helper2.platform.llm.CallOptions;
import ai.helper2.platform.llm.DailyBudget;
import ai.helper2.platform.llm.LlmPolicies;
import ai.helper2.platform.llm.ModelAnswers;
import ai.helper2.platform.llm.ModelCallMetrics;
import ai.helper2.platform.prompt.PromptTemplate;
import ai.helper2.platform.resilience.CallRefused;
import ai.helper2.platform.resilience.Resilience;
import ai.helper2.search.QueryTranslator;
import java.time.Duration;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;

/**
 * Translates a non-English search query into English search terms with the fast model (ADR 0011).
 * Every call is charged to its own daily budget, not the guide's: search allows far more requests per client
 * than the guide. When the budget is spent, the model fails or the {@code llm-chat} circuit is open, the answer is
 * null and search runs untranslated. Answers are shared by every replica for {@code ttl} (cache {@code translation}),
 * keyed by a hash of the model, the prompt version, the language and the normalised query, so a repeated query costs no
 * model call on any replica; a failed or refused translation is not cached.
 */
class SpringAiQueryTranslator implements QueryTranslator {

    private static final Logger log = LoggerFactory.getLogger(SpringAiQueryTranslator.class);

    static String systemPrompt() {
        return PromptTemplate.fromClasspath("search/translate-query.txt").render(Map.of("maxWords", QueryTranslator.MAX_WORDS));
    }

    private final ChatClient chat;
    private final String system;
    private final String version;
    private final String model;
    private final DailyBudget budget;
    private final SharedCache cache;
    private final Duration ttl;
    private final ModelCallMetrics metrics;
    private final Resilience resilience;

    /** The budget is checked inside the circuit, so a refused call is not charged; it is no provider failure either. */
    private static final class BudgetSpent extends RuntimeException {
        BudgetSpent() {
            super("daily translation budget used up", null, false, false);
        }
    }

    SpringAiQueryTranslator(ChatModel chatModel, String model, DailyBudget budget, SharedCache cache, Duration ttl,
                            ModelCallMetrics metrics, Resilience resilience) {
        resilience.require(LlmPolicies.CHAT);
        this.metrics = metrics;
        this.resilience = resilience;
        this.chat = ChatClient.builder(chatModel).build();
        this.system = systemPrompt();
        this.version = ModelCallMetrics.promptVersion(system);
        this.model = model;
        this.budget = budget;
        this.cache = cache;
        this.ttl = ttl;
    }

    @Override
    public String toEnglish(String text, String language) {
        String key = cache.key(model, version, language, SharedCache.normalisedQuery(text));
        String cached = cache.get(key);
        if (cached != null) {
            return cached;
        }
        String english;
        try {
            english = resilience.call(LlmPolicies.CHAT, () -> {
                if (!budget.tryAcquire()) {
                    throw new BudgetSpent();
                }
                return metrics.observe("translate", model, version, () -> {
                    var response = chat.prompt()
                        .options(CallOptions.of(model, 400, false))
                        .system(system)
                        .user("Language: " + language + "\nQuery: " + text)
                        .call()
                        .chatResponse();
                    metrics.tokens("translate", version, response);
                    return response == null || response.getResult() == null ? null : response.getResult().getOutput().getText();
                });
            });
        } catch (BudgetSpent e) {
            log.info("Query translation skipped: daily translation budget used up");
            return null;
        } catch (CallRefused e) {
            log.info("Query translation skipped: {} is {}", e.policy(), e.reason());
            return null;
        } catch (RuntimeException e) {
            log.info("Query translation failed: {}", e.toString());
            return null;
        }
        // Reasoning models may prepend their thinking; only the final line is the answer.
        english = ModelAnswers.withoutReasoning(english);
        if (english.isEmpty()) {
            return null;
        }
        english = english.lines().reduce((first, second) -> second).orElse("").strip();
        cache.put(key, english, ttl);
        return english;
    }
}
