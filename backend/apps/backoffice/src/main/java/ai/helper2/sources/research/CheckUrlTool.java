package ai.helper2.sources.research;

import ai.helper2.research.ResearchTool;
import ai.helper2.search.ShadowLibraries;
import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import org.apache.hc.client5.http.DnsResolver;
import org.apache.hc.client5.http.classic.methods.HttpHead;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.core5.http.Header;
import org.jspecify.annotations.Nullable;

public final class CheckUrlTool implements ResearchTool {

  static final String HTTPS_ONLY = "only https: the cluster reaches the web on port 443 only";

  private static final int MAX_HOPS = 5;
  private static final long TIMEOUT_SECONDS = 8;

  interface Transport {
    Hop head(URI url, List<InetAddress> addresses) throws IOException;
  }

  record Hop(int status, @Nullable String location) {}

  private final PublicAddresses addresses;
  private final Transport transport;

  public CheckUrlTool(ShadowLibraries shadowLibraries) {
    this(new PublicAddresses(shadowLibraries), CheckUrlTool::pinnedHead);
  }

  CheckUrlTool(PublicAddresses addresses, Transport transport) {
    this.addresses = addresses;
    this.transport = transport;
  }

  @Override
  public String name() {
    return "check_url";
  }

  @Override
  public String description() {
    return "Check that an https URL answers: the HTTP status of each hop and the final URL after redirects."
        + " Only https. Not evidence.";
  }

  @Override
  public String parameters() {
    return """
    {"type":"object","properties":{"url":{"type":"string"}},"required":["url"]}\
    """;
  }

  @Override
  public Output call(String arguments) {
    String url = Args.of(arguments).text("url");
    if (url == null) {
      return Output.error("url is required");
    }
    StringBuilder out = new StringBuilder();
    String current = url.strip();
    for (int hop = 0; hop <= MAX_HOPS; hop++) {
      URI uri;
      try {
        uri = URI.create(current);
      } catch (IllegalArgumentException e) {
        return Output.error(out + "not checked: " + current + ": not a URL");
      }
      String problem = problem(uri);
      if (problem != null) {
        return Output.error(out + "not checked: " + current + ": " + problem);
      }
      List<InetAddress> checked;
      try {
        checked = addresses.resolve(uri.getHost());
      } catch (PublicAddresses.NotPublic e) {
        return Output.error(out + "not checked: " + current + ": " + e.getMessage());
      }
      Hop response;
      try {
        response = transport.head(uri, checked);
      } catch (IOException e) {
        return Output.error(out + current + ": " + e.getClass().getSimpleName());
      }
      out.append(response.status()).append(' ').append(current).append('\n');
      if (response.status() / 100 != 3 || response.location() == null) {
        return new Output(out + "final: " + current, null);
      }
      current = uri.resolve(response.location()).toString();
    }
    return Output.error(out + "more than " + MAX_HOPS + " redirects");
  }

  private @Nullable String problem(URI uri) {
    String problem = addresses.urlProblem(uri);
    if (problem != null) {
      return problem;
    }
    String scheme = uri.getScheme().toLowerCase(Locale.ROOT);
    if (!scheme.equals("https") || uri.getPort() == 80) {
      return HTTPS_ONLY;
    }
    return null;
  }

  static Hop pinnedHead(URI url, List<InetAddress> checked) throws IOException {
    String host = url.getHost();
    DnsResolver pinned =
        new DnsResolver() {
          @Override
          public InetAddress[] resolve(String name) throws UnknownHostException {
            if (!name.equalsIgnoreCase(host)) {
              throw new UnknownHostException(name);
            }
            return checked.toArray(InetAddress[]::new);
          }

          @Override
          public String resolveCanonicalHostname(String name) {
            return name;
          }
        };
    try (CloseableHttpClient http =
        HttpClients.custom()
            .setConnectionManager(
                PoolingHttpClientConnectionManagerBuilder.create()
                    .setDnsResolver(pinned)
                    .setDefaultConnectionConfig(
                        ConnectionConfig.custom()
                            .setConnectTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                            .setSocketTimeout((int) TIMEOUT_SECONDS, TimeUnit.SECONDS)
                            .build())
                    .build())
            .setDefaultRequestConfig(
                RequestConfig.custom()
                    .setResponseTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    .build())
            .disableRedirectHandling()
            .disableAutomaticRetries()
            .disableCookieManagement()
            .build()) {
      HttpHead head = new HttpHead(url);
      head.setHeader("User-Agent", "helper2.ai research");
      return http.execute(
          head,
          response -> {
            Header location = response.getFirstHeader("Location");
            return new Hop(response.getCode(), location == null ? null : location.getValue());
          });
    }
  }
}
