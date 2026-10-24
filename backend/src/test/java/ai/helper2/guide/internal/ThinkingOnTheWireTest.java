package ai.helper2.guide.internal;

import static org.assertj.core.api.Assertions.assertThat;

import ai.helper2.platform.llm.DailyBudget;
import ai.helper2.platform.llm.ModelCallMetrics;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;

/**
 * The thinking switch must survive ChatClient's option merging and reach the HTTP body: a fake ChatModel
 * cannot show that, so this test runs the real OpenAI client against a local server.
 */
class ThinkingOnTheWireTest {

    HttpServer server;
    final List<String> bodies = new CopyOnWriteArrayList<>();

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            bodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            String content = "{\"searchQuery\":\"MCP server postgres\",\"constraints\":[],\"wantsReading\":false,\"webQuery\":\"\"}";
            byte[] reply = ("""
                {"id":"x","object":"chat.completion","created":0,"model":"fast",
                 "choices":[{"index":0,"finish_reason":"stop","message":{"role":"assistant","content":%s}}],
                 "usage":{"prompt_tokens":1,"completion_tokens":1,"total_tokens":2}}
                """.formatted(quote(content))).getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, reply.length);
            exchange.getResponseBody().write(reply);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private static String quote(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private SpringAiGuideModel model(SpringAiGuideModel.Thinking thinking) {
        OpenAiChatModel chat = OpenAiChatModel.builder()
            .options(OpenAiChatOptions.builder()
                .baseUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/v1").apiKey("test").model("default").build())
            .build();
        return new SpringAiGuideModel(chat, "test", "fast", "reasoning", true, 8192, thinking,
            new DailyBudget(10, Clock.systemUTC(), "guide"), ModelCallMetrics.none());
    }

    @Test
    void thinkingOffIsInTheRequestBody() {
        model(SpringAiGuideModel.Thinking.DEFAULT).plan("postgres MCP", null);

        assertThat(bodies).singleElement().satisfies(body -> {
            assertThat(body).contains("\"chat_template_kwargs\"").contains("\"enable_thinking\":false");
            assertThat(body).contains("\"model\":\"fast\"");
        });
    }

    @Test
    void thinkingOnSendsNoFlag() {
        model(new SpringAiGuideModel.Thinking(true, true, true)).plan("postgres MCP", null);

        assertThat(bodies).singleElement().satisfies(body -> assertThat(body).doesNotContain("chat_template_kwargs"));
    }
}
