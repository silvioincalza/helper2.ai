package ai.helper2.shared

import java.net.URI
import java.net.URISyntaxException

/**
 * Canonical form of a resource URL, used for identity and deduplication.
 *
 * Rules: http(s) only; scheme forced to https; host lower-cased without `www.`; default port,
 * fragment and tracking parameters removed; remaining query parameters sorted; trailing slash
 * removed. GitHub URLs collapse to `https://github.com/{owner}/{repo}` in lower case, because GitHub
 * treats owner/repo case-insensitively. A sub-directory (`/tree/{ref}/{path}`) is kept, with its ref and
 * path case, so each package of a monorepo has its own identity. Other hosts keep path case.
 */
class CanonicalUrl private constructor(val value: String) {

    val host: String get() = URI(value).host

    override fun equals(other: Any?) = other is CanonicalUrl && other.value == value
    override fun hashCode() = value.hashCode()
    override fun toString() = value

    companion object {
        private val TRACKING_PARAMS = setOf("ref", "ref_src", "fbclid", "gclid", "mc_cid", "mc_eid", "source")
        private val GIT_PREFIX = Regex("^(git\\+)?(git|ssh|https?)://(git@)?")

        @JvmStatic
        fun of(raw: String?): CanonicalUrl? {
            if (raw.isNullOrBlank() || raw.length > 2000) return null
            val cleaned = raw.trim().replace(GIT_PREFIX) { m -> if (m.value.contains("://")) "https://" else m.value }
            val uri = try {
                URI(cleaned)
            } catch (_: URISyntaxException) {
                return null
            }
            val scheme = uri.scheme?.lowercase() ?: return null
            if (scheme != "http" && scheme != "https") return null
            val rawHost = uri.host?.lowercase() ?: return null
            if (rawHost.isBlank() || !rawHost.contains('.')) return null
            val host = rawHost.removePrefix("www.")
            val port = if (uri.port == -1 || uri.port == 80 || uri.port == 443) "" else ":${uri.port}"

            if (host == "github.com") {
                val segments = (uri.rawPath ?: "").split('/').filter { it.isNotEmpty() }
                if (segments.size >= 2) {
                    val owner = segments[0].lowercase()
                    val repo = segments[1].lowercase().removeSuffix(".git")
                    val subDirectory = if (segments.size > 4 && segments[2] == "tree") "/" + segments.drop(2).joinToString("/") else ""
                    return CanonicalUrl("https://github.com/$owner/$repo$subDirectory")
                }
            }

            val path = (uri.rawPath ?: "").trimEnd('/')
            val query = uri.rawQuery
                ?.split('&')
                ?.filter { it.isNotBlank() }
                ?.filterNot { param ->
                    val name = param.substringBefore('=').lowercase()
                    name.startsWith("utm_") || name in TRACKING_PARAMS
                }
                ?.sorted()
                ?.joinToString("&")
                ?.takeIf { it.isNotEmpty() }
            return CanonicalUrl("https://$host$port$path" + (query?.let { "?$it" } ?: ""))
        }
    }
}
