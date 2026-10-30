package ai.helper2.search.internal;

import ai.helper2.platform.llm.CallOptions;
import ai.helper2.platform.llm.DailyBudget;
import ai.helper2.platform.llm.LlmPolicies;
import ai.helper2.platform.llm.ModelAnswers;
import ai.helper2.platform.llm.ModelCallMetrics;
import ai.helper2.platform.prompt.PromptTemplate;
import ai.helper2.platform.resilience.CallRefused;
import ai.helper2.platform.resilience.Resilience;
import ai.helper2.search.QueryTranslator;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;

/**
 * Translates a non-English search query into English search terms with the fast model (ADR 0011).
 * Every call is charged to its own daily budget, not the guide's: search allows far more requests per client
 * than the guide. When the budget is spent, the model fails or the {@code llm-chat} circuit is open, the answer is
 * null and search runs untranslated. Answers are cached in each replica's memory, so a repeated query costs nothing there; the cache
 * never leaves the process.
 */
class SpringAiQueryTranslator implements QueryTranslator {

    private static final Logger log = LoggerFactory.getLogger(SpringAiQueryTranslator.class);

    static String systemPrompt() {
        return PromptTemplate.fromClasspath("search/translate-query.txt").render(Map.of("maxWords", QueryTranslator.MAX_WORDS));
    }

    private record Entry(String english, Instant at) {
    }

    private final ChatClient chat;
    private final String system;
    private final String version;
    private final String model;
    private final DailyBudget budget;
    private final Clock clock;
    private final Duration ttl;
    private final Map<String, Entry> cache;
    private final ModelCallMetrics metrics;
    private final Resilience resilience;

    /** The budget is checked inside the circuit, so a refused call is not charged; it is no provider failure either. */
    private static final class BudgetSpent extends RuntimeException {
        BudgetSpent() {
            super("daily translation budget used up", null, false, false);
        }
    }

    SpringAiQueryTranslator(ChatModel chatModel, String model, DailyBudget budget, Clock clock, Duration ttl,
                            int maxEntries, ModelCallMetrics metrics, Resilience resilience) {
        resilience.require(LlmPolicies.CHAT);
        this.metrics = metrics;
        this.resilience = resilience;
        this.chat = ChatClient.builder(chatModel).build();
        this.system = systemPrompt();
        this.version = ModelCallMetrics.promptVersion(system);
        this.model = model;
        this.budget = budget;
        this.clock = clock;
        this.ttl = ttl;
        this.cache = new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Entry> eldest) {
                return size() > maxEntries;
            }
        };
    }

    @Override
    public String toEnglish(String text, String language) {
        String key = language + "|" + text.strip().toLowerCase();
        synchronized (cache) {
            Entry hit = cache.get(key);
            if (hit != null && hit.at().plus(ttl).isAfter(clock.instant())) {
                return hit.english();
            }
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
        synchronized (cache) {
            cache.put(key, new Entry(english, clock.instant()));
        }
        return english;
    }
}
