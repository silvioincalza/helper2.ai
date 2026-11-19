package ai.helper2.site.pages

interface SiteLists {
  fun newInCatalog(): List<Related>

  fun upcomingEvents(): List<Related>

  companion object {
    val NONE =
      object : SiteLists {
        override fun newInCatalog(): List<Related> = emptyList()

        override fun upcomingEvents(): List<Related> = emptyList()
      }
  }
}
