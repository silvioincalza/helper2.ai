package ai.searchavo.catalog

import ai.searchavo.shared.ResourceType

object IndexRule {
  @JvmField
  val INDEXED_TYPES: Set<ResourceType> =
    setOf(
      ResourceType.PRODUCT,
      ResourceType.AGENT,
      ResourceType.MODEL,
      ResourceType.MCP_SERVER,
      ResourceType.LIBRARY,
      ResourceType.SKILL,
      ResourceType.DATASET,
      ResourceType.PAPER,
      ResourceType.EVENT,
      ResourceType.COURSE,
    )

  @JvmField val LISTED_TYPES: Set<ResourceType> = INDEXED_TYPES + ResourceType.PROMPT

  const val PROMPT_MIN_LENGTH = 200

  @JvmStatic fun listedTypeNames(): List<String> = LISTED_TYPES.map { it.name }
}
