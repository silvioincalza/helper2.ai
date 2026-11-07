package ai.helper2.testing;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/** Tiny HTTP server replaying recorded API responses. Boundary tests only; no network. */
public final class FixtureServer implements AutoCloseable {

  /**
   * [delayMs] before the headers; [dripMs] &gt; 0 sends the body one byte at a time with that pause
   * before each byte, so every read gets data quickly while the whole body takes long (a per-read
   * timeout never fires).
   */
  public record Reply(
      int status, String body, Map<String, String> headers, long delayMs, long dripMs) {
    public Reply(int status, String body, Map<String, String> headers, long delayMs) {
      this(status, body, headers, delayMs, 0);
    }

    public static Reply ok(String body) {
      return new Reply(200, body, Map.of(), 0);
    }

    /** 200 with [body] sent byte by byte, [dripMs] apart. */
    public static Reply drip(String body, long dripMs) {
      return new Reply(200, body, Map.of(), 0, dripMs);
    }
  }

  public final List<String> requests = new CopyOnWriteArrayList<>();
  public final List<Map<String, List<String>>> requestHeaders = new CopyOnWriteArrayList<>();
  public final List<String> requestBodies = new CopyOnWriteArrayList<>();
  private final Map<String, Reply> routes = new ConcurrentHashMap<>();
  private final HttpServer server;

  public FixtureServer() {
    try {
      server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    server.createContext(
        "/",
        exchange -> {
          String uri = exchange.getRequestURI().toString();
          requests.add(uri);
          requestHeaders.add(Map.copyOf(exchange.getRequestHeaders()));
          requestBodies.add(
              new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
          Reply reply =
              // The longest matching prefix wins, so "/repos/a/b" and "/repos/a/b/readme" can
              // both be routed.
              routes.entrySet().stream()
                  .filter(e -> uri.startsWith(e.getKey()))
                  .max(java.util.Comparator.comparingInt(e -> e.getKey().length()))
                  .map(Map.Entry::getValue)
                  .orElse(new Reply(404, "{}", Map.of(), 0));
          try {
            Thread.sleep(reply.delayMs());
          } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
          }
          reply.headers().forEach((k, v) -> exchange.getResponseHeaders().add(k, v));
          exchange.getResponseHeaders().add("Content-Type", "application/json");
          byte[] bytes = reply.body().getBytes(StandardCharsets.UTF_8);
          // A dripped body goes chunked: the server sends each flushed chunk at once, while a body
          // of fixed length is held back until the exchange closes.
          exchange.sendResponseHeaders(reply.status(), reply.dripMs() > 0 ? 0 : bytes.length);
          if (reply.dripMs() > 0) {
            try {
              exchange.getResponseBody().flush();
              for (byte b : bytes) {
                Thread.sleep(reply.dripMs());
                exchange.getResponseBody().write(b);
                exchange.getResponseBody().flush();
              }
            } catch (InterruptedException ignored) {
              Thread.currentThread().interrupt();
            } catch (IOException clientGone) {
              // The client stopped reading (its deadline): nothing left to send.
            }
          } else {
            exchange.getResponseBody().write(bytes);
          }
          exchange.close();
        });
    server.start();
  }

  public FixtureServer route(String pathPrefix, Reply reply) {
    routes.put(pathPrefix, reply);
    return this;
  }

  public String baseUrl() {
    return "http://127.0.0.1:" + server.getAddress().getPort();
  }

  public static String fixture(String name) {
    try (InputStream in = FixtureServer.class.getResourceAsStream("/fixtures/" + name)) {
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException | NullPointerException e) {
      throw new IllegalStateException("missing fixture " + name, e);
    }
  }

  @Override
  public void close() {
    server.stop(0);
  }
}
