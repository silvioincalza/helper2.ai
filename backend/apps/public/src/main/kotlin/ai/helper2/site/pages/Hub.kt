package ai.helper2.site.pages

import ai.helper2.shared.ResourceType

enum class Hub(val kind: PageKind, val type: ResourceType, val label: String) {
  AI_APPS(PageKind.HUB_AI_APPS, ResourceType.PRODUCT, "AI apps"),
  AI_MODELS(PageKind.HUB_AI_MODELS, ResourceType.MODEL, "AI models"),
  AI_AGENTS(PageKind.HUB_AI_AGENTS, ResourceType.AGENT, "AI agents"),
  MCP_SERVERS(PageKind.HUB_MCP_SERVERS, ResourceType.MCP_SERVER, "MCP servers"),
  LIBRARIES(PageKind.HUB_LIBRARIES, ResourceType.LIBRARY, "AI libraries"),
  AGENT_SKILLS(PageKind.HUB_AGENT_SKILLS, ResourceType.SKILL, "Agent skills"),
  DATASETS(PageKind.HUB_DATASETS, ResourceType.DATASET, "AI datasets"),
  PAPERS(PageKind.HUB_PAPERS, ResourceType.PAPER, "AI papers"),
  PROMPTS(PageKind.HUB_PROMPTS, ResourceType.PROMPT, "AI prompts"),
  COURSES(PageKind.HUB_COURSES, ResourceType.COURSE, "AI courses");

  val path: String
    get() = "/" + kind.pattern

  val description: String
    get() =
      "$label in the ${Pages.BRAND} catalog, newest change first, with the license, pricing and " +
        "sources their sources state."

  companion object {
    const val SIZE = 50

    @JvmStatic fun of(kind: PageKind): Hub? = entries.firstOrNull { it.kind == kind }

    @JvmStatic fun of(type: ResourceType): Hub? = entries.firstOrNull { it.type == type }
  }
}
