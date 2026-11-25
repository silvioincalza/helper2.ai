package ai.searchavo.catalog.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.searchavo.testing.FixtureServer;
import java.net.URI;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.databind.json.JsonMapper;

class IndexNowNoticeTest {

  @Test
  void postsTheHostTheKeyItsFileAndTheAddresses() throws Exception {
    try (FixtureServer server = new FixtureServer()) {
      server.route("/indexnow", new FixtureServer.Reply(202, "", Map.of(), 0));
      new IndexNowNotice(
              RestClient.create(),
              URI.create(server.baseUrl() + "/indexnow"),
              "https://searchavo.ai",
              "0123456789abcdef0123456789abcdef")
          .send(List.of("https://searchavo.ai/resource/x/y"));

      var body = JsonMapper.builder().build().readTree(server.requestBodies.getFirst());
      assertThat(body.path("host").asString()).isEqualTo("searchavo.ai");
      assertThat(body.path("key").asString()).isEqualTo("0123456789abcdef0123456789abcdef");
      assertThat(body.path("keyLocation").asString())
          .isEqualTo("https://searchavo.ai/0123456789abcdef0123456789abcdef.txt");
      assertThat(body.path("urlList").get(0).asString())
          .isEqualTo("https://searchavo.ai/resource/x/y");
    }
  }

  @Test
  void aRefusedBatchIsAnError() {
    try (FixtureServer server = new FixtureServer()) {
      server.route("/indexnow", new FixtureServer.Reply(403, "", Map.of(), 0));
      var notice =
          new IndexNowNotice(
              RestClient.create(),
              URI.create(server.baseUrl() + "/indexnow"),
              "https://searchavo.ai",
              "0123456789abcdef0123456789abcdef");
      assertThatThrownBy(() -> notice.send(List.of("https://searchavo.ai/")))
          .isInstanceOf(RestClientException.class);
    }
  }
}
