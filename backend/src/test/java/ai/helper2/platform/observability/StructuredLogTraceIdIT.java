package ai.helper2.platform.observability;

import static org.assertj.core.api.Assertions.assertThat;

import ai.helper2.TestcontainersConfiguration;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.logging.logback.StructuredLogEncoder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.web.servlet.function.RouterFunction;
import org.springframework.web.servlet.function.RouterFunctions;
import org.springframework.web.servlet.function.ServerResponse;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * ADR 0026, decision 1: with tracing on (export off), every log line written inside a request, on
 * the request thread or on the search executor, and inside a scheduled job, carries {@code traceId}
 * and {@code spanId} in the logstash JSON format; the lines of one request share its trace id.
 *
 * <p>The lines are written by Spring Boot's own logstash encoder on an appender of this test. The
 * console format is not used: logging is set up once per JVM by the first test context, so another
 * test's plain-text format can win.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "helper2.sources.github.enabled=false",
      "helper2.sources.huggingface.enabled=false",
      "helper2.sources.npm.enabled=false",
      "helper2.sources.mcp-registry.enabled=false",
      "helper2.sources.arxiv.enabled=false",
      "helper2.sources.tavily.enabled=false",
      "management.tracing.sampling.probability=1.0",
      "management.tracing.export.enabled=false",
    })
@Import({TestcontainersConfiguration.class, StructuredLogTraceIdIT.LoggingEndpoints.class})
class StructuredLogTraceIdIT {

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final Logger LOG = (Logger) LoggerFactory.getLogger(LoggingEndpoints.class);

  @LocalServerPort int port;

  private final List<JsonNode> lines = new CopyOnWriteArrayList<>();
  private AppenderBase<ILoggingEvent> appender;
  private StructuredLogEncoder encoder;

  @BeforeEach
  void captureLogstashLines() {
    LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
    encoder = new StructuredLogEncoder();
    encoder.setContext(context);
    encoder.setFormat("logstash");
    encoder.start();
    appender =
        new AppenderBase<>() {
          @Override
          protected void append(ILoggingEvent event) {
            lines.add(JSON.readTree(new String(encoder.encode(event), StandardCharsets.UTF_8)));
          }
        };
    appender.setContext(context);
    appender.start();
    LOG.setLevel(Level.INFO);
    LOG.addAppender(appender);
  }

  @AfterEach
  void stopCapture() {
    LOG.detachAppender(appender);
    appender.stop();
    encoder.stop();
  }

  @Test
  void everyLineWrittenDuringARequestCarriesTheRequestsTraceAndSpanIds() throws Exception {
    String marker = UUID.randomUUID().toString();

    HttpResponse<String> response =
        HttpClient.newHttpClient()
            .send(
                HttpRequest.newBuilder(
                        URI.create("http://localhost:" + port + "/it/trace-log?marker=" + marker))
                    .build(),
                HttpResponse.BodyHandlers.ofString());

    assertThat(response.statusCode()).isEqualTo(200);
    List<JsonNode> written =
        lines.stream().filter(line -> line.path("message").asString("").contains(marker)).toList();
    assertThat(written).hasSize(2);
    assertThat(written)
        .allSatisfy(line -> {
          assertThat(line.path("traceId").asString("")).matches("[0-9a-f]{32}");
          assertThat(line.path("spanId").asString("")).matches("[0-9a-f]{16}");
        });
    assertThat(written.stream().map(line -> line.path("traceId").asString()).distinct()).hasSize(1);
  }

  @Test
  void aScheduledJobLogsWithATraceOfItsOwn() throws Exception {
    // The job runs every 200 ms; wait for a tick written while this test captures.
    long until = System.nanoTime() + Duration.ofSeconds(30).toNanos();
    while (ticks().isEmpty() && System.nanoTime() < until) {
      Thread.sleep(100);
    }

    assertThat(ticks()).isNotEmpty();
    assertThat(ticks().getFirst().path("traceId").asString("")).matches("[0-9a-f]{32}");
  }

  private List<JsonNode> ticks() {
    return lines.stream()
        .filter(line -> line.path("message").asString("").equals(LoggingEndpoints.TICK))
        .toList();
  }

  @TestConfiguration(proxyBeanMethods = false)
  static class LoggingEndpoints {

    static final String TICK = "structured-log-it scheduled tick";
    private static final org.slf4j.Logger log = LoggerFactory.getLogger(LoggingEndpoints.class);

    /**
     * A route, not a @RestController: component scanning would put a controller into every test
     * context.
     */
    @Bean
    RouterFunction<ServerResponse> traceLogRoute(
        @Qualifier("searchExecutor") ExecutorService searchExecutor) {
      return RouterFunctions.route()
          .GET(
              "/it/trace-log",
              request -> {
                String marker = request.param("marker").orElseThrow();
                log.info("on the request thread {}", marker);
                searchExecutor
                    .submit(() -> log.info("on the search executor {}", marker))
                    .get(5, TimeUnit.SECONDS);
                return ServerResponse.ok().body("ok");
              })
          .build();
    }

    @Bean
    Ticker structuredLogTicker() {
      return new Ticker();
    }

    static class Ticker {
      @Scheduled(fixedDelay = 200)
      void tick() {
        log.info(TICK);
      }
    }
  }
}
