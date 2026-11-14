package ai.helper2.platform.http;

import ai.helper2.platform.config.HttpClientProperties;
import io.micrometer.common.KeyValue;
import io.micrometer.observation.ObservationRegistry;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.time.Duration;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.regex.Pattern;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.DefaultRedirectStrategy;
import org.apache.hc.client5.http.impl.classic.HttpClientBuilder;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.core5.http.Header;
import org.apache.hc.core5.http.HttpResponse;
import org.apache.hc.core5.http.ProtocolException;
import org.apache.hc.core5.http.io.EofSensorInputStream;
import org.apache.hc.core5.http.protocol.HttpContext;
import org.apache.hc.core5.util.Timeout;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpRequest;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.http.client.observation.ClientRequestObservationContext;
import org.springframework.http.client.observation.DefaultClientRequestObservationConvention;
import org.springframework.web.client.RestClient;

public final class ObservedHttp {
  public static final String USER_AGENT =
      userAgent(ObservedHttp.class.getPackage().getImplementationVersion());

  private static final String MAX_RESPONSE_BYTES_ATTRIBUTE =
      ObservedHttp.class.getName() + ".maxResponseBytes";
  private static final String DEADLINE_ATTRIBUTE = ObservedHttp.class.getName() + ".deadline";

  private ObservedHttp() {}

  static String userAgent(String version) {
    String v = version == null || version.isBlank() ? "dev" : version;
    return "Helper2.ai-metasearch/" + v + " (+https://helper2.ai/sources)";
  }

  public static RestClient.Builder restClientBuilder(
      Duration timeout, ObservationRegistry registry, HttpClientProperties http) {
    return restClientBuilder(timeout, timeout, registry, http);
  }

  public static RestClient.Builder restClientBuilder(
      Duration timeout,
      Duration deadline,
      ObservationRegistry registry,
      HttpClientProperties http) {
    return observed(requestFactory(timeout, http.poolWait(), true, http), registry)
        .requestInterceptor(
            new BoundedResponse(http.maxResponseSize().toBytes(), deadline, System::nanoTime));
  }

  public static ClientHttpRequestInterceptor deadlineOnly(Duration deadline) {
    return new BoundedResponse(Long.MAX_VALUE, deadline, System::nanoTime);
  }

  public static Consumer<Map<String, Object>> deadline(Duration deadline) {
    return attributes -> attributes.put(DEADLINE_ATTRIBUTE, deadline);
  }

  public static final class DeadlineExceeded extends IOException {
    DeadlineExceeded(Duration deadline) {
      super("deadline of " + deadline.toMillis() + " ms exceeded");
    }
  }

  public static boolean isDeadlineExceeded(Throwable failure) {
    for (Throwable t = failure; t != null; t = t.getCause()) {
      if (t instanceof DeadlineExceeded) {
        return true;
      }
    }
    return false;
  }

  public static Consumer<Map<String, Object>> maxResponseBytes(int bytes) {
    return attributes -> attributes.put(MAX_RESPONSE_BYTES_ATTRIBUTE, bytes);
  }

  public static final class ResponseTooLarge extends IOException {
    ResponseTooLarge(long cap) {
      super("response larger than " + cap + " bytes");
    }
  }

  public static boolean isTooLarge(Throwable failure) {
    for (Throwable t = failure; t != null; t = t.getCause()) {
      if (t instanceof ResponseTooLarge) {
        return true;
      }
    }
    return false;
  }

  static final class BoundedResponse implements ClientHttpRequestInterceptor {
    private final long defaultMax;
    private final Duration defaultDeadline;
    private final LongSupplier ticker;

    BoundedResponse(long defaultMax, Duration defaultDeadline, LongSupplier ticker) {
      this.defaultMax = defaultMax;
      this.defaultDeadline = defaultDeadline;
      this.ticker = ticker;
    }

    @Override
    public ClientHttpResponse intercept(
        HttpRequest request, byte[] body, ClientHttpRequestExecution execution) throws IOException {
      long start = ticker.getAsLong();
      Object cap = request.getAttributes().get(MAX_RESPONSE_BYTES_ATTRIBUTE);
      long max = cap instanceof Integer n ? n : defaultMax;
      Duration deadline =
          request.getAttributes().get(DEADLINE_ATTRIBUTE) instanceof Duration d
              ? d
              : defaultDeadline;
      ClientHttpResponse response = execution.execute(request, body);
      return new ClientHttpResponse() {
        private InputStream bounded;

        @Override
        public InputStream getBody() throws IOException {
          if (bounded == null) {
            InputStream raw = response.getBody();
            bounded =
                new CappedStream(raw, max, start, deadline.toNanos(), ticker, () -> abort(raw));
          }
          return bounded;
        }

        @Override
        public HttpHeaders getHeaders() {
          return response.getHeaders();
        }

        @Override
        public HttpStatusCode getStatusCode() throws IOException {
          return response.getStatusCode();
        }

        @Override
        public String getStatusText() throws IOException {
          return response.getStatusText();
        }

        @Override
        public void close() {
          response.close();
        }
      };
    }
  }

  private static void abort(InputStream raw) {
    try {
      if (raw instanceof EofSensorInputStream apache) {
        apache.abort();
      } else {
        raw.close();
      }
    } catch (IOException ignored) {
    }
  }

  static final class CappedStream extends FilterInputStream {
    private final long max;
    private final long start;
    private final long deadlineNanos;
    private final LongSupplier ticker;
    private final Runnable onDeadline;
    private boolean expired;
    private long read;

    CappedStream(InputStream in, long max, long start, long deadlineNanos, LongSupplier ticker) {
      this(in, max, start, deadlineNanos, ticker, () -> {});
    }

    CappedStream(
        InputStream in,
        long max,
        long start,
        long deadlineNanos,
        LongSupplier ticker,
        Runnable onDeadline) {
      super(in);
      this.max = max;
      this.start = start;
      this.deadlineNanos = deadlineNanos;
      this.ticker = ticker;
      this.onDeadline = onDeadline;
    }

    @Override
    public int read() throws IOException {
      inTime();
      int b = super.read();
      inTime();
      if (b != -1) {
        count(1);
      }
      return b;
    }

    @Override
    public int read(byte[] buf, int off, int len) throws IOException {
      inTime();
      int n = super.read(buf, off, len);
      inTime();
      if (n > 0) {
        count(n);
      }
      return n;
    }

    private void inTime() throws DeadlineExceeded {
      if (expired || ticker.getAsLong() - start > deadlineNanos) {
        if (!expired) {
          expired = true;
          onDeadline.run();
        }
        throw new DeadlineExceeded(Duration.ofNanos(deadlineNanos));
      }
    }

    private void count(int n) throws IOException {
      read += n;
      if (read > max) {
        throw new ResponseTooLarge(max);
      }
    }
  }

  public static HttpComponentsClientHttpRequestFactory requestFactory(
      Duration timeout, Duration poolWait, boolean followRedirects, HttpClientProperties http) {
    ConnectionConfig connection =
        ConnectionConfig.custom().setConnectTimeout(Timeout.of(timeout)).build();
    PoolingHttpClientConnectionManager pool =
        PoolingHttpClientConnectionManagerBuilder.create()
            .setDefaultConnectionConfig(connection)
            .setMaxConnTotal(http.maxConnections())
            .setMaxConnPerRoute(http.maxConnectionsPerRoute())
            .build();
    RequestConfig request =
        RequestConfig.custom()
            .setResponseTimeout(Timeout.of(timeout))
            .setConnectionRequestTimeout(Timeout.of(poolWait))
            .build();
    HttpClientBuilder client =
        HttpClientBuilder.create()
            .setConnectionManager(pool)
            .setDefaultRequestConfig(request)
            .disableAutomaticRetries();
    if (followRedirects) {
      client.setRedirectStrategy(NoDowngradeRedirects.INSTANCE);
    } else {
      client.disableRedirectHandling();
    }
    return new HttpComponentsClientHttpRequestFactory(client.build());
  }

  public static RestClient.Builder observed(
      ClientHttpRequestFactory factory, ObservationRegistry registry) {
    return RestClient.builder()
        .requestFactory(factory)
        .defaultHeader(HttpHeaders.USER_AGENT, USER_AGENT)
        .observationRegistry(registry)
        .observationConvention(BoundedUriConvention.INSTANCE)
        .messageConverters(XmlConverters::remove);
  }

  static final class NoDowngradeRedirects extends DefaultRedirectStrategy {
    static final NoDowngradeRedirects INSTANCE = new NoDowngradeRedirects();

    @Override
    public boolean isRedirected(
        org.apache.hc.core5.http.HttpRequest request, HttpResponse response, HttpContext context)
        throws ProtocolException {
      if (!super.isRedirected(request, response, context)) {
        return false;
      }
      Header location = response.getFirstHeader(HttpHeaders.LOCATION);
      boolean downgrade =
          "https".equalsIgnoreCase(request.getScheme())
              && location != null
              && location.getValue().regionMatches(true, 0, "http:", 0, 5);
      return !downgrade;
    }
  }

  private static final Pattern ID_SEGMENT =
      Pattern.compile(
          "^(\\d+|[0-9a-fA-F]{8}(-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}|[0-9a-fA-F]{24,})$");

  static final class BoundedUriConvention extends DefaultClientRequestObservationConvention {
    static final BoundedUriConvention INSTANCE = new BoundedUriConvention();

    @Override
    protected KeyValue uri(ClientRequestObservationContext context) {
      if (context.getUriTemplate() != null || context.getCarrier() == null) {
        return super.uri(context);
      }
      String path = context.getCarrier().getURI().getRawPath();
      if (path == null || path.isEmpty()) {
        return super.uri(context);
      }
      StringBuilder out = new StringBuilder();
      for (String seg : path.split("/", -1)) {
        if (!out.isEmpty() || !seg.isEmpty()) {
          out.append('/').append(ID_SEGMENT.matcher(seg).matches() ? "{id}" : seg);
        }
      }
      return KeyValue.of("uri", out.isEmpty() ? "/" : out.toString());
    }

    @Override
    protected KeyValue requestUri(ClientRequestObservationContext context) {
      if (context.getCarrier() == null) {
        return super.requestUri(context);
      }
      URI u = context.getCarrier().getURI();
      String port = u.getPort() == -1 ? "" : ":" + u.getPort();
      String path = u.getRawPath() == null ? "" : u.getRawPath();
      return KeyValue.of("http.url", u.getScheme() + "://" + u.getHost() + port + path);
    }
  }
}
