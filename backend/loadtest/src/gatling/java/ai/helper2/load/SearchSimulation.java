package ai.helper2.load;

import static io.gatling.javaapi.core.CoreDsl.details;
import static io.gatling.javaapi.core.CoreDsl.jmesPath;
import static io.gatling.javaapi.core.CoreDsl.jsonFile;
import static io.gatling.javaapi.core.CoreDsl.scenario;
import static io.gatling.javaapi.http.HttpDsl.http;
import static io.gatling.javaapi.http.HttpDsl.status;

import io.gatling.javaapi.core.ScenarioBuilder;
import io.gatling.javaapi.core.Simulation;
import java.util.ArrayList;

public class SearchSimulation extends Simulation {

  static final String REQUEST = "search";

  private final LoadProfile profile = LoadProfile.fromSystemProperties();

  private final ScenarioBuilder search =
      scenario("search")
          .feed(jsonFile("search-queries.json").circular())
          .exec(
              http(REQUEST)
                  .get("/api/v1/search")
                  .queryParam("q", "#{text}")
                  .check(status().is(200), jmesPath("coverage").exists()));

  {
    var assertions = new ArrayList<>(LoadProfile.fewFailures());
    assertions.add(details(REQUEST).responseTime().percentile(50.0).lte(1_000));
    assertions.add(details(REQUEST).responseTime().percentile(95.0).lte(3_000));
    assertions.add(details(REQUEST).responseTime().percentile(99.0).lte(4_000));
    setUp(search.injectOpen(profile.arrivals()))
        .protocols(profile.protocol())
        .assertions(assertions);
  }
}
