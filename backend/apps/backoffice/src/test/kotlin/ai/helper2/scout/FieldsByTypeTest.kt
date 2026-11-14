package ai.helper2.scout

import ai.helper2.shared.ResourceType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FieldsByTypeTest {

  @Test
  fun `writing is asked for its topic, audience and tech level only`() {
    for (type in listOf(ResourceType.ARTICLE, ResourceType.NEWS, ResourceType.PAPER)) {
      assertEquals(listOf("audience", "techLevel", "useCase"), FieldsByType.fieldsFor(type))
      assertEquals(listOf(FieldsByType.ABOUT), FieldsByType.kindsFor(type))
    }
  }

  @Test
  fun `a resource with a known type is not asked for its type`() {
    assertFalse(FieldsByType.TYPE in FieldsByType.fieldsFor(ResourceType.LIBRARY))
    assertTrue("pricing" in FieldsByType.fieldsFor(ResourceType.LIBRARY))
  }

  @Test
  fun `a library is not asked for a use case, the other tools are`() {
    assertFalse("useCase" in FieldsByType.fieldsFor(ResourceType.LIBRARY))
    assertFalse(FieldsByType.readsUseCase(ResourceType.LIBRARY))
    for (type in listOf(ResourceType.PRODUCT, ResourceType.AGENT, ResourceType.MCP_SERVER)) {
      assertTrue("useCase" in FieldsByType.fieldsFor(type), "$type")
      assertTrue(FieldsByType.readsUseCase(type), "$type")
    }
  }

  @Test
  fun `only a resource of type OTHER is asked what it is`() {
    assertTrue(FieldsByType.TYPE in FieldsByType.fieldsFor(ResourceType.OTHER))
  }

  @Test
  fun `a course and an event keep every kind and also say what they are about`() {
    for (type in listOf(ResourceType.COURSE, ResourceType.EVENT)) {
      assertEquals(FieldsByType.KINDS + FieldsByType.ABOUT, FieldsByType.kindsFor(type))
      assertTrue("pricing" in FieldsByType.fieldsFor(type))
    }
  }

  @Test
  fun `a tool is never about another one`() {
    for (type in listOf(ResourceType.LIBRARY, ResourceType.PRODUCT, ResourceType.OTHER)) {
      assertEquals(FieldsByType.KINDS, FieldsByType.kindsFor(type))
      assertFalse(FieldsByType.allowsRelationship(type, FieldsByType.ABOUT))
    }
  }

  @Test
  fun `every field and kind the model is asked for is one the validator reads`() {
    val allowed = EvidenceValidator.allowedValues()
    for (type in ResourceType.entries) {
      for (field in FieldsByType.fieldsFor(type)) {
        assertTrue("- $field: " in allowed, "$type $field")
        assertTrue(FieldsByType.allowsField(type, field), "$type $field")
      }
      for (kind in FieldsByType.kindsFor(type)) {
        assertTrue(kind in EvidenceValidator.RELATIONSHIP_KINDS, "$type $kind")
        assertTrue(FieldsByType.allowsRelationship(type, kind), "$type $kind")
      }
    }
  }
}
