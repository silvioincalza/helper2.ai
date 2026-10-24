package ai.helper2.platform.http;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import org.apache.hc.client5.http.protocol.HttpClientContext;
import org.apache.hc.core5.http.message.BasicHttpRequest;
import org.apache.hc.core5.http.message.BasicHttpResponse;
import org.junit.jupiter.api.Test;

class ObservedHttpRedirectTest {

    private static boolean follows(String from, String location) throws Exception {
        BasicHttpResponse response = new BasicHttpResponse(302);
        response.addHeader("Location", location);
        return ObservedHttp.NoDowngradeRedirects.INSTANCE.isRedirected(
            new BasicHttpRequest("GET", URI.create(from)), response, HttpClientContext.create());
    }

    @Test
    void refusesHttpsToHttp() throws Exception {
        assertThat(follows("https://api.example.com/a", "http://cdn.example.com/b")).isFalse();
        assertThat(follows("https://api.example.com/a", "HTTP://cdn.example.com/b")).isFalse();
    }

    @Test
    void followsSafeRedirects() throws Exception {
        assertThat(follows("https://api.example.com/a", "https://cdn.example.com/b")).isTrue();
        assertThat(follows("https://api.example.com/a", "/b")).isTrue();
        assertThat(follows("http://api.example.com/a", "http://cdn.example.com/b")).isTrue();
        assertThat(follows("http://api.example.com/a", "https://cdn.example.com/b")).isTrue();
    }
}
