package ai.helper2.scout

import ai.helper2.shared.ResourceType

/**
 * What an enrichment may say about a resource of a type (ADR 0023). An article, a news item or a
 * paper is writing about other resources: the price, licence or platforms it quotes are facts of
 * the model or product it talks about, not of the page. So writing keeps only who it is for, how
 * technical it is and its topic, and it names what it is about with an ABOUT relationship. Every
 * other type keeps every field and never uses ABOUT.
 */
object FieldsByType {

  const val ABOUT = "ABOUT"

  /** Subjects kept per run: an article is mainly about a few things, not every name it drops. */
  const val MAX_ABOUT = 3

  private val WRITING = setOf(ResourceType.ARTICLE, ResourceType.NEWS, ResourceType.PAPER)

  /** The fields writing keeps: the reader (audience, tech level) and the topic (use case). */
  private val WRITING_FIELDS = setOf("audience", "techLevel", "useCase")

  @JvmStatic fun isWriting(type: ResourceType): Boolean = type in WRITING

  @JvmStatic
  fun allowsField(type: ResourceType, field: String): Boolean =
    !isWriting(type) || field in WRITING_FIELDS

  @JvmStatic
  fun allowsRelationship(type: ResourceType, kind: String): Boolean =
    if (isWriting(type)) kind == ABOUT else kind != ABOUT
}
