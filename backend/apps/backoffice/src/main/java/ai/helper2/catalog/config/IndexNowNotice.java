package ai.helper2.catalog.config;

import ai.helper2.catalog.usecase.SendChangedPages;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

class IndexNowNotice implements SendChangedPages.Notice {

  private final RestClient http;
  private final URI endpoint;
  private final String host;
  private final String key;
  private final String keyLocation;

  IndexNowNotice(RestClient http, URI endpoint, String publicBaseUrl, String key) {
    this.http = http;
    this.endpoint = endpoint;
    this.host = URI.create(publicBaseUrl).getHost();
    this.key = key;
    this.keyLocation = publicBaseUrl + "/" + key + ".txt";
  }

  @Override
  public void send(List<String> urls) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("host", host);
    body.put("key", key);
    body.put("keyLocation", keyLocation);
    body.put("urlList", urls);
    http.post()
        .uri(endpoint)
        .contentType(MediaType.APPLICATION_JSON)
        .body(body)
        .retrieve()
        .toBodilessEntity();
  }
}
