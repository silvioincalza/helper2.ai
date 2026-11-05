package ai.helper2.shared

import java.security.MessageDigest
import java.util.HexFormat

/**
 * One category of the AI world (ADR 0051, 0053), as search and the catalog see it: its id (the
 * search filter value), its names, its family, its words and its rules. The discovery collector of
 * the same id searches for it (the sources module).
 *
 * @param requireKeyword a resource gets it only from its finder or from a word in its own title or
 *   description, never from a tag alone (health, legal)
 * @param type what a product its discovery finds becomes; PROMPT and SKILL name their category
 *   ([CategoryMatcher])
 */
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

/**
 * Every category, in the configured order (`helper2.categories`). The order is the order people see
 * them in. Startup fails on an id that is not lower-case words with hyphens, on two categories with
 * one id, and on the id `unknown`: that is the "no category yet" bucket of the search facet.
 */
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

  /**
   * The values of a category filter that are neither a category nor [NONE] (the no-category
   * bucket), in their order: a request with one is refused.
   */
  fun notFilterValues(values: Collection<String>): List<String> =
    values.filter { it != NONE && it !in byId }.distinct()

  /** [ids] that are categories, in the configured order. */
  fun ordered(ids: Collection<String>): List<String> =
    ids.filter { it in byId }.distinct().sortedBy { position.getValue(it) }

  /**
   * Changes when an id, a word or a rule changes: the categories of the catalog are assigned again
   * ([fingerprint] is the backfill's marker). Names do not count: they do not change a match.
   */
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
    /** The facet value of a resource with no category yet; never a category id. */
    const val NONE = "unknown"

    @JvmField val EMPTY = CategoryCatalog(emptyList())

    private val TAG = Regex("[a-z0-9]+(-[a-z0-9]+)*")
  }
}

/**
 * What a resource's categories are read from: its own title and description, its tags, its type and
 * the source ids of the collectors that found it.
 */
data class CategoryInput
@JvmOverloads
constructor(
  val title: String,
  val description: String? = null,
  val tags: Collection<String> = emptyList(),
  val type: ResourceType? = null,
  val finders: Collection<String> = emptyList(),
)

/**
 * Assigns categories by code (ADR 0053): no model, the same input always gives the same answer. A
 * resource gets a category when:
 * - a collector of that category found it (`discovery-<id>`);
 * - one of the category's words is in its title or description;
 * - one of its tags is the category's id or has one of its words;
 * - its type is PROMPT or SKILL and exactly one category has that type.
 *
 * A category that requires a word (health, legal) comes only from the first two: a tag or a type
 * alone is not enough. The categories are returned in the configured order.
 */
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
      if (c.words.matches(text) || (!c.requireKeyword && byTag(c, input.tags))) found += c.id
    }
    input.type?.let { namedByType[it] }?.let { found += it }
    return catalog.ordered(found)
  }

  private fun byTag(c: Category, tags: Collection<String>): Boolean = tags.any {
    it.equals(c.id, ignoreCase = true) || c.words.matches(it)
  }

  companion object {
    /** Types that are a category on their own: a prompt library, a skill. */
    private val NAMING_TYPES = listOf(ResourceType.PROMPT, ResourceType.SKILL)

    @JvmField val NONE = CategoryMatcher(CategoryCatalog.EMPTY)
  }
}
