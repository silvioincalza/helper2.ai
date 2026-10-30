package ai.helper2.platform.http;

import io.micrometer.common.KeyValue;
import io.micrometer.observation.ObservationRegistry;
import java.net.URI;
import java.time.Duration;
import java.util.regex.Pattern;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.DefaultRedirectStrategy;
import org.apache.hc.client5.http.impl.classic.HttpClientBuilder;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.core5.http.Header;
import org.apache.hc.core5.http.HttpRequest;
import org.apache.hc.core5.http.HttpResponse;
import org.apache.hc.core5.http.ProtocolException;
import org.apache.hc.core5.http.protocol.HttpContext;
import org.apache.hc.core5.util.Timeout;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.http.client.observation.ClientRequestObservationContext;
import org.springframework.http.client.observation.DefaultClientRequestObservationConvention;
import org.springframework.web.client.RestClient;

/**
 * Outbound HTTP for every module that calls a remote API (sources, embeddings): bounded timeouts, an
 * identifying User-Agent, and observed calls. Each request records one {@code http.client.requests} timer and
 * one client span, with trace context propagated to the remote host; the {@code uri} tag and the span URL never
 * carry the query string (ADR 0006).
 */
public final class ObservedHttp {

    /** Names the release: the boot jar's manifest carries the build version (ADR 0029); "dev" when run from classes. */
    public static final String USER_AGENT = userAgent(ObservedHttp.class.getPackage().getImplementationVersion());

    /** Longest wait for a free pooled connection: a full pool means the remote host is already struggling. */
    public static final Duration POOL_WAIT = Duration.ofMillis(250);

    private ObservedHttp() {
    }

    static String userAgent(String version) {
        String v = version == null || version.isBlank() ? "dev" : version;
        return "Helper2.ai-metasearch/" + v + " (+https://helper2.ai/sources)";
    }

    /**
     * Builder for source calls: redirects followed unless they downgrade https to http.
     *
     * Timeouts: {@code timeout} once as the connect timeout and once as the client's single response timeout
     * (time to wait for response data), plus a short {@link #POOL_WAIT}. The response timeout is not repeated
     * as a socket timeout or a request-factory read timeout, so a straggler whose search was already given up
     * (and cancelled) frees its pooled connection after about the timeout, not a multiple of it.
     */
    public static RestClient.Builder restClientBuilder(Duration timeout, ObservationRegistry registry) {
        return observed(requestFactory(timeout, POOL_WAIT, true), registry);
    }

    /**
     * Pooled Apache client: {@code timeout} as connect and response timeout, {@code poolWait} for a free
     * connection; redirects either followed without an https-to-http downgrade, or not followed at all.
     */
    public static HttpComponentsClientHttpRequestFactory requestFactory(Duration timeout, Duration poolWait, boolean followRedirects) {
        ConnectionConfig connection = ConnectionConfig.custom().setConnectTimeout(Timeout.of(timeout)).build();
        PoolingHttpClientConnectionManager pool = PoolingHttpClientConnectionManagerBuilder.create()
            .setDefaultConnectionConfig(connection).setMaxConnTotal(50).setMaxConnPerRoute(20).build();
        RequestConfig request = RequestConfig.custom()
            .setResponseTimeout(Timeout.of(timeout)).setConnectionRequestTimeout(Timeout.of(poolWait)).build();
        HttpClientBuilder client = HttpClientBuilder.create().setConnectionManager(pool).setDefaultRequestConfig(request);
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
        public boolean isRedirected(HttpRequest request, HttpResponse response, HttpContext context)
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
