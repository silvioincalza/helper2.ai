package ai.helper2.load;

import static io.gatling.javaapi.core.CoreDsl.constantUsersPerSec;
import static io.gatling.javaapi.core.CoreDsl.global;
import static io.gatling.javaapi.core.CoreDsl.rampUsersPerSec;
import static io.gatling.javaapi.http.HttpDsl.http;

import io.gatling.javaapi.core.Assertion;
import io.gatling.javaapi.core.OpenInjectionStep;
import io.gatling.javaapi.http.HttpProtocolBuilder;
import java.time.Duration;
import java.util.List;

final class LoadProfile {

  static final double DEFAULT_PEAK_RPS = 5;

  final String baseUrl;
  final double peakRps;
  final Duration ramp;
  final Duration hold;

  private LoadProfile(String baseUrl, double peakRps, Duration ramp, Duration hold) {
    this.baseUrl = baseUrl;
    this.peakRps = peakRps;
    this.ramp = ramp;
    this.hold = hold;
  }

  static LoadProfile fromSystemProperties() {
    String baseUrl = System.getProperty("h2.load.baseUrl", "").strip();
    if (!baseUrl.startsWith("https://") && !baseUrl.startsWith("http://")) {
      throw new IllegalStateException(
          "Set -Dh2.load.baseUrl=https://<host> (no default: the run must name its target)");
    }
    double peak = positive("h2.load.peakRps", DEFAULT_PEAK_RPS);
    LoadProfile profile =
        new LoadProfile(
            baseUrl.replaceAll("/+$", ""),
            peak,
            minutes(positive("h2.load.rampMinutes", 5)),
            minutes(positive("h2.load.holdMinutes", 10)));
    System.out.printf(
        "load test: %s, 0 -> %.2f requests/s over %s, then %s at the peak%n",
        profile.baseUrl, profile.peakRps, profile.ramp, profile.hold);
    return profile;
  }

  OpenInjectionStep[] arrivals() {
    return new OpenInjectionStep[] {
      rampUsersPerSec(0).to(peakRps).during(ramp), constantUsersPerSec(peakRps).during(hold)
    };
  }

  HttpProtocolBuilder protocol() {
    return http.baseUrl(baseUrl)
        .acceptHeader("application/json")
        .userAgentHeader("helper2-loadtest (Gatling)");
  }

  static List<Assertion> fewFailures() {
    return List.of(global().failedRequests().percent().lt(1.0));
  }

  private static Duration minutes(double minutes) {
    return Duration.ofSeconds(Math.max(1, Math.round(minutes * 60)));
  }

  private static double positive(String name, double fallback) {
    String value = System.getProperty(name);
    if (value == null || value.isBlank()) {
      return fallback;
    }
    double parsed = Double.parseDouble(value.strip());
    if (!(parsed > 0)) {
      throw new IllegalStateException(name + " must be above 0: " + value);
    }
    return parsed;
  }
}
