package ai.helper2.platform.http;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import io.micrometer.common.KeyValue;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.core.instrument.observation.DefaultMeterObservationHandler;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import io.micrometer.observation.ObservationRegistry;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

class ObservedHttpObservationTest {

    private HttpServer server;
    private MeterRegistry meters;
    private ObservationRegistry observations;

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, body.length);
            ex.getResponseBody().write(body);
            ex.close();
        });
        server.start();
        meters = new SimpleMeterRegistry();
        observations = ObservationRegistry.create();
        observations.observationConfig().observationHandler(new DefaultMeterObservationHandler(meters));
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private RestClient client() {
        return ObservedHttp.restClientBuilder(Duration.ofSeconds(2), observations)
            .baseUrl("http://127.0.0.1:" + server.getAddress().getPort()).build();
    }

    @Test
    void recordsClientRequestTimerWithHostAndBoundedUri() {
        client().get().uri(u -> u.path("/api/models/12345").queryParam("search", "secret query").build())
            .retrieve().body(String.class);

        var timer = meters.get("http.client.requests").timer();
        assertThat(timer.count()).isEqualTo(1);
        assertThat(timer.getId().getTag("client.name")).isEqualTo("127.0.0.1");
        assertThat(timer.getId().getTag("uri")).isEqualTo("/api/models/{id}");
        assertThat(timer.getId().getTag("status")).isEqualTo("200");
    }

    @Test
    void spanUrlLeavesOutTheQuery() {
        List<String> urls = new ArrayList<>();
        observations.observationConfig().observationHandler(new ObservationHandler<>() {
            @Override
            public boolean supportsContext(Observation.Context context) {
                return true;
            }

            @Override
            public void onStop(Observation.Context context) {
                KeyValue url = context.getHighCardinalityKeyValue("http.url");
                if (url != null) {
                    urls.add(url.getValue());
                }
            }
        });

        client().get().uri(u -> u.path("/api/models").queryParam("search", "secret query").build())
            .retrieve().body(String.class);

        assertThat(urls).containsExactly("http://127.0.0.1:" + server.getAddress().getPort() + "/api/models");
    }

    @Test
    void keepsUriTemplateWhenGiven() {
        client().get().uri("/repos/{owner}/{repo}/readme", "acme", "tool").retrieve().body(String.class);

        assertThat(meters.get("http.client.requests").timer().getId().getTag("uri")).isEqualTo("/repos/{owner}/{repo}/readme");
    }
}
