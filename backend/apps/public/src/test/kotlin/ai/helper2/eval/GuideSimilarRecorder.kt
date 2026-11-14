package ai.helper2.eval

import ai.helper2.catalog.Embedder
import ai.helper2.catalog.internal.NvidiaEmbedder
import ai.helper2.platform.llm.LlmPolicies
import ai.helper2.platform.resilience.Resilience
import ai.helper2.testing.TestHttp
import io.micrometer.observation.ObservationRegistry
import java.time.Duration
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

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
