package ai.helper2.pages

import java.text.Normalizer
import java.util.UUID

/**
 * The readable part of a resource address: its title in lower case, ASCII letters and digits joined
 * by hyphens, at most [MAX] characters cut at a hyphen (ADR 0029). The id stays the key; the slug
 * is computed from the title on every request, so a renamed resource gets a new one and the old
 * address answers 301. `frontend/src/lib/slug.ts` computes the same slug.
 */
object Slug {
  const val MAX = 60

  private val MARKS = Regex("\\p{M}+")
  private val NOT_WORD = Regex("[^a-z0-9]+")

  fun of(title: String?): String {
    if (title == null) return ""
    val ascii = Normalizer.normalize(title, Normalizer.Form.NFKD).replace(MARKS, "")
    val slug = ascii.lowercase().replace(NOT_WORD, "-").trim('-')
    if (slug.length <= MAX) return slug
    val cut = slug.substring(0, MAX + 1)
    val end = cut.lastIndexOf('-')
    return (if (end > 0) cut.substring(0, end) else slug.substring(0, MAX)).trim('-')
  }
}

/** `/resource/{id}/{slug}`, or `/resource/{id}` when the title gives no slug. */
fun resourcePath(id: UUID, title: String?): String {
  val slug = Slug.of(title)
  return if (slug.isEmpty()) "/resource/$id" else "/resource/$id/$slug"
}
