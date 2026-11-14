package ai.helper2.scout

import ai.helper2.shared.ResourceType

object FieldsByType {

  const val ABOUT = "ABOUT"

  const val MAX_ABOUT = 3

  private val WRITING = setOf(ResourceType.ARTICLE, ResourceType.NEWS, ResourceType.PAPER)

  private val WRITING_FIELDS = setOf("audience", "techLevel", "useCase")

  private val TEACHES_OR_PRESENTS = setOf(ResourceType.COURSE, ResourceType.EVENT)

  const val TYPE = "type"

  private val FIELDS =
    listOf("audience", "techLevel", "pricing", "openSource", "license", "useCase", "platforms")

  @JvmField val KINDS = listOf("INTEGRATES_WITH", "USES", "SUPPORTS", "COMPATIBLE_WITH")

  @JvmStatic fun isWriting(type: ResourceType): Boolean = type in WRITING

  @JvmStatic
  fun allowsField(type: ResourceType, field: String): Boolean =
    !isWriting(type) || field in WRITING_FIELDS

  @JvmStatic
  fun allowsRelationship(type: ResourceType, kind: String): Boolean =
    when {
      isWriting(type) -> kind == ABOUT
      kind == ABOUT -> type in TEACHES_OR_PRESENTS
      else -> true
    }

  private const val USE_CASE = "useCase"

  @JvmStatic fun readsUseCase(type: ResourceType): Boolean = type != ResourceType.LIBRARY

  @JvmStatic
  fun fieldsFor(type: ResourceType): List<String> =
    when {
      type == ResourceType.LIBRARY -> FIELDS - USE_CASE
      isWriting(type) -> FIELDS.filter { it in WRITING_FIELDS }
      type == ResourceType.OTHER -> FIELDS + TYPE
      else -> FIELDS
    }

  @JvmStatic
  fun kindsFor(type: ResourceType): List<String> =
    when {
      isWriting(type) -> listOf(ABOUT)
      type in TEACHES_OR_PRESENTS -> KINDS + ABOUT
      else -> KINDS
    }
}
