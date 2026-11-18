package ai.helper2.shared

import java.net.URLEncoder
import java.nio.charset.StandardCharsets

object McpRegistryEntry {
  @JvmStatic
  fun url(baseUrl: String, name: String): String =
    baseUrl +
      "/v0.1/servers/" +
      URLEncoder.encode(name, StandardCharsets.UTF_8) +
      "/versions/latest"
}
