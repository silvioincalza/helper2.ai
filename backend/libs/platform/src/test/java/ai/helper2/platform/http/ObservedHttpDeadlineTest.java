package ai.helper2.platform.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.helper2.testing.FixtureServer;
import io.micrometer.observation.ObservationRegistry;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

/**
 * A total deadline per call (ADR 0006 (part was 0006) §2): the response timeout applies per read,
 * so a body that arrives a byte at a time never trips it. The deadline counts from the start of the
 * call and stops the read once it has passed.
 */
class ObservedHttpDeadlineTest {

  final FixtureServer server = new FixtureServer();

  @AfterEach
  void stop() {
    server.close();
  }

  private static final Duration SECOND = Duration.ofSeconds(1);

  /** 20 bytes, 100 ms apart: about 2 s in all, and no single read waits longer than 100 ms. */
  private void dripTwoSeconds() {
    server.route("/drip", FixtureServer.Reply.drip("x".repeat(20), 100));
  }

  private static long millisSince(long start) {
    return (System.nanoTime() - start) / 1_000_000;
  }

  @Test
  void aBodyThatDripsPastTheDeadlineStopsAtTheDeadline() {
    dripTwoSeconds();
    RestClient http =
        ObservedHttp.restClientBuilder(SECOND, Duration.ofMillis(500), ObservationRegistry.NOOP)
            .baseUrl(server.baseUrl())
            .build();
    long start = System.nanoTime();

    assertThatThrownBy(() -> http.get().uri("/drip").retrieve().body(String.class))
        .satisfies(e ->
            assertThat(ObservedHttp.isDeadlineExceeded(e)).as("cause chain of %s", e).isTrue());
    assertThat(millisSince(start)).isBetween(400L, 1_200L);
  }

  @Test
  void withoutItsOwnDeadlineACallStopsAtItsTimeout() {
    dripTwoSeconds();
    RestClient http =
        ObservedHttp.restClientBuilder(SECOND, ObservationRegistry.NOOP)
            .baseUrl(server.baseUrl())
            .build();
    long start = System.nanoTime();

    assertThatThrownBy(() -> http.get().uri("/drip").retrieve().body(String.class))
        .satisfies(e -> assertThat(ObservedHttp.isDeadlineExceeded(e)).isTrue());
    assertThat(millisSince(start)).isBetween(900L, 1_700L);
  }

  @Test
  void aCallMaySetALongerDeadlineOfItsOwn() {
    dripTwoSeconds();
    RestClient http =
        ObservedHttp.restClientBuilder(SECOND, Duration.ofMillis(500), ObservationRegistry.NOOP)
            .baseUrl(server.baseUrl())
            .build();

    String body =
        http.get()
            .uri("/drip")
            .attributes(ObservedHttp.deadline(Duration.ofSeconds(5)))
            .retrieve()
            .body(String.class);

    assertThat(body).hasSize(20);
  }

  @Test
  void aFastResponseIsReadAsBefore() {
    server.route("/fast", FixtureServer.Reply.ok("{\"ok\":true}"));
    RestClient http =
        ObservedHttp.restClientBuilder(SECOND, Duration.ofMillis(500), ObservationRegistry.NOOP)
            .baseUrl(server.baseUrl())
            .build();

    assertThat(http.get().uri("/fast").retrieve().body(String.class)).isEqualTo("{\"ok\":true}");
  }

  /**
   * The stream itself, on a fake ticker: the clock starts before the request, each read checks it.
   */
  @Test
  void theCappedStreamChecksTheDeadlineOnEveryRead() throws Exception {
    AtomicLong nanos = new AtomicLong(1_000);
    long deadline = Duration.ofSeconds(2).toNanos();
    InputStream in =
        new ObservedHttp.CappedStream(
            new ByteArrayInputStream("abcdef".getBytes()), 100, nanos.get(), deadline, nanos::get);

    assertThat(in.read()).isEqualTo('a');
    nanos.addAndGet(deadline);
    assertThat(in.read(new byte[2], 0, 2))
        .as("exactly at the deadline is still in time")
        .isEqualTo(2);
    nanos.incrementAndGet();

    assertThatThrownBy(in::read).isInstanceOf(ObservedHttp.DeadlineExceeded.class);
    assertThatThrownBy(() -> in.read(new byte[2], 0, 2))
        .isInstanceOf(ObservedHttp.DeadlineExceeded.class);
  }

  @Test
  void theCappedStreamStillCapsTheSize() {
    InputStream in =
        new ObservedHttp.CappedStream(
            new ByteArrayInputStream(new byte[10]), 4, 0, Long.MAX_VALUE, () -> 0);

    assertThatThrownBy(() -> in.readAllBytes()).isInstanceOf(ObservedHttp.ResponseTooLarge.class);
  }
}
