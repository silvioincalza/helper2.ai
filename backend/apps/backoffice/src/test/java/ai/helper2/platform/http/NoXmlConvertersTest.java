package ai.helper2.platform.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.helper2.testing.FixtureServer;
import ai.helper2.testing.TestHttp;
import io.micrometer.observation.ObservationRegistry;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.HttpMessageConverters;
import org.springframework.http.converter.StringHttpMessageConverter;
import org.springframework.http.converter.xml.JacksonXmlHttpMessageConverter;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.UnknownContentTypeException;

class NoXmlConvertersTest {

  final FixtureServer server = new FixtureServer();

  @AfterEach
  void stop() {
    server.close();
  }

  @Test
  void theServerNeitherReadsNorWritesXmlEvenWhenTheLibraryIsThere() {
    HttpMessageConverters.ServerBuilder builder =
        HttpMessageConverters.forServer().registerDefaults();
    builder.withXmlConverter(new JacksonXmlHttpMessageConverter());
    new NoXmlConvertersConfiguration().noXmlOnTheServer().customize(builder);

    List<HttpMessageConverter<?>> converters = list(builder.build());

    assertThat(converters).noneMatch(XmlConverters::handlesXml);
    assertThat(converters)
        .anyMatch(c -> c.getSupportedMediaTypes().contains(MediaType.APPLICATION_JSON));
    assertThat(converters).anyMatch(StringHttpMessageConverter.class::isInstance);
  }

  @Test
  void bootsClientsNeitherReadNorWriteXml() {
    HttpMessageConverters.ClientBuilder builder =
        HttpMessageConverters.forClient().registerDefaults();
    new NoXmlConvertersConfiguration().noXmlOnClients().customize(builder);

    assertThat(list(builder.build())).noneMatch(XmlConverters::handlesXml);
  }

  @Test
  void anObservedClientReadsJsonButNotXml() {
    server.route("/json", FixtureServer.Reply.ok("{\"name\":\"x\"}"));
    server.route(
        "/xml",
        new FixtureServer.Reply(
            200, "<thing><name>x</name></thing>", Map.of("Content-Type", "application/xml"), 0));
    RestClient http =
        TestHttp.restClientBuilder(Duration.ofSeconds(2), ObservationRegistry.NOOP).build();
    var type = new ParameterizedTypeReference<Map<String, Object>>() {};

    assertThat(http.get().uri(server.baseUrl() + "/json").retrieve().body(type))
        .containsEntry("name", "x");
    assertThatThrownBy(() -> http.get().uri(server.baseUrl() + "/xml").retrieve().body(type))
        .isInstanceOf(UnknownContentTypeException.class);
  }

  private static List<HttpMessageConverter<?>> list(HttpMessageConverters converters) {
    return StreamSupport.stream(converters.spliterator(), false).toList();
  }
}
