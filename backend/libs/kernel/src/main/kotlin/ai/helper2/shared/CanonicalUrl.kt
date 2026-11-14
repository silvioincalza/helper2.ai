package ai.helper2.shared

import java.net.URI
import java.net.URISyntaxException

class CanonicalUrl private constructor(val value: String) {

  val host: String
    get() = URI(value).host

  val isGitHubRepository: Boolean
    get() {
      if (!value.startsWith(GITHUB)) return false
      val segments = value.removePrefix(GITHUB).split('/')
      return segments.size == 2 && segments[0] !in GITHUB_SITE_PAGES
    }

  override fun equals(other: Any?) = other is CanonicalUrl && other.value == value

  override fun hashCode() = value.hashCode()

  override fun toString() = value

  companion object {
    private const val GITHUB = "https://github.com/"
    private const val ANY_REF = "HEAD"
    private val TRACKING_PARAMS =
      setOf("ref", "ref_src", "fbclid", "gclid", "mc_cid", "mc_eid", "source")
    private val GIT_PREFIX = Regex("^(git\\+)?(git|ssh|https?)://(git@)?")
    private val GITHUB_SITE_PAGES =
      setOf(
        "about",
        "apps",
        "collections",
        "customer-stories",
        "enterprise",
        "events",
        "explore",
        "features",
        "login",
        "marketplace",
        "orgs",
        "pricing",
        "readme",
        "resources",
        "security",
        "settings",
        "solutions",
        "sponsors",
        "team",
        "topics",
        "trending",
      )
    private val ARXIV_VIEWS = setOf("abs", "pdf", "html")
    private val ARXIV_VERSION = Regex("v\\d+$")
    private val ARXIV_ID = Regex("\\d{4}\\.\\d{4,5}(v\\d+)?")
    private val HF_REPOSITORY_KINDS = setOf("datasets", "spaces")
    private val ENCODED_DOT = Regex("%2[eE]")
    private val HF_SITE_PAGES =
      setOf(
        "api",
        "blog",
        "changelog",
        "chat",
        "collections",
        "course",
        "docs",
        "enterprise",
        "hardware",
        "inference-endpoints",
        "join",
        "learn",
        "login",
        "models",
        "new",
        "notifications",
        "organizations",
        "papers",
        "posts",
        "pricing",
        "privacy",
        "pro",
        "search",
        "settings",
        "tasks",
        "terms-of-service",
      )

    const val MAX_LENGTH = 2000

    @JvmStatic
    fun of(raw: String?): CanonicalUrl? {
      if (raw.isNullOrBlank() || raw.length > MAX_LENGTH) return null
      val cleaned =
        raw.trim().replace(GIT_PREFIX) { m -> if (m.value.contains("://")) "https://" else m.value }
      val uri =
        try {
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

      val segments = (uri.rawPath ?: "").split('/').filter { it.isNotEmpty() }
      if (segments.any { it.replace(ENCODED_DOT, ".").let { s -> s == "." || s == ".." } })
        return null
      hostRule(host, segments)?.let {
        return it
      }

      val path = (uri.rawPath ?: "").trimEnd('/')
      val query =
        uri.rawQuery
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

    private fun hostRule(host: String, segments: List<String>): CanonicalUrl? =
      when (host) {
        "github.com" -> github(segments)
        "arxiv.org",
        "export.arxiv.org" -> arxiv(segments)
        "huggingface.co",
        "hf.co" -> huggingFace(segments)
        else -> null
      }

    private fun github(segments: List<String>): CanonicalUrl? {
      if (segments.size < 2 || segments[0].lowercase() in GITHUB_SITE_PAGES) return null
      val repository =
        "https://github.com/${segments[0].lowercase()}/${segments[1].lowercase().removeSuffix(".git")}"
      val kind = segments.getOrNull(2)
      val ref = segments.getOrNull(3)
      val inside = segments.drop(4)
      return CanonicalUrl(
        when {
          ref == null || inside.isEmpty() -> repository
          kind == "tree" -> "$repository/tree/$ANY_REF/${inside.joinToString("/")}"
          kind == "blob" && inside.size > 1 && isReadme(inside.last()) ->
            "$repository/tree/$ANY_REF/${inside.dropLast(1).joinToString("/")}"
          else -> repository
        }
      )
    }

    private fun isReadme(name: String) = name.substringBefore('.').lowercase() == "readme"

    private fun arxiv(segments: List<String>): CanonicalUrl? {
      if (segments.size < 2 || segments[0] !in ARXIV_VIEWS) return null
      val id = segments.drop(1).joinToString("/").removeSuffix(".pdf").replace(ARXIV_VERSION, "")
      return CanonicalUrl("https://arxiv.org/abs/$id")
    }

    private fun huggingFace(segments: List<String>): CanonicalUrl? {
      if (segments.isEmpty()) return null
      if (segments[0] == "papers" && segments.size >= 2 && ARXIV_ID.matches(segments[1]))
        return CanonicalUrl("https://arxiv.org/abs/${segments[1].replace(ARXIV_VERSION, "")}")
      val repositoryLength = if (segments[0] in HF_REPOSITORY_KINDS) 3 else 2
      if (segments.size < repositoryLength || segments[0] in HF_SITE_PAGES) return null
      return CanonicalUrl(
        "https://huggingface.co/" + segments.take(repositoryLength).joinToString("/")
      )
    }
  }
}
