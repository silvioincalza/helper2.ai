package ai.helper2.scout.admission

import ai.helper2.search.NOW
import ai.helper2.search.PopularityKind
import ai.helper2.search.PopularityMetric
import ai.helper2.search.Refusal
import ai.helper2.search.SourceHit
import ai.helper2.search.Vitality
import ai.helper2.shared.CanonicalUrl
import ai.helper2.shared.DateFact
import ai.helper2.shared.ResourceType
import java.net.URI
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

class AdmissionPolicyTest {

  private class Case(
    val name: String,
    val profile: String,
    val stated: SourceHit,
    val expected: Admission,
  )

  private val policy = AdmissionFixtures.AS_CONFIGURED

  private val cases =
    listOf(
      Case(
        "a topic leader with 1,000 stars pushed exactly at the edge of the window",
        LEADERS,
        repository(stars = 1000, pushed = NOW.minus(Duration.ofDays(90))),
        Admission.Index,
      ),
      Case(
        "a topic leader pushed one second before the window",
        LEADERS,
        repository(stars = 1000, pushed = NOW.minus(Duration.ofDays(90)).minusSeconds(1)),
        Admission.Review(ReviewReason.NOT_ACTIVE),
      ),
      Case(
        "a topic leader with 90,000 stars pushed two days ago",
        LEADERS,
        repository(stars = 90_000, pushed = NOW.minus(Duration.ofDays(2))),
        Admission.Index,
      ),
      Case(
        "a topic leader with 14,000 stars pushed 400 days ago",
        LEADERS,
        repository(stars = 14_000, pushed = NOW.minus(Duration.ofDays(400))),
        Admission.Review(ReviewReason.NOT_ACTIVE),
      ),
      Case(
        "a topic leader with 999 stars pushed today",
        LEADERS,
        repository(stars = 999, pushed = NOW),
        Admission.Review(ReviewReason.FEW_STARS),
      ),
      Case(
        "a topic leader whose last push is not known",
        LEADERS,
        repository(stars = 5000, pushed = null),
        Admission.Review(ReviewReason.NOT_ACTIVE),
      ),
      Case(
        "a topic leader that states no stars",
        LEADERS,
        repository(stars = null, pushed = NOW),
        Admission.Review(ReviewReason.FEW_STARS),
      ),
      Case(
        "an archived topic leader with every number above the floor",
        LEADERS,
        repository(stars = 90_000, pushed = NOW, vitality = Vitality(archived = true)),
        Admission.Refuse(Refusal.ARCHIVED),
      ),
      Case(
        "a trending model with 2,971 likes",
        TRENDING,
        model(likes = 2971),
        Admission.Index,
      ),
      Case(
        "a trending model with 20 likes",
        TRENDING,
        model(likes = 20),
        Admission.Index,
      ),
      Case(
        "a trending model with 3 likes",
        TRENDING,
        model(likes = 3),
        Admission.Review(ReviewReason.FEW_LIKES),
      ),
      Case(
        "a trending model that states no likes",
        TRENDING,
        model(likes = null),
        Admission.Review(ReviewReason.FEW_LIKES),
      ),
      Case(
        "a disabled trending model with many likes",
        TRENDING,
        model(likes = 2971, vitality = Vitality(deprecated = true)),
        Admission.Refuse(Refusal.DEPRECATED),
      ),
      Case(
        "a package with a GitHub repository and a summary",
        PYPI,
        pythonPackage(repository = "https://github.com/acme/torch-tools", summary = "Tools"),
        Admission.Index,
      ),
      Case(
        "a package with a summary and no repository",
        PYPI,
        pythonPackage(repository = null, summary = "An agent toolkit"),
        Admission.Review(ReviewReason.NO_REPOSITORY),
      ),
      Case(
        "a package with a repository and no summary",
        PYPI,
        pythonPackage(repository = "https://github.com/acme/torch-tools", summary = null),
        Admission.Review(ReviewReason.NO_DESCRIPTION),
      ),
      Case(
        "a package with neither",
        PYPI,
        pythonPackage(repository = null, summary = null),
        Admission.Review(ReviewReason.NO_REPOSITORY),
      ),
      Case(
        "a repository of a collector that has no floor, with three stars",
        "github-awesome",
        repository(stars = 3, pushed = null),
        Admission.Index,
      ),
      Case(
        "an idle fork of a collector that has no floor",
        "github-awesome",
        repository(stars = 3, pushed = null, vitality = Vitality(fork = true)),
        Admission.Refuse(Refusal.IDLE_FORK),
      ),
      Case(
        "an active fork with 50 stars of a collector that has no floor",
        "github-awesome",
        repository(stars = 50, pushed = NOW, vitality = Vitality(fork = true)),
        Admission.Index,
      ),
    )

  @Test
  fun `each stated resource gets the outcome of its collector profile`() {
    cases.forEach { case ->
      assertEquals(case.expected, policy.decide(case.stated, case.profile), case.name)
    }
  }

  @Test
  fun `only an index outcome indexes`() {
    assertEquals(
      listOf(true, false, false),
      listOf(
          repository(stars = 1000, pushed = NOW),
          repository(stars = 999, pushed = NOW),
          repository(stars = 1000, pushed = NOW, vitality = Vitality(archived = true)),
        )
        .map { policy.indexes(it, LEADERS) },
    )
  }

  @Test
  fun `a policy without rules indexes everything the vitality rule admits`() {
    val noRules = AdmissionPolicy()

    assertEquals(Admission.Index, noRules.decide(model(likes = 0), TRENDING))
    assertEquals(
      Admission.Refuse(Refusal.ARCHIVED),
      noRules.decide(repository(1, null, Vitality(archived = true)), LEADERS),
    )
  }

  private companion object {
    const val LEADERS = AdmissionFixtures.TOPIC_LEADERS
    const val TRENDING = AdmissionFixtures.HF_TRENDING
    const val PYPI = AdmissionFixtures.PYPI

    fun repository(
      stars: Long?,
      pushed: Instant?,
      vitality: Vitality = Vitality.UNKNOWN,
    ): SourceHit =
      stated(
        "https://github.com/acme/tool",
        popularity = listOfNotNull(stars?.let { metric(PopularityKind.GITHUB_STARS, it) }),
        updated = pushed,
        vitality = vitality,
      )

    fun model(likes: Long?, vitality: Vitality = Vitality.UNKNOWN): SourceHit =
      stated(
        "https://huggingface.co/acme/model",
        type = ResourceType.MODEL,
        popularity = listOfNotNull(likes?.let { metric(PopularityKind.HF_LIKES, it) }),
        vitality = vitality,
      )

    fun pythonPackage(repository: String?, summary: String?): SourceHit =
      stated(
        "https://pypi.org/project/torch-tools/",
        repository = repository,
        description = summary,
        updated = NOW,
      )

    fun metric(kind: PopularityKind, value: Long) = PopularityMetric(kind, value, "source", NOW)

    fun stated(
      url: String,
      type: ResourceType = ResourceType.LIBRARY,
      repository: String? = null,
      description: String? = "A description.",
      popularity: List<PopularityMetric> = emptyList(),
      updated: Instant? = null,
      vitality: Vitality = Vitality.UNKNOWN,
    ): SourceHit =
      SourceHit(
        sourceId = "source",
        externalId = url,
        url = URI.create(url),
        canonicalUrl = CanonicalUrl.of(url)!!,
        repositoryUrl = repository?.let { CanonicalUrl.of(it) },
        title = url.substringAfterLast('/'),
        description = description,
        type = type,
        updated = DateFact.fromSource(updated, "source", NOW),
        popularity = popularity,
        retrievedAt = NOW,
        vitality = vitality,
      )
  }
}
