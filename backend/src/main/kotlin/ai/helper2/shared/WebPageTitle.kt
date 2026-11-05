package ai.helper2.shared

/**
 * The title of a web page result, cleaned for display: the HTML `<title>` often ends with the site
 * name, and a GitHub repository page carries its whole description. Conservative: only a final
 * segment that names the result's own site goes, and the title is never empty. Cleaning a clean
 * title changes nothing, so it runs both when a result is stored and when it is read back.
 *
 * - Whitespace is trimmed and inner runs collapse to one space.
 * - On github.com, `GitHub - owner/repo: description · GitHub` becomes `owner/repo`; the
 *   description is kept apart.
 * - A final `" | Site"`, `" - Site"`, `" · Site"`, `" — Site"` or `" – Site"` goes when Site is the
 *   host (`scored.tools`), the registrable domain (`Kaily.ai` on kaily.ai) or its name without
 *   punctuation and spaces (`Medium` on medium.com, `Hugging Face` on huggingface.co). One segment
 *   at most; a leading site name (`Ollama - Get up and running`) stays.
 *
 * [description] is the repository description of a GitHub title, null otherwise.
 */
data class WebPageTitle(val title: String, val description: String?) {

  companion object {
    private val SPACES = Regex("[\\s\\u00a0\\u2007\\u202f]+")
    private val SEPARATORS = listOf(" | ", " - ", " · ", " — ", " – ")
    private val GITHUB_REPOSITORY =
      Regex("^GitHub - ([A-Za-z0-9][A-Za-z0-9-]*/[A-Za-z0-9._-]+)(?:: (.*))?$")
    private const val GITHUB_SUFFIX = " · GitHub"

    /**
     * Second-level labels under which the registrable name is one label further left (bbc.co.uk).
     */
    private val SECOND_LEVEL = setOf("co", "com", "org", "net", "ac", "gov", "edu", "ne", "or")

    /** Cleans [raw] for a page on [host]; without a host only the whitespace is cleaned. */
    @JvmStatic
    fun of(raw: String?, host: String?): WebPageTitle {
      val title = (raw ?: "").replace(SPACES, " ").trim()
      if (host.isNullOrBlank()) return WebPageTitle(title, null)
      val site = host.lowercase().removePrefix("www.")
      if (site == "github.com")
        repository(title)?.let {
          return it
        }
      return WebPageTitle(withoutSiteName(title, site), null)
    }

    /**
     * [title] of a hit or row from [sourceId] at [url]: cleaned when the source gives page titles,
     * as stored otherwise.
     */
    @JvmStatic
    fun forSource(sourceId: String?, title: String, url: CanonicalUrl?): String =
      if (WebSources.isWeb(sourceId)) of(title, url?.host).title else title

    private fun repository(title: String): WebPageTitle? {
      val m = GITHUB_REPOSITORY.matchEntire(title.removeSuffix(GITHUB_SUFFIX)) ?: return null
      val description = m.groups[2]?.value?.trim()?.takeIf { it.isNotEmpty() }
      return WebPageTitle(m.groupValues[1], description)
    }

    private fun withoutSiteName(title: String, site: String): String {
      val (cut, separator) = SEPARATORS.map { title.lastIndexOf(it) to it }.maxBy { it.first }
      if (cut <= 0) return title
      val rest = title.substring(0, cut).trim()
      val segment = title.substring(cut + separator.length).trim()
      return if (rest.isNotEmpty() && namesSite(segment, site)) rest else title
    }

    private fun namesSite(segment: String, site: String): Boolean {
      val s = segment.lowercase()
      val labels = site.split('.')
      if (labels.size < 2) return s == site
      val nameAt =
        if (labels.size >= 3 && labels[labels.size - 2] in SECOND_LEVEL) labels.size - 3
        else labels.size - 2
      val registrable = labels.subList(nameAt, labels.size).joinToString(".")
      val compact = lettersAndDigits(s)
      return s == site ||
        s == registrable ||
        (compact.isNotEmpty() && compact == lettersAndDigits(labels[nameAt]))
    }

    private fun lettersAndDigits(s: String) = s.filter { it.isLetterOrDigit() }
  }
}
