package ai.helper2.shared

import java.security.MessageDigest
import java.util.HexFormat

data class Category
@JvmOverloads
constructor(
  val id: String,
  val name: String,
  val shortName: String,
  val family: String,
  val words: CategoryWords = CategoryWords.NONE,
  val requireKeyword: Boolean = false,
  val type: ResourceType? = null,
)

class CategoryCatalog(categories: List<Category>) {

  val all: List<Category> = categories.toList()

  private val byId: Map<String, Category> = all.associateBy { it.id }

  private val position: Map<String, Int> = all.withIndex().associate { it.value.id to it.index }

  init {
    all.forEach {
      require(TAG.matches(it.id)) { "category id '${it.id}' must be lower-case words with hyphens" }
      require(it.id != NONE) { "category id '$NONE' is the no-category bucket" }
    }
    require(byId.size == all.size) { "two categories have one id" }
  }

  fun ids(): List<String> = all.map { it.id }

  operator fun contains(id: String?): Boolean = id != null && id in byId

  fun get(id: String): Category? = byId[id]

  fun notFilterValues(values: Collection<String>): List<String> =
    values.filter { it != NONE && it !in byId }.distinct()

  fun ordered(ids: Collection<String>): List<String> =
    ids.filter { it in byId }.distinct().sortedBy { position.getValue(it) }

  val fingerprint: String by lazy {
    val text =
      all.joinToString("\n") {
        listOf(it.id, it.words.words().joinToString(","), it.requireKeyword, it.type)
          .joinToString("|")
      }
    HexFormat.of()
      .formatHex(MessageDigest.getInstance("SHA-256").digest(text.toByteArray()))
      .take(16)
  }

  companion object {
    const val NONE = "unknown"

    @JvmField val EMPTY = CategoryCatalog(emptyList())

    private val TAG = Regex("[a-z0-9]+(-[a-z0-9]+)*")
  }
}

data class CategoryInput
@JvmOverloads
constructor(
  val title: String,
  val description: String? = null,
  val tags: Collection<String> = emptyList(),
  val type: ResourceType? = null,
  val finders: Collection<String> = emptyList(),
  val headings: Collection<String> = emptyList(),
)

class CategoryMatcher(val catalog: CategoryCatalog) {

  private val namedByType: Map<ResourceType, String> =
    NAMING_TYPES.mapNotNull { type ->
        catalog.all
          .filter { it.type == type && !it.requireKeyword }
          .singleOrNull()
          ?.let { type to it.id }
      }
      .toMap()

  fun match(input: CategoryInput): List<String> {
    val found = mutableSetOf<String>()
    input.finders.forEach { finder ->
      if (WebSources.isDiscovery(finder)) {
        val id = finder.removePrefix(WebSources.DISCOVERY_PREFIX)
        if (id in catalog) found += id
      }
    }
    val text = listOfNotNull(input.title, input.description).joinToString("\n")
    for (c in catalog.all) {
      if (c.id in found) continue
      if (c.words.matches(text) || (!c.requireKeyword && byLabel(c, input))) found += c.id
    }
    input.type?.let { namedByType[it] }?.let { found += it }
    return catalog.ordered(found)
  }

  private fun byLabel(c: Category, input: CategoryInput): Boolean =
    byTag(c, input.tags) || byHeading(c, input.headings)

  private fun byTag(c: Category, tags: Collection<String>): Boolean = tags.any {
    it.equals(c.id, ignoreCase = true) || c.words.matches(it)
  }

  private fun byHeading(c: Category, paths: Collection<String>): Boolean =
    paths
      .asSequence()
      .flatMap { it.split(HEADING_SEPARATOR).asSequence() }
      .map(String::trim)
      .any { CategoryWords.normal(it) == CategoryWords.normal(c.id) || c.words.matches(it) }

  companion object {
    private val NAMING_TYPES = listOf(ResourceType.PROMPT, ResourceType.SKILL)

    const val HEADING_SEPARATOR = " > "

    @JvmField val NONE = CategoryMatcher(CategoryCatalog.EMPTY)
  }
}
