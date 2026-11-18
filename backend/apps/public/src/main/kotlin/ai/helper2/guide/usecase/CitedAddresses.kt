package ai.helper2.guide.usecase

import ai.helper2.guide.GuideAnswer
import ai.helper2.guide.QuotedFrom
import ai.helper2.search.MergedResult
import ai.helper2.search.ShadowLibraries
import java.net.URI

internal fun GuideAnswer.citesAShadowLibrary(libraries: ShadowLibraries): Boolean =
  citedAddresses().any { libraries.isShadowLibrary(it.host) }

private fun GuideAnswer.citedAddresses(): List<URI> =
  citedResults().flatMap { addressesOf(it) } + quotes().map { it.url }

private fun addressesOf(result: MergedResult): List<URI> =
  listOf(result.url, URI.create(result.canonicalUrl.value)) + result.hits.map { it.url }

private fun GuideAnswer.citedResults(): List<MergedResult> =
  recommendations.map { it.result } +
    search.results +
    comparison?.subjects.orEmpty().mapNotNull { it.result } +
    explanation?.sentences.orEmpty().map { it.source }

private fun GuideAnswer.quotes(): List<QuotedFrom> =
  recommendations.mapNotNull { it.quotedFrom } +
    comparison?.rows.orEmpty().flatMap { row -> row.cells.mapNotNull { it.quotedFrom } } +
    explanation?.sentences.orEmpty().mapNotNull { it.quotedFrom }
