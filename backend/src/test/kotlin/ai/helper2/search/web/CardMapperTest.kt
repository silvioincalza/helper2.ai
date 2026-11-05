package ai.helper2.search.web

import ai.helper2.api.generated.model.CompatibilityFlagDto
import ai.helper2.api.generated.model.ListMatchDto
import ai.helper2.search.ListMatch
import ai.helper2.search.ResultMerger
import ai.helper2.search.contexts
import ai.helper2.search.hit
import ai.helper2.shared.ResourceType
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * "Works with" flags (ADR 0013) are mapped once, so a result card and a guide pick show the same
 * flags.
 */
class CardMapperTest {

  private val merged =
    ResultMerger()
      .merge(
        listOf(
          hit(
            "github",
            "https://github.com/a/srv",
            type = ResourceType.MCP_SERVER,
            tags = setOf("openai"),
          )
        ),
        contexts("github"),
      )
      .single()

  @Test
  fun `compatibility maps every flag with its basis, evidence and source`() {
    val flags = CardMapper.compatibility(merged)

    assertEquals(listOf("openai", "mcp"), flags.map { it.tool })
    assertEquals(CompatibilityFlagDto.BasisEnum.TAG, flags[0].basis)
    assertEquals("openai", flags[0].evidence)
    assertEquals("github", flags[0].sourceId)
    assertEquals(CompatibilityFlagDto.BasisEnum.TYPE, flags[1].basis)
    assertEquals(null, flags[1].sourceId)
  }

  @Test
  fun `the card carries the same flags`() {
    assertEquals(CardMapper.compatibility(merged), CardMapper.card(merged).compatibleWith)
  }

  @Test
  fun `found in a list shows only when no source matched the result directly`() {
    val inList = ListMatch("acme/awesome-llm", "Tools > Vector databases")
    val listOnly = hit("helper2-index", "https://github.com/a/store").copy(foundIn = inList)
    val direct = hit("github", "https://github.com/a/store")

    val onlyInList = ResultMerger().merge(listOf(listOnly), contexts("helper2-index")).single()
    val alsoDirect =
      ResultMerger().merge(listOf(listOnly, direct), contexts("helper2-index", "github")).single()

    assertEquals(
      ListMatchDto("acme/awesome-llm", "Tools > Vector databases"),
      CardMapper.card(onlyInList).foundIn,
    )
    assertEquals(null, CardMapper.card(alsoDirect).foundIn)
  }

  @Test
  fun `no rule matched gives an empty list, not null`() {
    val plain =
      ResultMerger()
        .merge(listOf(hit("github", "https://github.com/a/plain")), contexts("github"))
        .single()

    assertEquals(emptyList(), CardMapper.compatibility(plain))
  }
}
