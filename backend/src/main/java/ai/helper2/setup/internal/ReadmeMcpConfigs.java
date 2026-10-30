package ai.helper2.setup.internal;

import ai.helper2.setup.Markdown;
import ai.helper2.setup.Material;
import ai.helper2.setup.McpServerSpec;
import ai.helper2.setup.ReadmeConfigs;
import ai.helper2.setup.RunOption;
import ai.helper2.setup.RunOptionRules;
import ai.helper2.setup.Setting;
import ai.helper2.setup.SettingKind;
import ai.helper2.setup.ToolMaterials;
import ai.helper2.setup.ToolRef;
import ai.helper2.setup.Transport;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Run options from the MCP config example in a server's own README: a JSON block with `mcpServers`, `servers`,
 * `context_servers` or `mcp`, whose entries have `command` / `args` / `env` or a `url`. No model is involved: the
 * values are the README's own. Only the entry of this resource is kept ({@link RunOptionRules#matches}), never
 * another server shown in the same example, and only an option {@link RunOptionRules} accepts: a package runner
 * that fetches one named package, never an interpreter, a shell, a path or flags that evaluate code or reach the
 * host. A variable whose name says it holds a secret (key, token, password) becomes a placeholder whatever the
 * README's example value is.
 */
class ReadmeMcpConfigs implements ReadmeConfigs {

    static final int MAX_OPTIONS = 3;
    private static final Pattern SECRET = Pattern.compile("(?i)(key|token|secret|passw|pat\\b|credential|auth)");
    private static final Pattern LINE_COMMENT = Pattern.compile("(?m)^\\s*//.*$");
    private static final List<String> ROOTS = List.of("mcpServers", "servers", "context_servers", "mcp");

    private final ToolMaterials materials;
    private final ObjectMapper json;

    ReadmeMcpConfigs(ToolMaterials materials, ObjectMapper json) {
        this.materials = materials;
        this.json = json;
    }

    @Override
    public @Nullable McpServerSpec of(ToolRef ref) {
        for (Material m : materials.load(ref)) {
            List<RunOption> options = options(ref, m.getText(), json);
            if (!options.isEmpty()) {
                return new McpServerSpec(ref.getTitle(), null, options, m.getUrl(), m.getRetrievedAt(), "readme");
            }
        }
        return null;
    }

    static List<RunOption> options(ToolRef ref, String readme, ObjectMapper json) {
        List<RunOption> out = new ArrayList<>();
        for (Markdown.Block b : Markdown.INSTANCE.blocks(readme)) {
            String lang = b.getLanguage() == null ? "" : b.getLanguage().toLowerCase();
            if (!(lang.isEmpty() || lang.startsWith("json"))) {
                continue;
            }
            String text = String.join("\n", b.getLines());
            if (ROOTS.stream().noneMatch(r -> text.contains("\"" + r + "\""))) {
                continue;
            }
            JsonNode root;
            try {
                root = json.readTree(LINE_COMMENT.matcher(text).replaceAll(""));
            } catch (JacksonException e) {
                continue;
            }
            for (String r : ROOTS) {
                JsonNode servers = root.path(r);
                if (!servers.isObject()) {
                    continue;
                }
                for (Map.Entry<String, JsonNode> e : servers.properties()) {
                    RunOption o = option(e.getValue());
                    if (o != null && RunOptionRules.INSTANCE.accepts(o) && RunOptionRules.INSTANCE.matches(ref, e.getKey(), o)
                        && !out.contains(o)) {
                        out.add(o);
                    }
                    if (out.size() >= MAX_OPTIONS) {
                        return out;
                    }
                }
            }
        }
        return out;
    }

    private static @Nullable RunOption option(JsonNode s) {
        if (!s.isObject()) {
            return null;
        }
        String url = first(s, "url", "serverUrl", "httpUrl", "uri");
        if (url != null) {
            if (!url.startsWith("https://")) {
                return null;
            }
            String type = text(s, "type");
            boolean sse = "sse".equalsIgnoreCase(type) || (type == null && url.endsWith("/sse"));
            try {
                return new RunOption.Remote(sse ? Transport.SSE : Transport.STREAMABLE_HTTP, URI.create(url),
                    settings(s.path("headers"), SettingKind.HEADER));
            } catch (IllegalArgumentException e) {
                return null;
            }
        }
        JsonNode command = s.path("command");
        List<String> args = new ArrayList<>();
        String program;
        if (command.isArray()) {
            // opencode's shape: the command and its arguments in one array.
            List<String> all = strings(command);
            if (all.isEmpty()) {
                return null;
            }
            program = all.getFirst();
            args.addAll(all.subList(1, all.size()));
        } else {
            program = text(s, "command");
            args.addAll(strings(s.path("args")));
        }
        if (program == null) {
            return null;
        }
        JsonNode env = s.has("env") ? s.path("env") : s.path("environment");
        return new RunOption.Command(program, args, settings(env, SettingKind.ENV));
    }

    private static List<Setting> settings(JsonNode values, SettingKind kind) {
        List<Setting> out = new ArrayList<>();
        if (!values.isObject()) {
            return out;
        }
        for (Map.Entry<String, JsonNode> e : values.properties()) {
            boolean secret = SECRET.matcher(e.getKey()).find();
            String value = e.getValue().isString() ? e.getValue().asString() : null;
            out.add(new Setting(e.getKey(), kind, null, true, secret, secret ? null : value));
        }
        return out;
    }

    private static List<String> strings(JsonNode array) {
        List<String> out = new ArrayList<>();
        for (JsonNode n : array) {
            if (n.isString()) {
                out.add(n.asString());
            }
        }
        return out;
    }

    private static @Nullable String first(JsonNode node, String... fields) {
        for (String f : fields) {
            String v = text(node, f);
            if (v != null) {
                return v;
            }
        }
        return null;
    }

    private static @Nullable String text(JsonNode node, String field) {
        JsonNode v = node.path(field);
        return v.isString() && !v.asString().isBlank() ? v.asString().strip() : null;
    }
}
