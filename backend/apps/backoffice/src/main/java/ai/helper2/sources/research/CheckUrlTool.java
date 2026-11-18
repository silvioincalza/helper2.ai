package ai.helper2.sources.research;

import ai.helper2.research.ResearchTool;
import ai.helper2.search.ShadowLibraries;
import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.FutureTask;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.LongSupplier;
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

  static final int MAX_ADDRESSES_PER_HOP = 2;

  private static final int MAX_HOPS = 5;
  private static final long TIMEOUT_SECONDS = 8;
  private static final ScheduledExecutorService DEADLINES =
      Executors.newSingleThreadScheduledExecutor(
          Thread.ofPlatform().daemon().name("check-url-deadline").factory());

  interface Transport {
    Hop head(URI url, List<InetAddress> addresses, Duration timeLeft) throws IOException;
  }

  record Hop(int status, @Nullable String location) {}

  private final PublicAddresses addresses;
  private final Transport transport;
  private final Duration deadline;
  private final LongSupplier nanos;

  public CheckUrlTool(ShadowLibraries shadowLibraries, Duration deadline) {
    this(
        new PublicAddresses(shadowLibraries), CheckUrlTool::pinnedHead, deadline, System::nanoTime);
  }

  CheckUrlTool(
      PublicAddresses addresses, Transport transport, Duration deadline, LongSupplier nanos) {
    this.addresses = addresses;
    this.transport = transport;
    this.deadline = deadline;
    this.nanos = nanos;
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
    long endsAt = nanos.getAsLong() + deadline.toNanos();
    for (int hop = 0; hop <= MAX_HOPS; hop++) {
      if (timeLeft(endsAt) == null) {
        return Output.error(out + stopped());
      }
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
        checked = resolveWithin(uri.getHost(), timeLeft(endsAt));
      } catch (PublicAddresses.NotPublic e) {
        return Output.error(out + "not checked: " + current + ": " + e.getMessage());
      } catch (TimeoutException e) {
        return Output.error(out + current + ": " + stopped());
      }
      Duration timeLeft = timeLeft(endsAt);
      if (timeLeft == null) {
        return Output.error(out + current + ": " + stopped());
      }
      Hop response;
      try {
        response =
            transport.head(
                uri, checked.subList(0, Math.min(MAX_ADDRESSES_PER_HOP, checked.size())), timeLeft);
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

  private @Nullable Duration timeLeft(long endsAt) {
    Duration left = Duration.ofNanos(endsAt - nanos.getAsLong());
    return left.isNegative() || left.isZero() ? null : left;
  }

  private String stopped() {
    return "stopped: one check takes at most " + deadline.toMillis() + " ms";
  }

  private List<InetAddress> resolveWithin(String host, @Nullable Duration timeLeft)
      throws PublicAddresses.NotPublic, TimeoutException {
    if (timeLeft == null) {
      throw new TimeoutException();
    }
    FutureTask<List<InetAddress>> lookup = new FutureTask<>(() -> addresses.resolve(host));
    Thread.ofVirtual().name("check-url-lookup").start(lookup);
    try {
      return lookup.get(timeLeft.toNanos(), TimeUnit.NANOSECONDS);
    } catch (ExecutionException e) {
      if (e.getCause() instanceof PublicAddresses.NotPublic notPublic) {
        throw notPublic;
      }
      throw new IllegalStateException(e.getCause());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new TimeoutException();
    } finally {
      lookup.cancel(true);
    }
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

  static Hop pinnedHead(URI url, List<InetAddress> checked, Duration timeLeft) throws IOException {
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
      ScheduledFuture<?> cancel =
          DEADLINES.schedule(head::cancel, timeLeft.toNanos(), TimeUnit.NANOSECONDS);
      try {
        return http.execute(
            head,
            response -> {
              Header location = response.getFirstHeader("Location");
              return new Hop(response.getCode(), location == null ? null : location.getValue());
            });
      } finally {
        cancel.cancel(false);
      }
    }
  }
}
