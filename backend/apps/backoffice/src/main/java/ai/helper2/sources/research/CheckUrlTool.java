package ai.helper2.sources.research;

import ai.helper2.research.ResearchTool;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

public final class CheckUrlTool implements ResearchTool {

  private static final int MAX_HOPS = 5;
  private static final Duration TIMEOUT = Duration.ofSeconds(8);

  private final HttpClient http =
      HttpClient.newBuilder()
          .followRedirects(HttpClient.Redirect.NEVER)
          .connectTimeout(TIMEOUT)
          .build();

  @Override
  public String name() {
    return "check_url";
  }

  @Override
  public String description() {
    return "Check that a URL answers: the HTTP status of each hop and the final URL after redirects. Not evidence.";
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
    String current = url;
    for (int hop = 0; hop <= MAX_HOPS; hop++) {
      String problem = PublicAddresses.problem(current);
      if (problem != null) {
        return Output.error(out + "not checked: " + current + ": " + problem);
      }
      HttpResponse<Void> response;
      try {
        response =
            http.send(
                HttpRequest.newBuilder(URI.create(current))
                    .method("HEAD", HttpRequest.BodyPublishers.noBody())
                    .timeout(TIMEOUT)
                    .header("User-Agent", "helper2.ai research")
                    .build(),
                HttpResponse.BodyHandlers.discarding());
      } catch (IOException e) {
        return Output.error(out + current + ": " + e.getClass().getSimpleName());
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return Output.error("interrupted");
      }
      out.append(response.statusCode()).append(' ').append(current).append('\n');
      String location = response.headers().firstValue("location").orElse(null);
      if (response.statusCode() / 100 != 3 || location == null) {
        return new Output(out + "final: " + current, null);
      }
      current = URI.create(current).resolve(location).toString();
    }
    return Output.error(out + "more than " + MAX_HOPS + " redirects");
  }
}
