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
        "zh | 翻译文档 | translation",
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
        "zh | 图像模型",
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
        "zh | 音频数据集 | 音频",
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
        "en | find a tool for notes of meetings | notes meetings",
        "it | trovare uno strumento per note di riunioni | strumento note riunioni",
        "de | finden ein Werkzeug für Notizen von Meetings | notizen meetings",
        "fr | trouver un outil pour notes des réunions | notes réunions",
        "es | encontrar una herramienta para notas de reuniones | notas reuniones",
        "zh | 工具的会议笔记 | 会议 笔记",
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
        "en | ai courses",
        "it | corsi di ai",
        "de | Kurse zu KI",
        "fr | cours d'IA",
        "es | cursos de IA",
        "zh | 人工智能课程",
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
        "zh | 编程助手聊天",
      ],
  )
  fun `two use case words in a text derive its use cases`(lang: String, text: String) {
    assertTrue("chat" in analyzer.useCasesOf(text), lang)
  }

  @ParameterizedTest(name = "{0}")
  @CsvSource(value = ["新加坡的数据集 | 新加坡", "有道翻译 | 有道", "用户手册 | 用户手册"], delimiter = '|')
  fun `a Chinese name is not cut at a single character`(query: String, name: String) {
    assertTrue(name in analyzer.analyze(query).contentTerms, query)
  }

  @ParameterizedTest(name = "{0}")
  @CsvSource(value = ["向量数据库", "代码库搜索", "知识库问答"])
  fun `a Chinese word that holds a shorter known word is read whole`(query: String) {
    val analyzed = analyzer.analyze(query)
    assertEquals(emptySet(), analyzed.typeHints, query)
    assertTrue("data-analysis" !in analyzed.useCases, query)
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
