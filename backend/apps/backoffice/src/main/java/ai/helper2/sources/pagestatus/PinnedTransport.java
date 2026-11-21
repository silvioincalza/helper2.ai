package ai.helper2.sources.pagestatus;

import ai.helper2.platform.http.ObservedHttp;
import ai.helper2.sources.research.PublicAddresses;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import org.apache.hc.client5.http.classic.methods.HttpUriRequestBase;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.ManagedHttpClientConnectionFactory;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.core5.http.ClassicHttpResponse;
import org.apache.hc.core5.http.Header;
import org.apache.hc.core5.http.HttpEntity;
import org.apache.hc.core5.http.config.Http1Config;

/**
 * One request of our own to a site, and nothing more than the request: it connects only to the
 * addresses already checked, follows no redirect, sends no cookie, no credential and no referrer,
 * tries once, and reads of the body at most what the caller asks for (nothing, for a status).
 */
final class PinnedTransport implements LivePageStatusReader.Transport {

  static final Duration CONNECT = Duration.ofSeconds(3);

  static final Duration ANSWER = Duration.ofSeconds(5);

  static final Duration WHOLE_CALL = Duration.ofSeconds(8);

  static final int MAX_HEADERS = 100;

  static final int MAX_HEADER_LINE = 8 * 1024;

  private static final ScheduledExecutorService DEADLINES =
      Executors.newSingleThreadScheduledExecutor(
          Thread.ofPlatform().daemon().name("page-status-deadline").factory());

  @Override
  public LivePageStatusReader.Answer send(
      String method, URI url, List<InetAddress> checked, int maxBodyBytes) throws IOException {
    try (CloseableHttpClient http = client(url.getHost(), checked)) {
      HttpUriRequestBase request = new HttpUriRequestBase(method, url);
      request.setHeader("User-Agent", ObservedHttp.USER_AGENT);
      request.setHeader("Accept", "*/*");
      ScheduledFuture<?> cancel =
          DEADLINES.schedule(request::cancel, WHOLE_CALL.toMillis(), TimeUnit.MILLISECONDS);
      ClassicHttpResponse response = http.executeOpen(null, request, null);
      try {
        Header location = response.getFirstHeader("Location");
        return new LivePageStatusReader.Answer(
            response.getCode(),
            location == null ? null : location.getValue(),
            body(response.getEntity(), maxBodyBytes));
      } finally {
        cancel.cancel(false);
        drop(request, response);
      }
    }
  }

  /** The connection is dropped, not released: what is left of the body is never read. */
  private static void drop(HttpUriRequestBase request, ClassicHttpResponse response) {
    request.cancel();
    try {
      response.close();
    } catch (IOException alreadyDropped) {
      // Closing after the drop finds the socket closed: that is the drop.
    }
  }

  private static String body(HttpEntity entity, int maxBytes) throws IOException {
    if (entity == null || maxBytes <= 0) {
      return "";
    }
    try (InputStream in = entity.getContent()) {
      return new String(in.readNBytes(maxBytes), StandardCharsets.UTF_8);
    }
  }

  private static CloseableHttpClient client(String host, List<InetAddress> checked) {
    return HttpClients.custom()
        .setConnectionManager(
            PoolingHttpClientConnectionManagerBuilder.create()
                .setDnsResolver(PublicAddresses.pinned(host, checked))
                .setConnectionFactory(
                    ManagedHttpClientConnectionFactory.builder()
                        .http1Config(
                            Http1Config.custom()
                                .setMaxHeaderCount(MAX_HEADERS)
                                .setMaxLineLength(MAX_HEADER_LINE)
                                .build())
                        .build())
                .setDefaultConnectionConfig(
                    ConnectionConfig.custom()
                        .setConnectTimeout(CONNECT.toMillis(), TimeUnit.MILLISECONDS)
                        .setSocketTimeout((int) ANSWER.toMillis(), TimeUnit.MILLISECONDS)
                        .build())
                .build())
        .setDefaultRequestConfig(
            RequestConfig.custom()
                .setResponseTimeout(ANSWER.toMillis(), TimeUnit.MILLISECONDS)
                .build())
        .disableRedirectHandling()
        .disableAutomaticRetries()
        .disableCookieManagement()
        .disableAuthCaching()
        .disableContentCompression()
        .build();
  }
}
