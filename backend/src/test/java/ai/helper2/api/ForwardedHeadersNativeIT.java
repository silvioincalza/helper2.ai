package ai.helper2.api;

import static org.assertj.core.api.Assertions.assertThat;

import ai.helper2.TestcontainersConfiguration;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Production setting behind Cloudflare (H2_FORWARD_HEADERS_STRATEGY=native): the proxy appends the real
 * client address as the rightmost X-Forwarded-For entry, and only that entry counts. Whatever the
 * client put in front of it cannot open a fresh rate-limit bucket.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "helper2.sources.github.enabled=false", "helper2.sources.huggingface.enabled=false",
    "helper2.sources.npm.enabled=false", "helper2.sources.mcp-registry.enabled=false", "helper2.sources.arxiv.enabled=false", "helper2.sources.tavily.enabled=false",
    "server.forward-headers-strategy=native", "helper2.rate-limit.recent-per-minute=3", "logging.structured.format.console=",
})
@Import(TestcontainersConfiguration.class)
class ForwardedHeadersNativeIT {

    @LocalServerPort
    int port;

    @Autowired
    StringRedisTemplate redis;

    /** Rate-limit counts are in the Redis that every test context shares: each test starts from zero. */
    @BeforeEach
    void resetRateLimits() {
        redis.delete(redis.keys("h2:v1:rate:*"));
    }

    final HttpClient http = HttpClient.newHttpClient();

    int recent(String xForwardedFor) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/recent"))
            .header("X-Forwarded-For", xForwardedFor).build(), HttpResponse.BodyHandlers.ofString()).statusCode();
    }

    @Test
    void onlyTheProxyAppendedAddressCountsNotTheClientSuppliedPrefix() throws Exception {
        int ok = 0;
        for (int i = 0; i < 6; i++) {
            ok += recent("198.51.100." + i + ", 10.0.0." + i + ", 203.0.113.9") == 200 ? 1 : 0;
        }
        assertThat(ok).isEqualTo(3);
        assertThat(recent("203.0.113.10")).as("a different real client has its own budget").isEqualTo(200);
    }
}
