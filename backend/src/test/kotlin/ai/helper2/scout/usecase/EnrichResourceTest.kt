package ai.helper2.scout.usecase

import ai.helper2.scout.EnrichmentInput
import ai.helper2.scout.EnrichmentMaterial
import ai.helper2.scout.EnrichmentModel
import ai.helper2.scout.EnrichmentOutcome
import ai.helper2.scout.EnrichmentProposal
import ai.helper2.scout.EnrichmentStore
import ai.helper2.scout.Material
import ai.helper2.scout.ValidationResult
import ai.helper2.search.NOW
import java.net.URI
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class EnrichResourceTest {

    private val clock = Clock.fixed(NOW, ZoneOffset.UTC)
    private val material = EnrichmentMaterial(
        EnrichmentInput("x", listOf(Material(UUID.randomUUID(), URI("https://x.example"), NOW, "text"))), "source-text-hash")

    private class CountingModel(override val model: String = "m", override val promptVersion: String = "enrich-v3") : EnrichmentModel {
        override val provider = "fake"
        var calls = 0
        override fun propose(input: EnrichmentInput) = EnrichmentProposal().also { calls++ }
    }

    /** Remembers the fingerprint of the last saved run, as the database would. */
    private inner class MemoryStore(var last: String? = null) : EnrichmentStore {
        val saved = mutableListOf<String>()
        override fun load(resourceId: UUID) = material
        override fun lastFingerprint(resourceId: UUID) = last
        override fun resourcesTitled(titles: Collection<String>, resourceId: UUID) = emptyMap<String, UUID>()
        override fun save(resourceId: UUID, model: EnrichmentModel, inputFingerprint: String, startedAt: Instant,
                          result: ValidationResult?, outcome: EnrichmentOutcome, failure: String?) {
            saved += inputFingerprint
            last = inputFingerprint
        }
    }

    @Test
    fun `the same text, prompt version and model is not sent again`() {
        val model = CountingModel()
        val store = MemoryStore()
        val enrich = EnrichResource(model, store, clock)

        enrich.enrich(UUID.randomUUID())
        val second = enrich.enrich(UUID.randomUUID())

        assertEquals(EnrichmentOutcome.UNCHANGED, second)
        assertEquals(1, model.calls)
    }

    @Test
    fun `a new prompt version reprocesses unchanged text`() {
        val store = MemoryStore(material.fingerprintFor(CountingModel(promptVersion = "enrich-v3")))
        val model = CountingModel(promptVersion = "enrich-v4")

        EnrichResource(model, store, clock).enrich(UUID.randomUUID())

        assertEquals(1, model.calls)
    }

    @Test
    fun `a new model reprocesses unchanged text`() {
        val store = MemoryStore(material.fingerprintFor(CountingModel(model = "nemotron-a")))
        val model = CountingModel(model = "nemotron-b")

        EnrichResource(model, store, clock).enrich(UUID.randomUUID())

        assertEquals(1, model.calls)
    }

    @Test
    fun `the fingerprint covers the material, the prompt version and the model`() {
        val base = material.fingerprintFor(CountingModel())
        assertEquals(base, material.fingerprintFor(CountingModel()))
        assertNotEquals(base, material.copy(textHash = "other-text").fingerprintFor(CountingModel()))
        assertNotEquals(base, material.fingerprintFor(CountingModel(promptVersion = "enrich-v4")))
        assertNotEquals(base, material.fingerprintFor(CountingModel(model = "other")))
    }
}
