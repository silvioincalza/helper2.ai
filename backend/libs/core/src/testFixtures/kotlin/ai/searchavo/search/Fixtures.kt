package ai.searchavo.search

import ai.searchavo.shared.CanonicalUrl
import ai.searchavo.shared.DateFact
import ai.searchavo.shared.ResourceType
import ai.searchavo.shared.SourceMode
import java.net.URI
import java.time.Duration
import java.time.Instant

val NOW: Instant = Instant.parse("2026-10-22T10:00:00Z")

fun descriptor(
  id: String,
  mode: SourceMode = SourceMode.LIVE,
  timeout: Duration = Duration.ofMillis(500),
  priority: Int = 100,
  enabled: Boolean = true,
  confirmsUrlPrefix: String? = null,
) =
  SourceDescriptor(
    id = id,
    name = id.replaceFirstChar { it.uppercase() },
    shortName = id.take(12),
    external = true,
    mode = mode,
    homepage = URI("https://$id.example"),
    termsUrl = null,
    attribution = null,
    resourceTypes = ResourceType.entries.toSet(),
    timeout = timeout,
    cacheTtl = Duration.ofMinutes(10),
    enabled = enabled,
    priority = priority,
    confirmsUrlPrefix = confirmsUrlPrefix,
  )

fun hit(
  source: String,
  url: String,
  title: String = url.substringAfterLast('/'),
  rank: Int = 0,
  repo: String? = null,
  type: ResourceType = ResourceType.LIBRARY,
  license: String? = null,
  published: String? = null,
  updated: String? = null,
  description: String? = null,
  tags: Set<String> = emptySet(),
  vitality: Vitality = Vitality.UNKNOWN,
  name: String? = null,
) =
  SourceHit(
    sourceId = source,
    externalId = name ?: "$source:$url",
    url = URI(url),
    canonicalUrl = CanonicalUrl.of(url)!!,
    repositoryUrl = repo?.let { CanonicalUrl.of(it) },
    title = title,
    description = description,
    type = type,
    license = license,
    tags = tags,
    published = DateFact.fromSource(published?.let(Instant::parse), source, NOW),
    updated = DateFact.fromSource(updated?.let(Instant::parse), source, NOW),
    retrievedAt = NOW,
    rank = rank,
    vitality = vitality,
  )

fun contexts(vararg ids: String, servedFrom: ServedFrom = ServedFrom.LIVE) = ids.associateWith {
  SourceContext(descriptor(it), servedFrom)
}
