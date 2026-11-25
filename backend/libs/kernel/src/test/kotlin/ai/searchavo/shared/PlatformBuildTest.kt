package ai.searchavo.shared

import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class PlatformBuildTest {

  @ParameterizedTest
  @ValueSource(
    strings =
      [
        "@anthropic-ai/claude-agent-sdk-linux-x64-musl",
        "@anthropic-ai/claude-agent-sdk-linux-x64",
        "@microsoft/copilot-sdk-darwin-arm64",
        "@esbuild/linux-x64",
        "@biomejs/cli-win32-x64",
        "onnxruntime-node-linux-arm64-gnu",
        "@lancedb/lancedb-darwin-universal",
      ]
  )
  fun `a package built for one operating system and processor is a platform build`(name: String) {
    assertTrue(PlatformBuild.isPlatformBuild(name))
  }

  @ParameterizedTest
  @ValueSource(
    strings =
      [
        "@anthropic-ai/claude-agent-sdk",
        "linux-mcp-server",
        "arm64-tools",
        "windows-mcp",
        "x64dbg-mcp",
        "darwin-ai",
        "@lancedb/lancedb",
      ]
  )
  fun `a package whose name only mentions a system is not a platform build`(name: String) {
    assertFalse(PlatformBuild.isPlatformBuild(name))
  }
}
