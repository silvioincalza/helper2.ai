package ai.helper2.setup.internal;

import ai.helper2.setup.Argument;
import ai.helper2.setup.McpServerSpec;
import ai.helper2.setup.McpServerSpecs;
import ai.helper2.setup.RunOption;
import ai.helper2.setup.Setting;
import ai.helper2.setup.SettingKind;
import ai.helper2.setup.SetupUnavailable;
import ai.helper2.setup.Transport;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Reads the latest entry of an MCP server from the Official MCP Registry, by name, at request time.
 * Docs: https://registry.modelcontextprotocol.io/docs. Only what the entry states is kept: remotes, packages with
 * their transport, arguments, environment variables and headers. Anything the entry does not state stays absent.
 *
 * <p>Answers (a missing entry included) are kept for {@link #TTL}, at most {@link #MAX_ENTRIES} names, so page views
 * do not become registry calls: the registry asks aggregators to read it infrequently, and prod's egress address is
 * the one the Scout collects from. Failures are not kept.
 */
class RegistryMcpServerSpecs implements McpServerSpecs {

    static final Duration TTL = Duration.ofHours(1);
    static final int MAX_ENTRIES = 500;

    private record Cached(@Nullable McpServerSpec spec, Instant expires) {
    }

    private final RestClient http;
    private final String baseUrl;
    private final ObjectMapper json;
    private final Clock clock;
    private final Map<String, Cached> cache = new LinkedHashMap<>(64, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Cached> eldest) {
            return size() > MAX_ENTRIES;
        }
    };

    RegistryMcpServerSpecs(RestClient.Builder builder, String baseUrl, ObjectMapper json, Clock clock) {
        this.http = builder.baseUrl(baseUrl).build();
        this.baseUrl = baseUrl;
        this.json = json;
        this.clock = clock;
    }

    @Override
    public @Nullable McpServerSpec latest(String name) {
        Instant now = clock.instant();
        synchronized (cache) {
            Cached c = cache.get(name);
            if (c != null && now.isBefore(c.expires())) {
                return c.spec();
            }
        }
        McpServerSpec spec = fetch(name);
        synchronized (cache) {
            cache.put(name, new Cached(spec, now.plus(TTL)));
        }
        return spec;
    }

    private @Nullable McpServerSpec fetch(String name) {
        String path = "/v0.1/servers/" + URLEncoder.encode(name, StandardCharsets.UTF_8) + "/versions/latest";
        String body;
        try {
            body = http.get().uri(URI.create(baseUrl + path)).retrieve().body(String.class);
        } catch (RestClientResponseException e) {
            if (e.getStatusCode().isSameCodeAs(HttpStatus.NOT_FOUND)) {
                return null;
            }
            throw new SetupUnavailable("MCP Registry answered " + e.getStatusCode().value());
        } catch (RestClientException e) {
            throw new SetupUnavailable("MCP Registry did not answer");
        }
        try {
            JsonNode server = json.readTree(body == null ? "" : body).path("server");
            if (!server.isObject()) {
                throw new SetupUnavailable("MCP Registry sent an unexpected answer");
            }
            return parse(server, URI.create(baseUrl + path), clock);
        } catch (JacksonException e) {
            throw new SetupUnavailable("MCP Registry sent a malformed answer");
        }
    }

    static McpServerSpec parse(JsonNode server, URI sourceUrl, Clock clock) {
        List<RunOption> options = new ArrayList<>();
        for (JsonNode r : server.path("remotes")) {
            Transport t = transport(text(r, "type"));
            String url = text(r, "url");
            if (t == null || t == Transport.STDIO || url == null || !url.startsWith("https://")) {
                continue;
            }
            URI uri;
            try {
                uri = URI.create(url);
            } catch (IllegalArgumentException e) {
                // One malformed entry must not take the whole answer down.
                continue;
            }
            options.add(new RunOption.Remote(t, uri, settings(r.path("headers"), SettingKind.HEADER)));
        }
        for (JsonNode p : server.path("packages")) {
            String type = text(p, "registryType");
            String id = text(p, "identifier");
            Transport t = transport(text(p.path("transport"), "type"));
            if (type == null || id == null || t != Transport.STDIO) {
                continue;
            }
            options.add(new RunOption.Package(type, id, text(p, "version"), t, text(p, "runtimeHint"),
                arguments(p.path("runtimeArguments")), arguments(p.path("packageArguments")),
                settings(p.path("environmentVariables"), SettingKind.ENV)));
        }
        return new McpServerSpec(text(server, "name"), text(server, "version"), options, sourceUrl, clock.instant());
    }

    private static @Nullable Transport transport(@Nullable String type) {
        if (type == null) {
            return null;
        }
        return switch (type) {
            case "streamable-http" -> Transport.STREAMABLE_HTTP;
            case "sse" -> Transport.SSE;
            case "stdio" -> Transport.STDIO;
            default -> null;
        };
    }

    private static List<Setting> settings(JsonNode items, SettingKind kind) {
        List<Setting> out = new ArrayList<>();
        for (JsonNode s : items) {
            String name = text(s, "name");
            if (name == null) {
                continue;
            }
            boolean secret = s.path("isSecret").asBoolean(false);
            String value = text(s, "value");
            if (value == null && !secret) {
                value = text(s, "default");
            }
            out.add(new Setting(name, kind, text(s, "description"), s.path("isRequired").asBoolean(false), secret, value));
        }
        return out;
    }

    private static List<Argument> arguments(JsonNode items) {
        List<Argument> out = new ArrayList<>();
        for (JsonNode a : items) {
            boolean named = "named".equals(text(a, "type"));
            String name = named ? text(a, "name") : null;
            if (named && name == null) {
                continue;
            }
            boolean secret = a.path("isSecret").asBoolean(false);
            String value = text(a, "value");
            if (value == null && !secret) {
                value = text(a, "default");
            }
            out.add(new Argument(name, value, text(a, "valueHint"), text(a, "description"),
                a.path("isRequired").asBoolean(false), secret));
        }
        return out;
    }

    private static @Nullable String text(JsonNode node, String field) {
        JsonNode v = node.path(field);
        if (!v.isString()) {
            return null;
        }
        String s = v.asString().strip();
        return s.isEmpty() ? null : s;
    }
}
