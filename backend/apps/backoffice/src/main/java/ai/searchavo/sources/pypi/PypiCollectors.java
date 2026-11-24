package ai.searchavo.sources.pypi;

import ai.searchavo.platform.resilience.Resilience;
import ai.searchavo.scout.admission.AdmissionPolicy;
import ai.searchavo.sources.config.CollectorProperties;
import ai.searchavo.sources.support.RunInterval;
import ai.searchavo.sources.support.SourceSettings;
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
          "The PyPI source is on (SA_SOURCE_PYPI_ENABLED) but SA_PYPI_BIGQUERY_PROJECT or"
              + " SA_PYPI_BIGQUERY_KEY is empty");
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
