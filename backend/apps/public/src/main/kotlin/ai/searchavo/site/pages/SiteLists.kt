package ai.searchavo.site.pages

import ai.searchavo.shared.ResourceType

interface SiteLists {
  fun newInCatalog(): List<Related>

  fun upcomingEvents(): List<Related>

  fun hub(type: ResourceType): List<Related>

  companion object {
    val NONE =
      object : SiteLists {
        override fun newInCatalog(): List<Related> = emptyList()

        override fun upcomingEvents(): List<Related> = emptyList()

        override fun hub(type: ResourceType): List<Related> = emptyList()
      }
  }
}
