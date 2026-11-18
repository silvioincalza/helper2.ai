package ai.helper2.search

import ai.helper2.shared.CanonicalUrl
import ai.helper2.shared.CollectorIds
import ai.helper2.shared.WordLists
import java.io.InputStreamReader
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.Properties

object RepositoryJoin {

  @JvmStatic
  fun of(hit: SourceHit): CanonicalUrl? =
    curatedRepositoryOf(hit.canonicalUrl)?.takeUnless { it == hit.canonicalUrl } ?: byRule(hit)

  internal fun byRule(hit: SourceHit): CanonicalUrl? {
    val repository = hit.repositoryUrl ?: return null
    if (repository == hit.canonicalUrl || isInside(hit.canonicalUrl, repository)) return null
    val root = RepositoryRoot.of(repository) ?: return repository
    return if (StatedName.of(hit).belongsTo(root)) repository else null
  }

  private fun isInside(address: CanonicalUrl, repository: CanonicalUrl): Boolean =
    address.value.startsWith(repository.value + "/")

  private class RepositoryRoot(val owner: Set<String>, val name: Set<String>) {
    companion object {
      fun of(repository: CanonicalUrl): RepositoryRoot? {
        val segments = (URI(repository.value).rawPath ?: "").split('/').filter { it.isNotEmpty() }
        if (isFolder(repository.host, segments)) return null
        val name =
          segments.lastOrNull()?.removeSuffix(CLONE) ?: repository.host.substringBefore('.')
        return RepositoryRoot(words(segments.dropLast(1).joinToString(" ")), words(name))
      }

      private const val FOLDER = "tree"

      private const val GITLAB_PAGES = "-"

      private const val CLONE = ".git"

      private const val GITHUB = "github.com"

      private const val GITHUB_FOLDER_AT = 2

      private fun isFolder(host: String, segments: List<String>): Boolean =
        segments.indices.any { at ->
          segments[at] == FOLDER &&
            hasRefAndPath(segments, at) &&
            (isGitHubFolder(host, at) || isGitLabFolder(segments, at))
        }

      private fun hasRefAndPath(segments: List<String>, at: Int) = segments.size >= at + 3

      private fun isGitHubFolder(host: String, at: Int) = host == GITHUB && at == GITHUB_FOLDER_AT

      private fun isGitLabFolder(segments: List<String>, at: Int) =
        at > GITHUB_FOLDER_AT && segments[at - 1] == GITLAB_PAGES
    }
  }

  private sealed interface StatedName {
    fun belongsTo(root: RepositoryRoot): Boolean

    companion object {
      fun of(hit: SourceHit): StatedName {
        val id = packageName(hit.canonicalUrl) ?: hit.externalId
        return when {
          hit.sourceId == CollectorIds.MCP_REGISTRY -> ServerName(words(id.substringAfter('/')))
          id.startsWith("@") && id.contains('/') ->
            PackageName(words(id.substringBefore('/')), words(id))
          else -> PackageName(emptySet(), words(id))
        }
      }
    }
  }

  private class PackageName(val scope: Set<String>, val name: Set<String>) : StatedName {
    override fun belongsTo(root: RepositoryRoot): Boolean =
      name.containsAll(telling(root.name)) || sameOwner(root.owner)

    private fun sameOwner(owner: Set<String>): Boolean =
      scope.isNotEmpty() &&
        owner.isNotEmpty() &&
        (owner.containsAll(scope) || scope.containsAll(owner))
  }

  private class ServerName(val name: Set<String>) : StatedName {
    override fun belongsTo(root: RepositoryRoot): Boolean {
      val repository = telling(root.name)
      val server = telling(name)
      return server.isNotEmpty() &&
        (server.containsAll(repository) || repository.containsAll(server))
    }
  }

  private fun telling(words: Set<String>) = (words - GENERIC_REPOSITORY_WORDS).ifEmpty { words }

  private val GENERIC_REPOSITORY_WORDS =
    WordLists.load("query-words").wordsIn("en", "repository.generic")

  private const val NPM = "npmjs.com/package"

  private const val PYPI = "pypi.org/project"

  private val PACKAGE_PAGE = Regex("^https://($NPM|$PYPI)/([^?#]+)$")

  private fun packageName(address: CanonicalUrl): String? =
    PACKAGE_PAGE.find(address.value)?.groupValues?.get(2)?.let {
      URLDecoder.decode(it, StandardCharsets.UTF_8)
    }

  private val PYPI_NAME_SEPARATOR = Regex("[-_.]+")

  private fun pageKey(address: String): String? =
    PACKAGE_PAGE.find(address)?.let { page ->
      val registry = page.groupValues[1]
      val name = URLDecoder.decode(page.groupValues[2], StandardCharsets.UTF_8).lowercase()
      registry + "/" + if (registry == PYPI) name.replace(PYPI_NAME_SEPARATOR, "-") else name
    }

  private fun curatedRepositoryOf(address: CanonicalUrl): CanonicalUrl? =
    pageKey(address.value)?.let { CURATED[it] }

  private const val CURATED_JOINS = "catalog/repository-joins.properties"

  internal val CURATED: Map<String, CanonicalUrl> by lazy {
    val rows = Properties()
    val stream =
      checkNotNull(RepositoryJoin::class.java.classLoader.getResourceAsStream(CURATED_JOINS)) {
        "$CURATED_JOINS is missing"
      }
    stream.use { rows.load(InputStreamReader(it, StandardCharsets.UTF_8)) }
    rows.stringPropertyNames().associate { page ->
      val key = checkNotNull(pageKey("https://$page")) { "$page is not an npm or PyPI page" }
      val repository = rows.getProperty(page).trim()
      key to checkNotNull(CanonicalUrl.of(repository)) { "$repository is not an address" }
    }
  }

  private val WORD_SEPARATOR = Regex("[^\\p{L}\\p{N}]+")

  private fun words(text: String): Set<String> =
    text.lowercase().split(WORD_SEPARATOR).filter { it.isNotEmpty() }.toSet()
}
