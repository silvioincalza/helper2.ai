package ai.helper2.sources.support

import ai.helper2.shared.ResourceType
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

class ClassificationTest {

  @ParameterizedTest
  @CsvSource(
    delimiter = '|',
    value =
      [
        "paper|The Swiss Army Knife of Vector Graphics Scripting|vector,graphic",
        "agent-base|Turn a function into an http.Agent instance|agent,http",
        "global-agent|Global HTTP/HTTPS proxy agent configurable using environment variables|agent,proxy",
        "@expo/vector-icons|Built-in icon library for Expo|icons,vector",
        "prompts|Lightweight, beautiful and user-friendly prompts|prompt,prompts,cli,tty",
      ],
  )
  fun `one weak word, or one weak word twice, does not make a resource about AI`(
    title: String,
    description: String,
    labels: String,
  ) {
    assertFalse(Classification.isAiRelated(title, description, labels.split(',')))
  }

  @ParameterizedTest
  @CsvSource(
    delimiter = '|',
    value =
      [
        "ollama|Ollama Javascript library|",
        "acme/rag-agent|An agent that answers from your documents with RAG|",
        "@huggingface/transformers|State-of-the-art Machine Learning for the web|",
        "crewAIInc/crewAI-examples|Examples of the CrewAI framework|",
        "acme/assistente|Un assistente basato su intelligenza artificiale|",
        "acme/ki-helfer|Ein Werkzeug mit künstlicher Intelligenz|",
        "llama3-tools|Tools for llama3|",
      ],
  )
  fun `a strong word, or two different weak words, makes a resource about AI`(
    title: String,
    description: String,
    labels: String?,
  ) {
    assertTrue(
      Classification.isAiRelated(title, description, labels?.split(',').orEmpty()),
      "$title should be about AI",
    )
  }

  @Test
  fun `the word prompt alone does not type a resource PROMPT`() {
    assertEquals(
      ResourceType.LIBRARY,
      Classification.typeOf(
        "mongodb-connection-string-url",
        "MongoDB connection strings",
        listOf("password", "prompt", "tty"),
        ResourceType.LIBRARY,
      ),
    )
    assertEquals(
      ResourceType.PROMPT,
      Classification.typeOf(
        "awesome-llm-prompts",
        "Prompts for LLM agents",
        listOf("prompt", "llm"),
        ResourceType.LIBRARY,
      ),
    )
  }
}
