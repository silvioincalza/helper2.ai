package ai.helper2.platform.http;

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
import org.apache.hc.core5.http.io.EofSensorInputStream;
import org.apache.hc.core5.http.ProtocolException;
import org.apache.hc.core5.http.protocol.HttpContext;
import org.apache.hc.core5.util.Timeout;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpRequest;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.http.client.observation.ClientRequestObservationContext;
import org.springframework.http.client.observation.DefaultClientRequestObservationConvention;
import org.springframework.web.client.RestClient;

/**
 * Outbound HTTP for every module that calls a remote API (sources, embeddings): bounded timeouts, an
 * identifying User-Agent, bounded response bodies for source calls, and observed calls. Each request records one {@code http.client.requests} timer and
 * one client span, with trace context propagated to the remote host; the {@code uri} tag and the span URL never
 * carry the query string (ADR 0006).
 */
public final class ObservedHttp {

    /** Names the release: the boot jar's manifest carries the build version (ADR 0029); "dev" when run from classes. */
    public static final String USER_AGENT = userAgent(ObservedHttp.class.getPackage().getImplementationVersion());

    /** Longest wait for a free pooled connection: a full pool means the remote host is already struggling. */
    public static final Duration POOL_WAIT = Duration.ofMillis(250);

    /** Largest response body a source call reads, unless the call sets its own cap with {@link #maxResponseBytes}. */
    public static final int MAX_RESPONSE_BYTES = 2 * 1024 * 1024;

    private static final String MAX_RESPONSE_BYTES_ATTRIBUTE = ObservedHttp.class.getName() + ".maxResponseBytes";
    private static final String DEADLINE_ATTRIBUTE = ObservedHttp.class.getName() + ".deadline";

    private ObservedHttp() {
    }

    static String userAgent(String version) {
        String v = version == null || version.isBlank() ? "dev" : version;
        return "Helper2.ai-metasearch/" + v + " (+https://helper2.ai/sources)";
    }

    /**
     * Builder for source calls: redirects followed unless they downgrade https to http; response bodies read up
     * to {@link #MAX_RESPONSE_BYTES} or the call's own {@link #maxResponseBytes}; the call ends at its deadline,
     * here {@code timeout} (see {@link #restClientBuilder(Duration, Duration, ObservationRegistry)}).
     *
     * Timeouts: {@code timeout} once as the connect timeout and once as the client's single response timeout
     * (time to wait for response data), plus a short {@link #POOL_WAIT}. The response timeout is not repeated
     * as a socket timeout or a request-factory read timeout, so a straggler whose search was already given up
     * (and cancelled) frees its pooled connection after about the timeout, not a multiple of it.
     */
    public static RestClient.Builder restClientBuilder(Duration timeout, ObservationRegistry registry) {
        return restClientBuilder(timeout, timeout, registry);
    }

    /**
     * As {@link #restClientBuilder(Duration, ObservationRegistry)}, with a total {@code deadline} per call. The response
     * timeout applies to each read, so a body that keeps trickling in never trips it; the deadline does. Its clock
     * starts once, before the request is sent, so the pool wait, the connect and the wait for the headers count
     * inside it (never on top). It is checked on every read of the body: past it, the read throws
     * {@link DeadlineExceeded}. A call may set its own with {@link #deadline}.
     */
    public static RestClient.Builder restClientBuilder(Duration timeout, Duration deadline, ObservationRegistry registry) {
        return observed(requestFactory(timeout, POOL_WAIT, true), registry)
            .requestInterceptor(new BoundedResponse(MAX_RESPONSE_BYTES, deadline, System::nanoTime));
    }

    /**
     * Only the total deadline, no body cap: for a client that is not built by {@link #restClientBuilder}, such as the
     * embedding client, add it with {@code builder.requestInterceptor(deadlineOnly(d))}.
     */
    public static ClientHttpRequestInterceptor deadlineOnly(Duration deadline) {
        return new BoundedResponse(Long.MAX_VALUE, deadline, System::nanoTime);
    }

    /**
     * Request attributes for a call that may take up to {@code deadline} in all (e.g. a big README), instead of the
     * builder's: {@code http.get().uri(...).attributes(deadline(d))}.
     */
    public static Consumer<Map<String, Object>> deadline(Duration deadline) {
        return attributes -> attributes.put(DEADLINE_ATTRIBUTE, deadline);
    }

    /** Thrown on a read of the body once the call's deadline has passed; RestClient wraps it, {@link #isDeadlineExceeded} finds it. */
    public static final class DeadlineExceeded extends IOException {
        DeadlineExceeded(Duration deadline) {
            super("deadline of " + deadline.toMillis() + " ms exceeded");
        }
    }

    /** Whether {@code failure}, or one of its causes, is a read past the call's deadline. */
    public static boolean isDeadlineExceeded(Throwable failure) {
        for (Throwable t = failure; t != null; t = t.getCause()) {
            if (t instanceof DeadlineExceeded) {
                return true;
            }
        }
        return false;
    }

    /**
     * Request attributes for a call that may read up to {@code bytes} (e.g. a README), instead of
     * {@link #MAX_RESPONSE_BYTES}: {@code http.get().uri(...).attributes(maxResponseBytes(n))}.
     */
    public static Consumer<Map<String, Object>> maxResponseBytes(int bytes) {
        return attributes -> attributes.put(MAX_RESPONSE_BYTES_ATTRIBUTE, bytes);
    }

    /** Thrown while reading a body past its cap; RestClient wraps it, {@link #isTooLarge} finds it. */
    public static final class ResponseTooLarge extends IOException {
        ResponseTooLarge(long cap) {
            super("response larger than " + cap + " bytes");
        }
    }

    /** Whether {@code failure}, or one of its causes, is a body read past its cap. */
    public static boolean isTooLarge(Throwable failure) {
        for (Throwable t = failure; t != null; t = t.getCause()) {
            if (t instanceof ResponseTooLarge) {
                return true;
            }
        }
        return false;
    }

    /**
     * Wraps each response body in a stream that fails once more bytes than the call's cap arrive, or on a read past the
     * call's deadline. The deadline's clock starts here, before the request goes out.
     */
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
        public ClientHttpResponse intercept(HttpRequest request, byte[] body, ClientHttpRequestExecution execution) throws IOException {
            long start = ticker.getAsLong();
            Object cap = request.getAttributes().get(MAX_RESPONSE_BYTES_ATTRIBUTE);
            long max = cap instanceof Integer n ? n : defaultMax;
            Duration deadline = request.getAttributes().get(DEADLINE_ATTRIBUTE) instanceof Duration d ? d : defaultDeadline;
            ClientHttpResponse response = execution.execute(request, body);
            return new ClientHttpResponse() {
                private InputStream bounded;

                @Override
                public InputStream getBody() throws IOException {
                    if (bounded == null) {
                        InputStream raw = response.getBody();
                        bounded = new CappedStream(raw, max, start, deadline.toNanos(), ticker, () -> abort(raw));
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

    /**
     * Drops the connection under a body we stop reading at its deadline. A plain close would read the rest first: Spring
     * drains the body on close, and Apache reads a chunked or sized body to its end to reuse the connection, so a body
     * that trickles in would hold the call until it ends, deadline or not.
     */
    private static void abort(InputStream raw) {
        try {
            if (raw instanceof EofSensorInputStream apache) {
                apache.abort();
            } else {
                raw.close();
            }
        } catch (IOException ignored) {
            // The call fails with DeadlineExceeded anyway.
        }
    }

    /**
     * A body read up to [max] bytes and until [start] + [deadlineNanos] on [ticker] (nanoseconds). The deadline is
     * checked before each read and again after it, as one read may wait up to the response timeout. Past it,
     * [onDeadline] runs once (it drops the connection), then the read throws {@link DeadlineExceeded}.
     */
    static final class CappedStream extends FilterInputStream {
        private final long max;
        private final long start;
        private final long deadlineNanos;
        private final LongSupplier ticker;
        private final Runnable onDeadline;
        private boolean expired;
        private long read;

        CappedStream(InputStream in, long max, long start, long deadlineNanos, LongSupplier ticker) {
            this(in, max, start, deadlineNanos, ticker, () -> {
            });
        }

        CappedStream(InputStream in, long max, long start, long deadlineNanos, LongSupplier ticker, Runnable onDeadline) {
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

    /**
     * Pooled Apache client: {@code timeout} as connect and response timeout, {@code poolWait} for a free
     * connection; redirects either followed without an https-to-http downgrade, or not followed at all. Each request
     * is sent once: retries are the caller's resilience policy (ADR 0033), so they are counted and bounded there.
     */
    public static HttpComponentsClientHttpRequestFactory requestFactory(Duration timeout, Duration poolWait, boolean followRedirects) {
        ConnectionConfig connection = ConnectionConfig.custom().setConnectTimeout(Timeout.of(timeout)).build();
        PoolingHttpClientConnectionManager pool = PoolingHttpClientConnectionManagerBuilder.create()
            .setDefaultConnectionConfig(connection).setMaxConnTotal(50).setMaxConnPerRoute(20).build();
        RequestConfig request = RequestConfig.custom()
            .setResponseTimeout(Timeout.of(timeout)).setConnectionRequestTimeout(Timeout.of(poolWait)).build();
        HttpClientBuilder client = HttpClientBuilder.create().setConnectionManager(pool).setDefaultRequestConfig(request)
            .disableAutomaticRetries();
        if (followRedirects) {
            client.setRedirectStrategy(NoDowngradeRedirects.INSTANCE);
        } else {
            client.disableRedirectHandling();
        }
        return new HttpComponentsClientHttpRequestFactory(client.build());
    }

    /** A builder over {@code factory} that sends the User-Agent and is observed with the bounded URI convention. */
    public static RestClient.Builder observed(ClientHttpRequestFactory factory, ObservationRegistry registry) {
        return RestClient.builder().requestFactory(factory).defaultHeader(HttpHeaders.USER_AGENT, USER_AGENT)
            .observationRegistry(registry).observationConvention(BoundedUriConvention.INSTANCE);
    }

    /**
     * Follows redirects except https to http. The inherited {@link DefaultRedirectStrategy#isRedirectAllowed}
     * refuses a redirect to another scheme, host or port while the request carries {@code Authorization}, so a
     * source token never reaches a host other than the configured one (TokenRedirectTest).
     */
    static final class NoDowngradeRedirects extends DefaultRedirectStrategy {
        static final NoDowngradeRedirects INSTANCE = new NoDowngradeRedirects();

        @Override
        public boolean isRedirected(org.apache.hc.core5.http.HttpRequest request, HttpResponse response, HttpContext context)
            throws ProtocolException {
            if (!super.isRedirected(request, response, context)) {
                return false;
            }
            Header location = response.getFirstHeader(HttpHeaders.LOCATION);
            boolean downgrade = "https".equalsIgnoreCase(request.getScheme()) && location != null
                && location.getValue().regionMatches(true, 0, "http:", 0, 5);
            return !downgrade;
        }
    }

    private static final Pattern ID_SEGMENT = Pattern.compile("^(\\d+|[0-9a-fA-F]{8}(-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}|[0-9a-fA-F]{24,})$");

    /**
     * The default convention tags {@code uri} "none" for calls built with a UriBuilder lambda (all our search calls).
     * Fall back to the request path, never the query string, with numeric and uuid-like segments collapsed to
     * {@code {id}}, so the tag stays bounded. Calls with a URI template keep the template.
     */
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

        /** The span's {@code http.url} without query or fragment: queries carry visitors' search text. */
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
