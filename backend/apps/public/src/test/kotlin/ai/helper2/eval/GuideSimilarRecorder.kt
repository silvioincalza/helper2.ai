package ai.helper2.eval

import ai.helper2.catalog.Embedder
import ai.helper2.catalog.internal.NvidiaEmbedder
import ai.helper2.platform.llm.LlmPolicies
import ai.helper2.platform.resilience.Resilience
import io.micrometer.observation.ObservationRegistry
import java.time.Duration
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/**
 * Records the question embeddings of docs/eval/guide-similar.json into
 * docs/eval/guide-similar-vectors.json, with the embedding API search uses (ADR 0011):
 * H2_NVIDIA_API_KEY, and H2_NVIDIA_EMBEDDING_BASE_URL and H2_NVIDIA_EMBEDDING_MODEL when not the
 * defaults. Our own fixed questions only, so the file is committed and the evaluation runs in every
 * build. Never part of `test` or CI: `./gradlew evalRecord --tests '*GuideSimilarRecorder'`.
 */
@Tag("eval-record")
class GuideSimilarRecorder {

  @Test
  fun `records one query embedding per question of the set`() {
    val key =
      requireNotNull(System.getenv("H2_NVIDIA_API_KEY")?.ifBlank { null }) {
        "H2_NVIDIA_API_KEY is not set"
      }
    val base =
      System.getenv("H2_NVIDIA_EMBEDDING_BASE_URL") ?: "https://integrate.api.nvidia.com/v1"
    val model = System.getenv("H2_NVIDIA_EMBEDDING_MODEL") ?: "nvidia/nemotron-3.5-embed-1b"
    val embedder =
      NvidiaEmbedder(
        base,
        key,
        model,
        DIMENSIONS,
        Duration.ofSeconds(60),
        ObservationRegistry.NOOP,
        Resilience.NONE,
        LlmPolicies.EMBED,
      )
    val questions = GuideSimilarEvaluation.questions(GuideSimilarEvaluation.set())
    val vectors =
      questions
        // One question per call: NVIDIA fails most calls of 2+ texts with 500 "service inference
        // failed", while every single text goes through (7 Nov 2026, as the indexer saw on 5 Nov).
        .map { question ->
          question to embedder.embed(listOf(question), Embedder.Kind.QUERY).single()
        }
        .associate { (text, vector) -> text to RecordedVectors.encode(vector) }
        .toSortedMap()
    EvalFiles.json
      .writerWithDefaultPrettyPrinter()
      .writeValue(GuideSimilarEvaluation.vectorsFile, RecordedVectors(model, DIMENSIONS, vectors))
    println(
      "recorded ${vectors.size} question embeddings with $model into ${GuideSimilarEvaluation.vectorsFile}"
    )
  }

  private companion object {
    /** helper2.embedding.dimensions: the vectors the guide compares in production. */
    const val DIMENSIONS = 1024
  }
}
