package ai.helper2.shared

data class WebPageTitle(val title: String, val description: String?) {

  companion object {
    private val SPACES = Regex("[\\s\\u00a0\\u2007\\u202f]+")
    private val SEPARATORS = listOf(" | ", " - ", " · ", " — ", " – ")
    private val GITHUB_REPOSITORY =
      Regex("^GitHub - ([A-Za-z0-9][A-Za-z0-9-]*/[A-Za-z0-9._-]+)(?:: (.*))?$")
    private const val GITHUB_SUFFIX = " · GitHub"

    private val SECOND_LEVEL = setOf("co", "com", "org", "net", "ac", "gov", "edu", "ne", "or")

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
