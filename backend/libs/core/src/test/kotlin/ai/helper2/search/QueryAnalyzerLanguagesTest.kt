package ai.helper2.search

import ai.helper2.shared.ResourceType
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

class QueryAnalyzerLanguagesTest {

  private val analyzer = QueryAnalyzer()

  @ParameterizedTest(name = "{0}: {1}")
  @CsvSource(
    delimiter = '|',
    value =
      [
        "en | translate documents | ",
        "it | tradurre documenti | translation",
        "de | Dokumente übersetzen | translation",
        "fr | traduire des documents | translation",
        "es | traducir documentos | translation",
      ],
  )
  fun `a use case word names its use case and a non-English one adds the English term`(
    lang: String,
    query: String,
    expanded: String?,
  ) {
    val analyzed = analyzer.analyze(query)
    assertEquals(setOf("translation"), analyzed.useCases, lang)
    assertEquals(listOfNotNull(expanded), analyzed.expandedTerms, lang)
  }

  @ParameterizedTest(name = "{0}: {1}")
  @CsvSource(
    delimiter = '|',
    value =
      [
        "en | image model",
        "it | modello per immagini",
        "de | Modell für Bilder",
        "fr | modèle d'images",
        "es | modelo de voz",
      ],
  )
  fun `a word for a kind of resource hints the type`(lang: String, query: String) {
    assertEquals(setOf(ResourceType.MODEL), analyzer.analyze(query).typeHints, lang)
  }

  @ParameterizedTest(name = "{0}: {1}")
  @CsvSource(
    delimiter = '|',
    value =
      [
        "en | audio dataset | audio",
        "it | dataset audio | audio",
        "de | Datensatz für Audio | audio",
        "fr | jeu de données audio | audio",
        "es | conjunto de datos de audio | audio",
      ],
  )
  fun `a word or phrase for a dataset hints the type and is not required`(
    lang: String,
    query: String,
    required: String,
  ) {
    val analyzed = analyzer.analyze(query)
    assertEquals(setOf(ResourceType.DATASET), analyzed.typeHints, lang)
    assertTrue(analyzed.requiredVariants.none { "données" in it || "datos" in it }, lang)
    assertTrue(required in analyzed.contentTerms, lang)
    assertTrue("data-analysis" !in analyzed.useCases, lang)
    assertTrue(analyzed.expandedTerms.none { "data" in it }, lang)
  }

  @ParameterizedTest(name = "{0}: {1}")
  @CsvSource(
    delimiter = '|',
    value =
      [
        "en | awesome list for rag | rag",
        "en | rag awesome-list | rag",
        "it | lista awesome per rag | rag",
        "de | Awesome-Liste für RAG | rag",
        "fr | liste awesome pour rag | rag",
        "es | lista awesome de rag | rag",
      ],
  )
  fun `a phrase for an awesome list hints the type and is not content`(
    lang: String,
    query: String,
    content: String,
  ) {
    val analyzed = analyzer.analyze(query)
    assertEquals(setOf(ResourceType.AWESOME_LIST), analyzed.typeHints, lang)
    assertEquals(listOf(content), analyzed.contentTerms, lang)
  }

  @ParameterizedTest(name = "{0}: {1}")
  @CsvSource(
    delimiter = '|',
    value =
      [
        "en | find a tool for notes of meetings | notes meetings",
        "it | trovare uno strumento per note di riunioni | strumento note riunioni",
        "de | finden ein Werkzeug für Notizen von Meetings | notizen meetings",
        "fr | trouver un outil pour notes des réunions | notes réunions",
        "es | encontrar una herramienta para notas de reuniones | notas reuniones",
      ],
  )
  fun `filler and request words are not content`(lang: String, query: String, content: String) {
    assertEquals(content.split(' '), analyzer.analyze(query).contentTerms, lang)
  }

  @ParameterizedTest(name = "{0}: {1}")
  @CsvSource(
    delimiter = '|',
    value =
      [
        "fr | jeu de données climat suisse | données",
        "es | conjunto de datos clima suiza | datos",
      ],
  )
  fun `a type phrase reaches the sources as its own words`(
    lang: String,
    query: String,
    word: String,
  ) {
    val terms = analyzer.analyze(query).contentTerms
    assertTrue(word in terms, lang)
    assertTrue(terms.none { '-' in it }, lang)
  }

  @ParameterizedTest(name = "{0}: {1}")
  @CsvSource(
    delimiter = '|',
    value =
      [
        "en | ai courses",
        "it | corsi di ai",
        "de | Kurse zu KI",
        "fr | cours d'IA",
        "es | cursos de IA",
      ],
  )
  fun `a word for news, courses or events makes the search time-bound`(
    lang: String,
    query: String,
  ) {
    assertEquals(
      IntentClass.TIME_BOUND,
      IntentClass.of(SearchRequest(query), analyzer.analyze(query)),
      lang,
    )
  }

  @ParameterizedTest(name = "{0}: {1}")
  @CsvSource(
    delimiter = '|',
    value =
      [
        "en | coding assistant chatbot",
        "it | assistente di programmazione e conversazione",
        "de | Assistent zum Programmieren im Gespräch",
        "fr | assistant de programmation et conversation",
        "es | asistente de programación y conversación",
      ],
  )
  fun `two use case words in a text derive its use cases`(lang: String, text: String) {
    assertTrue("chat" in analyzer.useCasesOf(text), lang)
  }

  @ParameterizedTest(name = "{0}")
  @CsvSource(
    delimiter = '|',
    value = ["postgres mcp | postgresql mcp", "k8s operator | kubernetes operator"],
  )
  fun `a name of one thing also searches its other names`(query: String, variant: String) {
    assertTrue(variant in analyzer.analyze(query).requiredVariants, query)
  }
}
