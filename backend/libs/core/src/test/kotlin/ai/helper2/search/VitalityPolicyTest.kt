package ai.helper2.search

import ai.helper2.shared.ResourceType
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VitalityPolicyTest {

  private val policy = VitalityPolicy(VitalityRules(hostedAppMinLikes = 3, forkMinStars = 10))
  private val longAgo = NOW.minus(Duration.ofDays(1500)).toString()
  private val lately = NOW.minus(Duration.ofDays(30)).toString()

  private fun repo(vitality: Vitality, stars: Long = 0, updated: String? = null) =
    hit("github", "https://github.com/a/x", updated = updated, vitality = vitality)
      .copy(
        popularity = listOf(PopularityMetric(PopularityKind.GITHUB_STARS, stars, "github", NOW))
      )

  private fun space(running: Boolean, likes: Long) =
    hit("hf-spaces", "https://huggingface.co/spaces/a/x", vitality = Vitality(running = running))
      .copy(popularity = listOf(PopularityMetric(PopularityKind.HF_LIKES, likes, "hf-spaces", NOW)))

  @Test
  fun `a hit that states nothing is admitted`() {
    assertNull(policy.refusalOf(hit("npm", "https://www.npmjs.com/package/x")))
  }

  @Test
  fun `an archived repository is refused whatever its stars`() {
    assertEquals(
      Refusal.ARCHIVED,
      policy.refusalOf(repo(Vitality(archived = true), stars = 90_000, updated = lately)),
    )
  }

  @Test
  fun `a deprecated package is refused`() {
    assertEquals(Refusal.DEPRECATED, policy.refusalOf(repo(Vitality(deprecated = true))))
  }

  @Test
  fun `a hosted app that does not run is refused whatever its likes`() {
    assertEquals(Refusal.NOT_RUNNING, policy.refusalOf(space(running = false, likes = 5_000)))
  }

  @Test
  fun `a running hosted app needs the minimum of likes`() {
    assertEquals(Refusal.TOO_FEW_LIKES, policy.refusalOf(space(running = true, likes = 2)))
    assertNull(policy.refusalOf(space(running = true, likes = 3)))
  }

  @Test
  fun `a fork is admitted when it has the stars`() {
    assertNull(policy.refusalOf(repo(Vitality(fork = true), stars = 10, updated = longAgo)))
  }

  @Test
  fun `a fork is admitted when it is maintained`() {
    assertNull(policy.refusalOf(repo(Vitality(fork = true), stars = 0, updated = lately)))
  }

  @Test
  fun `a fork with too few stars and no recent update is refused`() {
    assertEquals(
      Refusal.IDLE_FORK,
      policy.refusalOf(repo(Vitality(fork = true), stars = 9, updated = longAgo)),
    )
    assertEquals(Refusal.IDLE_FORK, policy.refusalOf(repo(Vitality(fork = true), stars = 9)))
  }

  @Test
  fun `a repository with few stars that is no fork is admitted`() {
    assertNull(policy.refusalOf(repo(Vitality(fork = false), stars = 0, updated = longAgo)))
  }

  @Test
  fun `the admitted hits keep their order with consecutive ranks and each refusal is told`() {
    val refused = mutableListOf<Refusal>()
    val hits =
      listOf(
        hit("github", "https://github.com/a/first", rank = 0),
        hit("github", "https://github.com/a/gone", rank = 1, vitality = Vitality(archived = true)),
        hit("github", "https://github.com/a/second", rank = 2),
      )

    val admitted = policy.admitted(hits) { _, refusal -> refused += refusal }

    assertEquals(listOf("first", "second"), admitted.map { it.title })
    assertEquals(listOf(0, 1), admitted.map { it.rank })
    assertEquals(listOf(Refusal.ARCHIVED), refused)
  }

  private fun merged(type: ResourceType, updated: String?) =
    ResultMerger()
      .merge(
        listOf(hit("github", "https://github.com/a/x", type = type, updated = updated)),
        contexts("github"),
      )
      .single()

  @Test
  fun `a library with no update inside the window is unmaintained`() {
    assertTrue(policy.isUnmaintained(merged(ResourceType.LIBRARY, longAgo)))
    assertFalse(policy.isUnmaintained(merged(ResourceType.LIBRARY, lately)))
  }

  @Test
  fun `a repository with ten thousand stars is never unmaintained, however old`() {
    fun starred(stars: Long) =
      merged(ResourceType.LIBRARY, longAgo)
        .copy(
          popularity = listOf(PopularityMetric(PopularityKind.GITHUB_STARS, stars, "github", NOW))
        )

    assertFalse(policy.isUnmaintained(starred(10_000)))
    assertTrue(policy.isUnmaintained(starred(9_999)))
  }

  @Test
  fun `an unknown last update says nothing`() {
    assertFalse(policy.isUnmaintained(merged(ResourceType.LIBRARY, null)))
  }

  @Test
  fun `a model, a paper, news or a product is never unmaintained`() {
    assertFalse(policy.isUnmaintained(merged(ResourceType.MODEL, longAgo)))
    assertFalse(policy.isUnmaintained(merged(ResourceType.PAPER, longAgo)))
    assertFalse(policy.isUnmaintained(merged(ResourceType.NEWS, longAgo)))
    assertFalse(policy.isUnmaintained(merged(ResourceType.PRODUCT, longAgo)))
  }
}
