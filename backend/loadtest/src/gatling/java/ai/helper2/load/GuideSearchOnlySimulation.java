package ai.helper2.load;

import static io.gatling.javaapi.core.CoreDsl.StringBody;
import static io.gatling.javaapi.core.CoreDsl.bodyString;
import static io.gatling.javaapi.core.CoreDsl.details;
import static io.gatling.javaapi.core.CoreDsl.jsonFile;
import static io.gatling.javaapi.core.CoreDsl.scenario;
import static io.gatling.javaapi.core.CoreDsl.substring;
import static io.gatling.javaapi.http.HttpDsl.CookieKey;
import static io.gatling.javaapi.http.HttpDsl.getCookieValue;
import static io.gatling.javaapi.http.HttpDsl.http;
import static io.gatling.javaapi.http.HttpDsl.status;

import io.gatling.javaapi.core.ScenarioBuilder;
import io.gatling.javaapi.core.Simulation;
import java.util.ArrayList;

public class GuideSearchOnlySimulation extends Simulation {

  static final String REQUEST = "guide search-only";

  private final LoadProfile profile = LoadProfile.fromSystemProperties();

  private final ScenarioBuilder guide =
      scenario("guide search-only")
          .feed(jsonFile("guide-queries.json").circular())
          .exec(http("csrf cookie").get("/api/v1/me").check(status().in(200, 204, 401)))
          .exec(getCookieValue(CookieKey("XSRF-TOKEN").saveAs("csrf")))
          .exec(
              http(REQUEST)
                  .post("/api/v1/guide/stream")
                  .header("Accept", "text/event-stream")
                  .header("Content-Type", "application/json")
                  .header("X-XSRF-TOKEN", "#{csrf}")
                  .body(StringBody("{\"request\": #{request.jsonStringify()}}"))
                  .check(
                      status().is(200),
                      substring("event:answer").exists(),
                      bodyString().transform(body -> body.contains("event:chosen")).is(false)));

  {
    var assertions = new ArrayList<>(LoadProfile.fewFailures());
    assertions.add(details(REQUEST).responseTime().percentile(50.0).lte(3_000));
    assertions.add(details(REQUEST).responseTime().percentile(95.0).lte(8_000));
    setUp(guide.injectOpen(profile.arrivals()))
        .protocols(profile.protocol())
        .assertions(assertions);
  }
}
