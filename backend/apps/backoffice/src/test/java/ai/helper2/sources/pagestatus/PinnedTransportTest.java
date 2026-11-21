package ai.helper2.sources.pagestatus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** UC-030 BR-014: what the Scout's own request to a site sends and reads. */
class PinnedTransportTest {

  static final List<InetAddress> LOOPBACK = List.of(InetAddress.getLoopbackAddress());

  final List<String> methods = new ArrayList<>();
  final Map<String, List<String>> headers = new ConcurrentHashMap<>();
  final AtomicInteger bodyBytesSent = new AtomicInteger();
  HttpServer server;

  @BeforeEach
  void start() throws IOException {
    server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
    server.createContext(
        "/go",
        exchange -> {
          seen(exchange.getRequestMethod(), exchange.getRequestHeaders());
          exchange.getResponseHeaders().add("Location", "/landed");
          exchange.getResponseHeaders().add("Set-Cookie", "session=1");
          exchange.sendResponseHeaders(302, -1);
          exchange.close();
        });
    server.createContext(
        "/landed",
        exchange -> {
          seen(exchange.getRequestMethod(), exchange.getRequestHeaders());
          exchange.sendResponseHeaders(200, -1);
          exchange.close();
        });
    server.createContext(
        "/big",
        exchange -> {
          seen(exchange.getRequestMethod(), exchange.getRequestHeaders());
          exchange.sendResponseHeaders(200, 0);
          try (OutputStream out = exchange.getResponseBody()) {
            byte[] chunk = "x".repeat(8 * 1024).getBytes(StandardCharsets.US_ASCII);
            for (int i = 0; i < 4_096; i++) {
              out.write(chunk);
              bodyBytesSent.addAndGet(chunk.length);
            }
          } catch (IOException closedByTheClient) {
            // The client dropped the connection: the rest of the body is not sent.
          }
        });
    server.start();
  }

  @AfterEach
  void stop() {
    server.stop(0);
  }

  private synchronized void seen(String method, Map<String, List<String>> sent) {
    methods.add(method);
    headers.putAll(sent);
  }

  private URI url(String path) {
    return URI.create("http://site.test:" + server.getAddress().getPort() + path);
  }

  @Test
  void aRedirectIsAnAnswerWithItsLocationAndIsNotFollowed() throws IOException {
    LivePageStatusReader.Answer answer =
        new PinnedTransport().send("HEAD", url("/go"), LOOPBACK, 0);

    assertThat(answer.status()).isEqualTo(302);
    assertThat(answer.location()).isEqualTo("/landed");
    assertThat(methods).containsExactly("HEAD");
  }

  @Test
  void noCookieNoCredentialAndNoReferrerIsSentAndTheUserAgentNamesHelper2() throws IOException {
    PinnedTransport transport = new PinnedTransport();
    transport.send("HEAD", url("/go"), LOOPBACK, 0);
    transport.send("HEAD", url("/landed"), LOOPBACK, 0);

    assertThat(headers.keySet())
        .doesNotContain("Cookie", "Authorization", "Referer", "Proxy-authorization");
    assertThat(headers.get("User-agent"))
        .singleElement()
        .asString()
        .startsWith("Helper2.ai-metasearch/")
        .contains("https://helper2.ai/sources");
  }

  @Test
  void theBodyOfAGetForAStatusIsNotRead() throws IOException {
    long started = System.nanoTime();

    LivePageStatusReader.Answer answer =
        new PinnedTransport().send("GET", url("/big"), LOOPBACK, 0);

    assertThat(answer.status()).isEqualTo(200);
    assertThat(answer.body()).isEmpty();
    assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(5));
    assertThat(bodyBytesSent.get()).isLessThan(32 * 1024 * 1024);
  }

  @Test
  void aBodyThatIsAskedForIsReadUpToItsCap() throws IOException {
    LivePageStatusReader.Answer answer =
        new PinnedTransport().send("GET", url("/big"), LOOPBACK, 1_024);

    assertThat(answer.body()).hasSize(1_024);
  }

  @Test
  void theRequestGoesOnlyToTheCheckedAddressOfTheHostAsked() {
    assertThatThrownBy(() ->
            new PinnedTransport()
                .send(
                    "HEAD",
                    URI.create("http://site.test:" + server.getAddress().getPort() + "/go"),
                    List.of(InetAddress.getByAddress(new byte[] {(byte) 192, 0, 2, 1})),
                    0))
        .isInstanceOf(IOException.class);
    assertThat(methods).isEmpty();
  }
}
