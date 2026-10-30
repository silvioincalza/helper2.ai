package ai.helper2.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ai.helper2.search.SourceHit;
import ai.helper2.search.SourceQuery;
import ai.helper2.shared.Audience;
import ai.helper2.shared.CanonicalUrl;
import ai.helper2.shared.DateFact;
import ai.helper2.shared.ResourceType;
import ai.helper2.shared.ValueOrigin;
import java.net.URI;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * ADR 0032 end to end with a scripted planner (no network): an ambiguous name asked back and the reply answered, and a
 * WHEN question about an event searched over events only. Dates come from records, so no call after the plan.
 */
@TestPropertySource(properties = {
    "spring.ai.model.chat=fake",
    "helper2.llm.provider-label=nebius-token-factory",
    "helper2.llm.fast-model=fake-nano",
    "helper2.llm.reasoning-model=fake-super",
    "helper2.llm.native-schema=false",
})
class GuideAskBackApiIT extends ApiTestBase {

    static final List<String> MODELS_CALLED = new CopyOnWriteArrayList<>();
    private static final Pattern NEED = Pattern.compile("<need>\\n(.*?)\\n</need>", Pattern.DOTALL);
    private static final Pattern ANSWER = Pattern.compile("<answer>\\n(.*?)\\n</answer>", Pattern.DOTALL);

    /** The planner: the question's need decides the plan; a reply to our question names the chosen thing. */
    @TestConfiguration(proxyBeanMethods = false)
    static class FakePlanner {
        @Bean
        ChatModel chatModel() {
            return new ChatModel() {
                @Override
                public ChatResponse call(Prompt prompt) {
                    MODELS_CALLED.add(prompt.getOptions().getModel());
                    return new ChatResponse(List.of(new Generation(new AssistantMessage(plan(prompt.getContents())))));
                }
            };
        }

        private static String plan(String prompt) {
            Matcher need = NEED.matcher(prompt);
            assertThat(need.find()).as("only the plan call is expected").isTrue();
            if (need.group(1).contains("hackathon")) {
                return """
                    {"intent":"WHEN","subjects":[],"searchQuery":"AI hackathon","constraints":[],"wantsReading":true,
                     "webQuery":"upcoming AI hackathons","window":null,"clarify":null}""";
            }
            String subject = ANSWER.matcher(prompt).find() ? "gemini cli" : "Gemini";
            return """
                {"intent":"WHEN","subjects":["%s"],"searchQuery":"%s","constraints":[],"wantsReading":false,
                 "webQuery":"%s release date","window":null,"clarify":null}""".formatted(subject, subject, subject);
        }
    }

    private final List<SourceQuery> queries = new CopyOnWriteArrayList<>();

    @BeforeEach
    void clearCalls() {
        MODELS_CALLED.clear();
        queries.clear();
    }

    private static SourceHit entry(String url, String title, ResourceType type, String published, int rank) {
        return hit(url, title, type, DateFact.fromSource(Instant.parse(published), "fake-live", Instant.now()), DateFact.unknown(), rank);
    }

    private static SourceHit event(String url, String title, LocalDate start, int rank) {
        return hit(url, title, ResourceType.EVENT, DateFact.unknown(), DateFact.scheduledDay(start, "fake-live"), rank);
    }

    private static SourceHit hit(String url, String title, ResourceType type, DateFact published, DateFact eventStart, int rank) {
        Instant now = Instant.now();
        return new SourceHit("fake-live", url, URI.create(url), CanonicalUrl.of(url), null, title, title + " for developers",
            ValueOrigin.SOURCE_PROVIDED, type, Audience.BUILD_WITH_AI, null, Set.of(), Set.of("ai"), null, null, Set.of(), null,
            published, DateFact.unknown(), List.of(), now, rank, eventStart, DateFact.unknown(), "Zurich");
    }

    private static RequestPostProcessor from(String ip) {
        return r -> {
            r.setRemoteAddr(ip);
            return r;
        };
    }

    private String ask(String ip, String json) throws Exception {
        return mvc.perform(post("/api/v1/guide").with(csrf()).with(from(ip)).contentType(MediaType.APPLICATION_JSON).content(json))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
    }

    @Test
    void anAmbiguousNameIsAskedBackOnceAndTheReplyIsAnsweredFromTheChosenEntry() throws Exception {
        FAKE.set(q -> {
            queries.add(q);
            return List.of(
                entry("https://ai.google.dev/gemini", "Gemini", ResourceType.MODEL, "2023-12-06T00:00:00Z", 0),
                entry("https://github.com/google-gemini/gemini-cli", "google-gemini/gemini-cli", ResourceType.LIBRARY, "2025-06-25T00:00:00Z", 1));
        });
        String question = "{\"request\":\"when was Gemini released?\",\"path\":\"USE_AI\"}";

        String asked = ask("10.0.9.1", question);
        ContractValidator.assertMatches("GuideResponse", asked);
        // One option per kind: the model, and the first tool named Gemini (the curated seed holds one too).
        assertThat(asked).contains("\"status\":\"NEEDS_CLARIFICATION\"", "\"question\":\"Which Gemini do you mean?\"",
            "\"Gemini (model)\"", " (tool)\"");
        mvc.perform(post("/api/v1/guide").with(csrf()).with(from("10.0.9.2")).contentType(MediaType.APPLICATION_JSON).content(question))
            .andExpect(jsonPath("$.aiContent.fields").value(org.hamcrest.Matchers.hasItems("clarification.question", "clarification.options[]")))
            .andExpect(jsonPath("$.aiContent.models[0]").value("fake-nano"));
        assertThat(MODELS_CALLED).as("a plan call each, nothing else").containsExactly("fake-nano", "fake-nano");

        MODELS_CALLED.clear();
        String answered = ask("10.0.9.1",
            "{\"request\":\"when was Gemini released?\",\"path\":\"USE_AI\",\"clarification\":\"gemini cli (tool)\"}");
        ContractValidator.assertMatches("GuideResponse", answered);
        assertThat(answered).contains("\"status\":\"OK\"", "\"title\":\"google-gemini/gemini-cli\"", "Created 2025-06-25")
            .doesNotContain("NEEDS_CLARIFICATION");
        assertThat(MODELS_CALLED).containsExactly("fake-nano");
    }

    @Test
    void aWhenQuestionAboutAnEventSearchesEventsOnlyAndAnswersSoonestFirst() throws Exception {
        LocalDate today = LocalDate.now(java.time.ZoneOffset.UTC);
        FAKE.set(q -> {
            queries.add(q);
            return List.of(
                entry("https://github.com/acme/ai-hackathon", "ai-hackathon", ResourceType.LIBRARY, "2025-01-01T00:00:00Z", 0),
                event("https://example.com/hack-zurich", "AI Hackathon Zurich", today.plusDays(30), 1),
                event("https://example.com/hack-milan", "AI Hackathon Milan", today.plusDays(10), 2));
        });

        String json = ask("10.0.9.3", "{\"request\":\"when is the next AI hackathon?\",\"path\":\"USE_AI\"}");

        ContractValidator.assertMatches("GuideResponse", json);
        assertThat(queries).isNotEmpty().allSatisfy(q -> assertThat(q.getTypes()).containsExactly(ResourceType.EVENT));
        assertThat(json).contains("\"status\":\"OK\"", "\"intent\":\"WHEN\"").doesNotContain("\"title\":\"ai-hackathon\"");
        assertThat(json.indexOf("AI Hackathon Milan")).isLessThan(json.indexOf("AI Hackathon Zurich"));
        assertThat(MODELS_CALLED).containsExactly("fake-nano");
    }
}
