package ai.helper2.guide.internal;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.http.okhttp.OpenAiHttpClientBuilderCustomizer;

final class FakeProvider implements AutoCloseable {

  static final String PLAN =
      "{\"searchQuery\":\"MCP server postgres\",\"constraints\":[],\"wantsReading\":false,\"webQuery\":\"\"}";

  static final Duration SILENT_FOR = Duration.ofSeconds(8);
  private static final Duration NOTICED_WITHIN = Duration.ofSeconds(2);
  private static final Duration BETWEEN_PINGS = Duration.ofMillis(25);

  enum Ending {
    WHOLE,
    SILENT,
    STALLS,
    ERROR_EVENT,
  }

  record Reply(String reasoning, String content, String finishReason, Ending ending, Usage usage) {

    static Reply answer(String content) {
      return new Reply("", content, "stop", Ending.WHOLE, Usage.ON_THE_LAST_CHUNK);
    }

    static Reply cut(String content) {
      return new Reply("", content, "length", Ending.WHOLE, Usage.ON_THE_LAST_CHUNK);
    }

    static Reply silent() {
      return new Reply("", "", "stop", Ending.SILENT, Usage.ON_THE_LAST_CHUNK);
    }

    static Reply stallsAfter(String content) {
      return new Reply("", content, "stop", Ending.STALLS, Usage.ON_THE_LAST_CHUNK);
    }

    static Reply keepsThinking() {
      return new Reply("Let me think", "", "stop", Ending.STALLS, Usage.PROMPT_ONLY_AT_THE_START);
    }

    static Reply errorEventAfter(String content) {
      return new Reply("", content, "stop", Ending.ERROR_EVENT, Usage.ON_THE_LAST_CHUNK);
    }

    Reply afterThinking(String thought) {
      return new Reply(thought, content, finishReason, ending, usage);
    }

    Reply reporting(Usage how) {
      return new Reply(reasoning, content, finishReason, ending, how);
    }
  }

  enum Usage {
    ON_THE_LAST_CHUNK,
    PROMPT_ONLY_AT_THE_START,
  }

  final List<String> bodies = new CopyOnWriteArrayList<>();
  private final List<Duration> closedByTheClientAfter = new CopyOnWriteArrayList<>();
  private final Deque<Reply> script = new ConcurrentLinkedDeque<>();
  private volatile Reply otherwise = Reply.answer(PLAN);
  private final HttpServer server;

  FakeProvider() {
    try {
      server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
    server.createContext("/v1/chat/completions", this::reply);
    server.start();
  }

  List<Duration> closedByTheClientAfter(int streams) {
    long giveUp = System.nanoTime() + NOTICED_WITHIN.toNanos();
    while (closedByTheClientAfter.size() < streams && System.nanoTime() < giveUp) {
      pause(BETWEEN_PINGS);
    }
    return List.copyOf(closedByTheClientAfter);
  }

  FakeProvider then(Reply... replies) {
    script.addAll(List.of(replies));
    return this;
  }

  FakeProvider otherwise(Reply reply) {
    otherwise = reply;
    return this;
  }

  OpenAiChatModel chatModel(OpenAiHttpClientBuilderCustomizer... http) {
    OpenAiChatModel.Builder builder =
        OpenAiChatModel.builder()
            .options(
                OpenAiChatOptions.builder()
                    .baseUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/v1")
                    .apiKey("test")
                    .model("default")
                    .timeout(Duration.ofSeconds(60))
                    .maxRetries(0)
                    .build());
    List.of(http).forEach(builder::httpClientBuilderCustomizer);
    return builder.build();
  }

  @Override
  public void close() {
    server.stop(0);
  }

  private void reply(HttpExchange exchange) throws IOException {
    String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
    bodies.add(body);
    Reply reply = script.isEmpty() ? otherwise : script.poll();
    try (exchange) {
      if (reply.ending() == Ending.SILENT) {
        pause(SILENT_FOR);
      } else if (body.contains("\"stream\":true")) {
        stream(exchange, reply);
      } else {
        whole(exchange, reply);
      }
    }
  }

  private void whole(HttpExchange exchange, Reply reply) throws IOException {
    if (reply.ending() != Ending.WHOLE) {
      pause(SILENT_FOR);
      return;
    }
    byte[] json =
        """
        {"id":"x","object":"chat.completion","created":0,"model":"fast",
         "choices":[{"index":0,"finish_reason":"%s","message":{"role":"assistant","content":%s}}],
         "usage":{"prompt_tokens":7,"completion_tokens":5,"total_tokens":12}}
        """
            .formatted(reply.finishReason(), quoted(reply.content()))
            .getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().add("Content-Type", "application/json");
    exchange.sendResponseHeaders(200, json.length);
    exchange.getResponseBody().write(json);
  }

  private void stream(HttpExchange exchange, Reply reply) throws IOException {
    exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
    exchange.sendResponseHeaders(200, 0);
    OutputStream out = exchange.getResponseBody();
    boolean usageFirst = reply.usage() == Usage.PROMPT_ONLY_AT_THE_START;
    event(
        out,
        chunk("{\"role\":\"assistant\",\"content\":\"\"}", null, usageFirst ? usage(7, 0) : null));
    for (String thought : halves(reply.reasoning())) {
      event(out, chunk("{\"reasoning_content\":" + quoted(thought) + "}", null, null));
    }
    for (String piece : halves(reply.content())) {
      event(out, chunk("{\"content\":" + quoted(piece) + "}", null, null));
    }
    switch (reply.ending()) {
      case STALLS -> pingUntilTheClientCloses(out);
      case ERROR_EVENT ->
          event(out, "{\"error\":{\"message\":\"overloaded\",\"type\":\"server_error\"}}");
      default -> {
        event(out, chunk("{}", reply.finishReason(), null));
        if (!usageFirst) {
          event(
              out,
              "{\"id\":\"x\",\"object\":\"chat.completion.chunk\",\"created\":0,"
                  + "\"model\":\"fast\",\"choices\":[],\"usage\":"
                  + usage(7, 5)
                  + "}");
        }
        event(out, "[DONE]");
      }
    }
  }

  private void pingUntilTheClientCloses(OutputStream out) {
    long started = System.nanoTime();
    try {
      while (System.nanoTime() - started < SILENT_FOR.toNanos()) {
        out.write(": ping\n\n".getBytes(StandardCharsets.UTF_8));
        out.flush();
        pause(BETWEEN_PINGS);
      }
    } catch (IOException closed) {
      closedByTheClientAfter.add(Duration.ofNanos(System.nanoTime() - started));
    }
  }

  private static void event(OutputStream out, String data) throws IOException {
    out.write(("data: " + data + "\n\n").getBytes(StandardCharsets.UTF_8));
    out.flush();
  }

  private static String chunk(String delta, String finishReason, String usage) {
    return "{\"id\":\"x\",\"object\":\"chat.completion.chunk\",\"created\":0,\"model\":\"fast\","
        + "\"choices\":[{\"index\":0,\"delta\":"
        + delta
        + ",\"finish_reason\":"
        + (finishReason == null ? "null" : quoted(finishReason))
        + "}]"
        + (usage == null ? "" : ",\"usage\":" + usage)
        + "}";
  }

  private static String usage(int prompt, int completion) {
    return "{\"prompt_tokens\":%d,\"completion_tokens\":%d,\"total_tokens\":%d}"
        .formatted(prompt, completion, prompt + completion);
  }

  private static List<String> halves(String text) {
    if (text.isEmpty()) {
      return List.of();
    }
    int middle = text.length() / 2;
    return middle == 0 ? List.of(text) : List.of(text.substring(0, middle), text.substring(middle));
  }

  private static String quoted(String s) {
    return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
  }

  private static void pause(Duration time) {
    try {
      Thread.sleep(time);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }
}
