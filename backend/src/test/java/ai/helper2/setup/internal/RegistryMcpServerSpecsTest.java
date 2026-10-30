package ai.helper2.setup.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.helper2.platform.http.ObservedHttp;
import ai.helper2.setup.McpServerSpec;
import ai.helper2.setup.RunOption;
import ai.helper2.setup.SetupUnavailable;
import ai.helper2.setup.Transport;
import ai.helper2.testing.FixtureServer;
import io.micrometer.observation.ObservationRegistry;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/** Parsing of real Official MCP Registry entries (trimmed), as fetched 2026-10-27. */
class RegistryMcpServerSpecsTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-27T20:00:00Z"), ZoneOffset.UTC);

    private static McpServerSpec parse(String json) {
        return RegistryMcpServerSpecs.parse(JsonMapper.builder().build().readTree(json).path("server"),
            URI.create("https://registry.example/x"), CLOCK);
    }

    @Test
    void remoteServer() {
        McpServerSpec s = parse("""
            {"server":{"name":"ai.dotsy/mcp","version":"0.6.2",
              "remotes":[{"type":"streamable-http","url":"https://mcp.dotsy.ai/mcp"}]}}""");
        assertThat(s.getVersion()).isEqualTo("0.6.2");
        assertThat(s.getOptions()).singleElement().isInstanceOfSatisfying(RunOption.Remote.class, r -> {
            assertThat(r.getTransport()).isEqualTo(Transport.STREAMABLE_HTTP);
            assertThat(r.getUrl()).isEqualTo(URI.create("https://mcp.dotsy.ai/mcp"));
        });
    }

    @Test
    void packagesKeepArgumentsAndSecretsWithoutValues_nonStdioPackageAndPlainHttpRemoteDropped() {
        McpServerSpec s = parse("""
            {"server":{"name":"io.github.Eszetael/postgres-mcp-hardened","version":"0.1.10",
              "remotes":[{"type":"streamable-http","url":"http://insecure.example/mcp"}],
              "packages":[
                {"registryType":"npm","identifier":"postgres-mcp-hardened","version":"0.1.10","runtimeHint":"npx",
                 "transport":{"type":"stdio"},
                 "runtimeArguments":[{"isRequired":true,"type":"positional","value":"--stdio"}],
                 "environmentVariables":[
                   {"description":"Connection string","isRequired":true,"isSecret":true,"name":"DATABASE_URL","default":"postgres://leak"},
                   {"description":"Timeout","name":"MCP_STATEMENT_TIMEOUT","default":"30s"}]},
                {"registryType":"oci","identifier":"ghcr.io/eszetael/postgres-mcp-hardened:0.1.10",
                 "transport":{"type":"streamable-http","url":"http://localhost:8080/mcp"}}]}}""");
        assertThat(s.getOptions()).singleElement().isInstanceOfSatisfying(RunOption.Package.class, p -> {
            assertThat(p.getIdentifier()).isEqualTo("postgres-mcp-hardened");
            assertThat(p.getRuntimeHint()).isEqualTo("npx");
            assertThat(p.getRuntimeArgs()).singleElement().satisfies(a -> assertThat(a.getValue()).isEqualTo("--stdio"));
            assertThat(p.getSettings()).hasSize(2);
            assertThat(p.getSettings().get(0).getSecret()).isTrue();
            assertThat(p.getSettings().get(0).getValue()).isNull();
            assertThat(p.getSettings().get(1).getValue()).isEqualTo("30s");
        });
    }

    @Test
    void malformedRemoteUrlIsSkippedNotFatal() {
        McpServerSpec s = parse("""
            {"server":{"name":"x/y","remotes":[
              {"type":"streamable-http","url":"https://bad host/mcp"},
              {"type":"sse","url":"https://ok.example/sse"}]}}""");
        assertThat(s.getOptions()).singleElement().isInstanceOfSatisfying(RunOption.Remote.class,
            r -> assertThat(r.getUrl()).isEqualTo(URI.create("https://ok.example/sse")));
    }

    @Test
    void answersAreKeptForAnHourAndAMissingEntryToo_failuresAreNot() {
        try (FixtureServer server = new FixtureServer()) {
            server.route("/v0.1/servers/ai.dotsy%2Fmcp/versions/latest", FixtureServer.Reply.ok("""
                {"server":{"name":"ai.dotsy/mcp","remotes":[{"type":"streamable-http","url":"https://mcp.dotsy.ai/mcp"}]}}"""));
            server.route("/v0.1/servers/gone%2Fx/versions/latest", new FixtureServer.Reply(404, "{}", Map.of(), 0));
            server.route("/v0.1/servers/down%2Fx/versions/latest", new FixtureServer.Reply(503, "{}", Map.of(), 0));
            MutableClock clock = new MutableClock(Instant.parse("2026-10-27T20:00:00Z"));
            RegistryMcpServerSpecs specs = new RegistryMcpServerSpecs(
                ObservedHttp.restClientBuilder(Duration.ofSeconds(2), ObservationRegistry.NOOP), server.baseUrl(),
                JsonMapper.builder().build(), clock, Duration.ofHours(1));

            assertThat(specs.latest("ai.dotsy/mcp")).isNotNull();
            assertThat(specs.latest("ai.dotsy/mcp")).isNotNull();
            assertThat(specs.latest("gone/x")).isNull();
            assertThat(specs.latest("gone/x")).isNull();
            assertThatThrownBy(() -> specs.latest("down/x")).isInstanceOf(SetupUnavailable.class);
            assertThatThrownBy(() -> specs.latest("down/x")).isInstanceOf(SetupUnavailable.class);
            assertThat(server.requests).filteredOn(r -> r.contains("dotsy")).hasSize(1);
            assertThat(server.requests).filteredOn(r -> r.contains("gone")).hasSize(1);
            // Not kept: each lookup asks again (the shared client may also retry a 503 once).
            int down = (int) server.requests.stream().filter(r -> r.contains("down")).count();
            specs.latest("ai.dotsy/mcp");
            assertThatThrownBy(() -> specs.latest("down/x")).isInstanceOf(SetupUnavailable.class);
            assertThat(server.requests).filteredOn(r -> r.contains("down")).hasSizeGreaterThan(down);

            clock.now = clock.now.plus(Duration.ofHours(1));
            specs.latest("ai.dotsy/mcp");
            assertThat(server.requests).filteredOn(r -> r.contains("dotsy")).hasSize(2);
        }
    }

    static final class MutableClock extends Clock {
        Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
