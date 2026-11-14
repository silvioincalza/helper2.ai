package ai.helper2.site.pages

import java.text.Normalizer
import java.util.UUID

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

fun resourcePath(id: UUID, title: String?): String {
  val slug = Slug.of(title)
  return if (slug.isEmpty()) "/resource/$id" else "/resource/$id/$slug"
}
