package ai.helper2.search

import ai.helper2.shared.CanonicalUrl
import ai.helper2.shared.CollectorIds
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

object RepositoryJoin {

  @JvmStatic
  fun of(hit: SourceHit): CanonicalUrl? {
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
        if (FOLDER in segments) return null
        val name = segments.lastOrNull() ?: repository.host.substringBefore('.')
        return RepositoryRoot(words(segments.dropLast(1).joinToString(" ")), words(name))
      }

      private const val FOLDER = "tree"
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
      name.containsAll(root.name) || sameOwner(root.owner)

    private fun sameOwner(owner: Set<String>): Boolean =
      scope.isNotEmpty() && (owner.containsAll(scope) || scope.containsAll(owner))
  }

  private class ServerName(val name: Set<String>) : StatedName {
    override fun belongsTo(root: RepositoryRoot): Boolean {
      val telling = (root.name - GENERIC_REPOSITORY_WORDS).ifEmpty { root.name }
      return telling.any { it in name }
    }
  }

  private val GENERIC_REPOSITORY_WORDS =
    setOf("mcp", "server", "servers", "model", "context", "protocol", "modelcontextprotocol")

  private val PACKAGE_PAGE = Regex("^https://(?:npmjs\\.com/package|pypi\\.org/project)/([^?#]+)$")

  private fun packageName(address: CanonicalUrl): String? =
    PACKAGE_PAGE.find(address.value)?.groupValues?.get(1)?.let {
      URLDecoder.decode(it, StandardCharsets.UTF_8)
    }

  private val WORD_SEPARATOR = Regex("[^\\p{L}\\p{N}]+")

  private fun words(text: String): Set<String> =
    text.lowercase().split(WORD_SEPARATOR).filter { it.isNotEmpty() }.toSet()
}
