package ai.helper2.sources;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import ai.helper2.platform.http.ObservedHttp;
import ai.helper2.search.SearchSource;
import ai.helper2.search.SourceFailure;
import ai.helper2.search.SourceQuery;
import ai.helper2.sources.github.GitHubSource;
import ai.helper2.sources.huggingface.HuggingFaceModelsSource;
import ai.helper2.sources.support.SourceSettings;
import ai.helper2.sources.tavily.TavilySource;
import ai.helper2.testing.FixtureServer;
import io.micrometer.observation.ObservationRegistry;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.web.client.RestClient;

/**
 * A source token goes only to the configured host. HttpClient refuses a redirect to another authority (here
 * another port) while the request carries Authorization, so a GET search fails as Unavailable; a redirected
 * POST becomes a GET without the source's headers. Either way the other host never sees the token.
 */
class TokenRedirectTest {

    static final String TOKEN = "secret-token";

    final FixtureServer configured = new FixtureServer();
    final FixtureServer elsewhere = new FixtureServer();

    @AfterEach
    void stop() {
        configured.close();
        elsewhere.close();
    }

    record TokenSource(String name, boolean get, String path, BiFunction<SourceSettings, RestClient.Builder, SearchSource> create) {
        @Override
        public String toString() {
            return name;
        }
    }

    static Stream<TokenSource> tokenSources() {
        return Stream.of(
            new TokenSource("github", true, "/search/repositories", (s, b) -> new GitHubSource(s, b, SourceFixtures.CLOCK)),
            new TokenSource("hf-models", true, "/api/models", (s, b) -> new HuggingFaceModelsSource(s, b, SourceFixtures.CLOCK)),
            new TokenSource("tavily", false, "/search", (s, b) -> new TavilySource(s, b, SourceFixtures.CLOCK)));
    }

    private SearchSource source(TokenSource t) {
        return t.create().apply(new SourceSettings(true, configured.baseUrl(), TOKEN, Duration.ofSeconds(2), Duration.ZERO),
            ObservedHttp.restClientBuilder(Duration.ofSeconds(2), ObservationRegistry.NOOP));
    }

    @ParameterizedTest
    @MethodSource("tokenSources")
    void aRedirectToAnotherHostNeverReceivesTheToken(TokenSource t) {
        configured.route(t.path(), new FixtureServer.Reply(302, "", Map.of("Location", elsewhere.baseUrl() + t.path()), 0));
        elsewhere.route("/", FixtureServer.Reply.ok("{\"items\":[],\"results\":[]}"));

        Throwable failure = catchThrowable(() -> source(t).search(new SourceQuery("mcp", List.of(), null, 10)));

        if (t.get()) {
            assertThat(failure).isInstanceOf(SourceFailure.Unavailable.class);
            assertThat(elsewhere.requests).isEmpty();
        }
        assertThat(configured.requestHeaders.getFirst().get("Authorization")).containsExactly("Bearer " + TOKEN);
        assertThat(elsewhere.requestHeaders).allSatisfy(h -> assertThat(h).doesNotContainKey("Authorization"));
    }

    static Stream<TokenSource> getSources() {
        return tokenSources().filter(TokenSource::get);
    }

    @ParameterizedTest
    @MethodSource("getSources")
    void aRedirectOnTheConfiguredHostKeepsTheToken(TokenSource t) {
        configured.route("/moved", FixtureServer.Reply.ok("{\"items\":[],\"results\":[]}"));
        configured.route(t.path(), new FixtureServer.Reply(302, "", Map.of("Location", "/moved"), 0));

        assertThat(source(t).search(new SourceQuery("mcp", List.of(), null, 10))).isEmpty();

        assertThat(configured.requestHeaders).hasSize(2)
            .allSatisfy(h -> assertThat(h.get("Authorization")).containsExactly("Bearer " + TOKEN));
    }
}
