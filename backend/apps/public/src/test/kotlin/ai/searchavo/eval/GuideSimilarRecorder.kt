package ai.searchavo.eval

import ai.searchavo.catalog.Embedder
import ai.searchavo.catalog.internal.NvidiaEmbedder
import ai.searchavo.platform.llm.LlmPolicies
import ai.searchavo.platform.resilience.Resilience
import ai.searchavo.testing.TestHttp
import io.micrometer.observation.ObservationRegistry
import java.time.Duration
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

@Tag("eval-record")
class GuideSimilarRecorder {

  @Test
  fun `records one query embedding per question of the set`() {
    val key =
      requireNotNull(System.getenv("SA_NVIDIA_API_KEY")?.ifBlank { null }) {
        "SA_NVIDIA_API_KEY is not set"
      }
    val base =
      System.getenv("SA_NVIDIA_EMBEDDING_BASE_URL") ?: "https://integrate.api.nvidia.com/v1"
    val model = System.getenv("SA_NVIDIA_EMBEDDING_MODEL") ?: "nvidia/nemotron-3.5-embed-1b"
    val embedder =
      NvidiaEmbedder(
        base,
        key,
        model,
        DIMENSIONS,
        Duration.ofSeconds(60),
        TestHttp.SETTINGS,
        ObservationRegistry.NOOP,
        Resilience.NONE,
        LlmPolicies.EMBED,
      )
    val questions = GuideSimilarEvaluation.questions(GuideSimilarEvaluation.set())
    val vectors =
      questions
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
    const val DIMENSIONS = 1024
  }
}
