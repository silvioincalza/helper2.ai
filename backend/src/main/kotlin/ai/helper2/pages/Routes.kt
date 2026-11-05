package ai.helper2.pages

import java.util.UUID

/** The pages of the app the server knows (ADR 0050). One list: routes.tsx is checked against it. */
enum class PageKind(val pattern: String) {
  HOME(""),
  SEARCH("search"),
  RESOURCE("resource/:id"),
  SAVED("saved"),
  SOURCES("sources"),
  ACCOUNT("account"),
  MISSION("mission"),
  PRIVACY("privacy"),
  TERMS("terms"),
  ADMIN("admin"),
  ADMIN_PAGE("admin/:page"),
}

/** An address the app owns, as the server reads it: which page, and the id of a resource page. */
sealed interface PageAddress {
  data class Page(val kind: PageKind) : PageAddress

  /** [raw] is the id as written; [id] is null when it is not a UUID. */
  data class Resource(val raw: String, val id: UUID?) : PageAddress

  data object Unknown : PageAddress

  companion object {
    /** [path] without the leading slash or with it; a trailing slash is not the same page. */
    fun of(path: String): PageAddress {
      val p = path.removePrefix("/")
      val parts = p.split('/')
      if (parts.size == 2 && parts[0] == "resource" && parts[1].isNotEmpty()) {
        return Resource(parts[1], uuidOrNull(parts[1]))
      }
      if (parts.size == 2 && parts[0] == "admin" && parts[1].isNotEmpty()) {
        return Page(PageKind.ADMIN_PAGE)
      }
      return PageKind.entries
        .firstOrNull { !it.pattern.contains(':') && it.pattern == p }
        ?.let { Page(it) } ?: Unknown
    }

    private fun uuidOrNull(raw: String): UUID? =
      try {
        UUID.fromString(raw).takeIf { it.toString() == raw.lowercase() }
      } catch (_: IllegalArgumentException) {
        null
      }
  }
}
