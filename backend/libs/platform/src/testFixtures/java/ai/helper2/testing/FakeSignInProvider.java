package ai.helper2.testing;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.test.context.DynamicPropertyRegistry;

public final class FakeSignInProvider implements AutoCloseable {
  public static final String REGISTRATION = "fake";

  public static final long USER_ID = 4242;

  public static final String EMAIL = "fake-user@example.org";

  public static final String BIO = "fake-bio-text";
  public static final String LOCATION = "fake-location-text";
  public static final String COMPANY = "fake-company-text";

  public final String accessToken = "fake-access-" + UUID.randomUUID();

  private final HttpServer server;

  private FakeSignInProvider(HttpServer server) {
    this.server = server;
    server.createContext(
        "/token",
        ex ->
            json(
                ex,
                """
                {"access_token":"%s","token_type":"bearer","scope":"read:user"}\
                """
                    .formatted(accessToken)));
    server.createContext(
        "/user",
        ex ->
            json(
                ex,
                ("{\"id\":%d,\"login\":\"fake-user\",\"name\":\"Fake User\",\"email\":\"%s\","
                        + "\"bio\":\"%s\",\"location\":\"%s\",\"company\":\"%s\","
                        + "\"avatar_url\":\"https://avatars.example/fake\"}")
                    .formatted(USER_ID, EMAIL, BIO, LOCATION, COMPANY)));
    server.start();
  }

  public static FakeSignInProvider start() {
    try {
      return new FakeSignInProvider(HttpServer.create(new InetSocketAddress("localhost", 0), 0));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  public void register(DynamicPropertyRegistry registry) {
    String base = "http://localhost:" + server.getAddress().getPort();
    String reg = "spring.security.oauth2.client.registration." + REGISTRATION + ".";
    String provider = "spring.security.oauth2.client.provider." + REGISTRATION + ".";
    registry.add(reg + "client-id", () -> "fake-client");
    registry.add(reg + "client-secret", () -> "fake-secret");
    registry.add(reg + "authorization-grant-type", () -> "authorization_code");
    registry.add(reg + "redirect-uri", () -> "{baseUrl}/login/oauth2/code/{registrationId}");
    registry.add(reg + "scope", () -> "read:user");
    registry.add(provider + "authorization-uri", () -> base + "/authorize");
    registry.add(provider + "token-uri", () -> base + "/token");
    registry.add(provider + "user-info-uri", () -> base + "/user");
    registry.add(provider + "user-name-attribute", () -> "id");
  }

  public record SignedIn(int status, String location, Map<String, String> cookies) {
    public String cookieHeader() {
      return cookies.entrySet().stream()
          .map(c -> c.getKey() + "=" + c.getValue())
          .collect(Collectors.joining("; "));
    }
  }

  public SignedIn signIn(URI app) throws IOException, InterruptedException {
    HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
    Map<String, String> cookies = new LinkedHashMap<>();
    HttpResponse<String> start =
        http.send(
            HttpRequest.newBuilder(app.resolve("/oauth2/authorization/" + REGISTRATION)).build(),
            HttpResponse.BodyHandlers.ofString());
    keep(start, cookies);
    URI authorize = URI.create(start.headers().firstValue("Location").orElseThrow());
    String state = query(authorize).get("state");
    HttpRequest.Builder callback =
        HttpRequest.newBuilder(
            app.resolve("/login/oauth2/code/" + REGISTRATION + "?code=fake-code&state=" + state));
    if (!cookies.isEmpty()) {
      callback.header("Cookie", new SignedIn(0, "", cookies).cookieHeader());
    }
    HttpResponse<String> back = http.send(callback.build(), HttpResponse.BodyHandlers.ofString());
    keep(back, cookies);
    return new SignedIn(
        back.statusCode(), back.headers().firstValue("Location").orElse(""), Map.copyOf(cookies));
  }

  private static void keep(HttpResponse<?> response, Map<String, String> cookies) {
    for (String set : response.headers().allValues("Set-Cookie")) {
      String pair = set.split(";", 2)[0];
      int eq = pair.indexOf('=');
      cookies.put(pair.substring(0, eq).strip(), pair.substring(eq + 1).strip());
    }
  }

  private static Map<String, String> query(URI uri) {
    Map<String, String> params = new LinkedHashMap<>();
    for (String part : List.of(uri.getRawQuery().split("&"))) {
      int eq = part.indexOf('=');
      params.put(
          part.substring(0, eq), URLDecoder.decode(part.substring(eq + 1), StandardCharsets.UTF_8));
    }
    return params;
  }

  private static void json(HttpExchange ex, String body) throws IOException {
    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    ex.getRequestBody().readAllBytes();
    ex.getResponseHeaders().add("Content-Type", "application/json");
    ex.sendResponseHeaders(200, bytes.length);
    try (var out = ex.getResponseBody()) {
      out.write(bytes);
    }
  }

  @Override
  public void close() {
    server.stop(0);
  }
}
