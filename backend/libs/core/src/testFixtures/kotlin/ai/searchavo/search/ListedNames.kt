package ai.searchavo.search

class ListedNames {
  private val names = mutableSetOf<String>()

  val libraries: ShadowLibraries = ShadowLibraries.readFrom(Store()).also { it.reload() }

  fun add(name: String) {
    names += name
    libraries.reload()
  }

  private inner class Store : ShadowLibraryStore {
    override fun all() = emptyList<ShadowLibrary>()

    override fun names() = names.toSet()

    override fun add(library: ShadowLibrary) = false

    override fun remove(name: String): ShadowLibrary? = null
  }
}
