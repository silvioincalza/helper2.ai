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
        if (segments.size != 2) return null
        return RepositoryRoot(words(segments[0]), words(segments[1]))
      }
    }
  }

  private class StatedName(val scope: Set<String>, val name: Set<String>) {

    fun belongsTo(root: RepositoryRoot): Boolean =
      name.containsAll(root.name) || sameOwner(root.owner)

    private fun sameOwner(owner: Set<String>): Boolean =
      scope.isNotEmpty() && (owner.containsAll(scope) || scope.containsAll(owner))

    companion object {
      fun of(hit: SourceHit): StatedName {
        val id = packageName(hit.canonicalUrl) ?: hit.externalId
        return if (hit.sourceId == CollectorIds.MCP_REGISTRY) {
          StatedName(emptySet(), words(id.substringAfter('/')))
        } else if (id.startsWith("@") && id.contains('/')) {
          StatedName(words(id.substringBefore('/')), words(id))
        } else {
          StatedName(emptySet(), words(id))
        }
      }
    }
  }

  private val PACKAGE_PAGE = Regex("^https://(?:npmjs\\.com/package|pypi\\.org/project)/([^?#]+)$")

  private fun packageName(address: CanonicalUrl): String? =
    PACKAGE_PAGE.find(address.value)?.groupValues?.get(1)?.let {
      URLDecoder.decode(it, StandardCharsets.UTF_8)
    }

  private val WORD_SEPARATOR = Regex("[^\\p{L}\\p{N}]+")

  private fun words(text: String): Set<String> =
    text.lowercase().split(WORD_SEPARATOR).filter { it.isNotEmpty() }.toSet()
}
