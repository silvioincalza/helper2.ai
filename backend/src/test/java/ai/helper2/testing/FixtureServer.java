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

    public record Reply(int status, String body, Map<String, String> headers, long delayMs) {
        public static Reply ok(String body) {
            return new Reply(200, body, Map.of(), 0);
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
        server.createContext("/", exchange -> {
            String uri = exchange.getRequestURI().toString();
            requests.add(uri);
            requestHeaders.add(Map.copyOf(exchange.getRequestHeaders()));
            requestBodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            Reply reply = routes.entrySet().stream().filter(e -> uri.startsWith(e.getKey())).map(Map.Entry::getValue)
                .findFirst().orElse(new Reply(404, "{}", Map.of(), 0));
            try {
                Thread.sleep(reply.delayMs());
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            reply.headers().forEach((k, v) -> exchange.getResponseHeaders().add(k, v));
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            byte[] bytes = reply.body().getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(reply.status(), bytes.length);
            exchange.getResponseBody().write(bytes);
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
