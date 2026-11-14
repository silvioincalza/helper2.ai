package ai.helper2.sources.pypi;

import ai.helper2.platform.resilience.Resilience;
import ai.helper2.scout.admission.AdmissionPolicy;
import ai.helper2.sources.config.CollectorProperties;
import ai.helper2.sources.support.RunInterval;
import ai.helper2.sources.support.SourceSettings;
import java.time.Clock;
import org.springframework.web.client.RestClient;

public final class PypiCollectors {

  private PypiCollectors() {}

  public static PypiCollector create(
      SourceSettings settings,
      CollectorProperties.Pypi properties,
      RestClient http,
      Clock clock,
      Resilience resilience,
      AdmissionPolicy admission) {
    PypiCollector.Queries queries =
        settings.enabled() ? bigQuery(settings, properties, http, clock) : PypiCollectors::off;
    return new PypiCollector(
        settings,
        queries,
        clock,
        resilience,
        new RunInterval(properties.interval()),
        properties.candidatesPerDay(),
        admission);
  }

  private static PypiCollector.Queries bigQuery(
      SourceSettings settings, CollectorProperties.Pypi properties, RestClient http, Clock clock) {
    String key = settings.token();
    String project = properties.project();
    if (key == null || key.isBlank() || project == null || project.isBlank()) {
      throw new IllegalStateException(
          "The PyPI source is on (H2_SOURCE_PYPI_ENABLED) but H2_PYPI_BIGQUERY_PROJECT or"
              + " H2_PYPI_BIGQUERY_KEY is empty");
    }
    GoogleServiceAccount account = GoogleServiceAccount.fromBase64Json(key);
    BigQueryClient client =
        new BigQueryClient(
            http,
            new GoogleAccessTokens(account, http, clock),
            project.strip(),
            clock,
            BigQueryClient.API,
            properties.maxBytesBilled());
    return client::query;
  }

  private static java.util.List<java.util.Map<String, Object>> off(
      String sql, java.util.List<BigQueryClient.Parameter> parameters, int maxRows) {
    throw new IllegalStateException("The PyPI source is off");
  }
}
