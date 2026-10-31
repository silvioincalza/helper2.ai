package ai.helper2.search.usecase

import ai.helper2.platform.quota.QuotaCounter
import ai.helper2.platform.quota.QuotaCounter.Counter
import ai.helper2.platform.quota.QuotaCounter.Result
import ai.helper2.platform.quota.QuotaKeys
import ai.helper2.platform.resilience.Resilience
import ai.helper2.platform.resilience.Resilience.Circuit
import ai.helper2.search.DescribedSource
import ai.helper2.search.RecordedOutcomes
import ai.helper2.search.ServedFrom
import ai.helper2.search.SourceHealth
import ai.helper2.search.SourceOutcome
import ai.helper2.search.SourceStatus
import ai.helper2.search.WebCredits
import ai.helper2.search.WebSearchQuota
import ai.helper2.search.descriptor
import ai.helper2.testing.MovableClock
import java.time.Duration
import java.time.Instant
import java.util.Optional
import java.util.function.Supplier
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ListSourcesTest {

    private val now = Instant.parse("2026-10-31T09:00:00Z")
    private val keys = QuotaKeys("test-secret", MovableClock(now))

    private class Described(override val descriptor: ai.helper2.search.SourceDescriptor) : DescribedSource

    /** Circuits by policy name; every other policy has none. */
    private class Circuits(val states: Map<String, Circuit>) : Resilience {
        override fun <T> call(policy: String, work: Supplier<T>): T = work.get()
        override fun require(policy: String) {}
        override fun circuit(policy: String): Optional<Circuit> = Optional.ofNullable(states[policy])
    }

    /** A counter with fixed counts; reading fails when [down]. */
    private class Counts(val counts: Map<Counter, Long>, val down: Boolean = false) : QuotaCounter {
        override fun take(own: Counter?, ownLimit: Int, global: Counter?, globalLimit: Long): Result = error("not counted here")
        override fun used(counter: Counter): Long = if (down) error("down") else counts[counter] ?: 0
        override fun delete(keys: Collection<String>) {}
    }

    private val github = Described(descriptor("github", priority = 20))
    private val npm = Described(descriptor("npm", priority = 40))
    private val tavily = Described(descriptor("tavily", priority = 48).copy(metered = true))

    private fun quota(counter: QuotaCounter = Counts(mapOf(Counter(keys.global(), "credits") to 12L)), cap: Long? = 200) =
        WebSearchQuota(counter, keys, globalDailyCredits = cap)

    private fun list(
        sources: List<DescribedSource> = listOf(npm, tavily, github),
        health: SourceHealth = SourceHealth(),
        recorded: RecordedOutcomes = RecordedOutcomes { emptyMap() },
        resilience: Resilience = Circuits(emptyMap()),
        quota: WebSearchQuota = quota(),
    ) = ListSources(sources, health, recorded, resilience, quota).list()

    @Test
    fun `sources are listed by priority, then id`() {
        assertEquals(listOf("github", "npm", "tavily"), list().map { it.descriptor.id })
    }

    @Test
    fun `each source shows this replica's response time and its circuit`() {
        val health = SourceHealth()
        health.sourceCompleted(SourceStatus(github.descriptor, SourceOutcome.SUCCEEDED, ServedFrom.LIVE, 3, Duration.ofMillis(420), now))
        val opened = now.minusSeconds(12)
        val circuits = Circuits(mapOf(
            "source-github" to Circuit(Circuit.State.CLOSED, null),
            "source-npm" to Circuit(Circuit.State.OPEN, opened),
        ))

        val byId = list(health = health, resilience = circuits).associateBy { it.descriptor.id }

        assertEquals(Duration.ofMillis(420), byId.getValue("github").responseTime)
        assertEquals(Circuit(Circuit.State.CLOSED, null), byId.getValue("github").circuit)
        assertEquals(Circuit(Circuit.State.OPEN, opened), byId.getValue("npm").circuit)
        assertNull(byId.getValue("npm").responseTime)
        assertNull(byId.getValue("tavily").circuit, "a policy without a circuit")
    }

    @Test
    fun `a disabled source shows no circuit`() {
        val off = Described(descriptor("github", enabled = false))
        val circuits = Circuits(mapOf("source-github" to Circuit(Circuit.State.CLOSED, null)))

        assertNull(list(sources = listOf(off), resilience = circuits).single().circuit)
    }

    @Test
    fun `only an enabled metered source shows the credits of the day, with the cap`() {
        val byId = list().associateBy { it.descriptor.id }

        assertEquals(WebCredits(12, 200), byId.getValue("tavily").webCredits)
        assertNull(byId.getValue("github").webCredits)

        val off = Described(tavily.descriptor.copy(enabled = false))
        assertNull(list(sources = listOf(off)).single().webCredits)
    }

    @Test
    fun `credits that cannot be read are left out, the rest still shows`() {
        val listed = list(quota = quota(Counts(emptyMap(), down = true)))

        assertNull(listed.single { it.descriptor.id == "tavily" }.webCredits)
        assertEquals(3, listed.size)
    }

    @Test
    fun `a newer Scout outcome wins, and an unreadable record leaves this replica's outcomes`() {
        val health = SourceHealth()
        health.sourceCompleted(SourceStatus(github.descriptor, SourceOutcome.FAILED, ServedFrom.NONE, 0, null, now.minusSeconds(60)))

        val recorded = list(health = health, recorded = { mapOf("github" to SourceHealth.Entry(SourceOutcome.SUCCEEDED, now)) })
        assertEquals(SourceHealth.Entry(SourceOutcome.SUCCEEDED, now), recorded.first().last)

        val unreadable = list(health = health, recorded = { error("database down") })
        assertEquals(SourceOutcome.FAILED, unreadable.first().last!!.outcome)
    }
}
