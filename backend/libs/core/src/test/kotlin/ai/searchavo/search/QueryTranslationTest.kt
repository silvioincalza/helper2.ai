package ai.searchavo.search

import ai.searchavo.search.usecase.SearchResources
import java.time.Clock
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class QueryTranslationTest {

  private val executor = Executors.newVirtualThreadPerTaskExecutor()
  private val languages = LanguageDetector()

  @AfterTest fun shutdown() = executor.shutdownNow().let {}

  private val noCache =
    object : SearchCache {
      override fun get(key: String): CachedHits? = null

      override fun put(key: String, value: CachedHits, ttl: Duration) {}
    }

  private fun run(
    text: String,
    timeout: Duration = Duration.ofSeconds(2),
    translator: QueryTranslator,
  ): Pair<SourceQuery, SearchOutcome> {
    var seen: SourceQuery? = null
    val src =
      object : SearchSource {
        override val descriptor = descriptor("catalog")

        override fun search(query: SourceQuery): List<SourceHit> =
          emptyList<SourceHit>().also { seen = query }
      }
    val out =
      SearchResources(
          listOf(src),
          noCache,
          { emptyMap() },
          executor,
          Clock.systemUTC(),
          languages = languages,
          translator = translator,
          translateTimeout = timeout,
        )
        .search(SearchRequest(text))
    return seen!! to out
  }

  @Test
  fun `a Spanish query is searched in English first, and the notice keeps to our own words`() {
    val (q, out) =
      run("herramienta para traducir documentos") { _, lang ->
        assertEquals("es", lang)
        "Document translation tool"
      }
    assertEquals("es", q.language)
    assertEquals("document translation tool", q.requiredVariants.first())
    assertTrue("document translation tool" in q.expandedTerms)
    assertEquals("document translation tool", out.interpretation.translation)
    assertTrue(out.notices.none { "document translation tool" in it.message })
  }

  @Test
  fun `the translation is its own field, apart from the lexicon terms (AI-written, ADR 0033)`() {
    val (q, out) =
      run("uno strumento per tradurre i documenti") { _, _ -> "Document translation app" }
    assertEquals("it", q.language)
    assertEquals("document translation app", out.interpretation.translation)
    assertEquals("document translation app", out.interpretation.expandedTerms.first())
    assertEquals(listOf("translation"), out.interpretation.lexiconTerms)
  }

  @Test
  fun `without a translation every expanded term is from the lexicon`() {
    val (_, out) = run("uno strumento per tradurre i documenti", translator = QueryTranslator.NONE)
    assertNull(out.interpretation.translation)
    assertEquals(out.interpretation.expandedTerms, out.interpretation.lexiconTerms)
  }

  @Test
  fun `a long translation is cut to the word limit the prompt states`() {
    val long = (1..20).joinToString(" ") { "word$it" }
    val (q, _) = run("herramienta para traducir documentos") { _, _ -> long }
    assertEquals(
      (1..QueryTranslator.MAX_WORDS).joinToString(" ") { "word$it" },
      q.requiredVariants.first(),
    )
  }

  @Test
  fun `a long translation is cut on a code point, never inside a character`() {
    val long = "a" + "\uD835\uDD38".repeat(200)
    val (q, _) = run("herramienta para traducir documentos") { _, _ -> long }
    val cut = q.requiredVariants.first()
    assertEquals(QueryTranslator.MAX_CHARS, cut.codePointCount(0, cut.length))
    assertFalse(Character.isHighSurrogate(cut.last()))
  }

  @Test
  fun `translation time is not taken from a source's own timeout or latency`() {
    val seen = AtomicReference<SourceQuery>()
    val fast =
      object : SearchSource {
        override val descriptor = descriptor("github", timeout = Duration.ofMillis(200))

        override fun search(query: SourceQuery): List<SourceHit> =
          emptyList<SourceHit>().also { seen.set(query) }
      }
    val slowModel = QueryTranslator { _, _ ->
      Thread.sleep(400)
      "document translation tool"
    }
    val out =
      SearchResources(
          listOf(fast),
          noCache,
          { emptyMap() },
          executor,
          Clock.systemUTC(),
          languages = languages,
          translator = slowModel,
          translateTimeout = Duration.ofSeconds(2),
        )
        .search(SearchRequest("herramienta para traducir documentos"))

    val status = out.statuses.single()
    assertEquals(SourceOutcome.SUCCEEDED, status.outcome)
    assertTrue(
      status.latency!! < Duration.ofMillis(200),
      "latency ${status.latency} includes the translation",
    )
    assertEquals("document translation tool", seen.get().requiredVariants.first())
  }

  @Test
  fun `sources that never read the translation run while the model is still translating`() {
    val semanticCalled = CountDownLatch(1)
    val semantic =
      object : SearchSource {
        override val descriptor =
          descriptor("semantic", mode = ai.searchavo.shared.SourceMode.INDEXED)
            .copy(usesTranslation = false)

        override fun search(query: SourceQuery): List<SourceHit> =
          emptyList<SourceHit>().also { semanticCalled.countDown() }
      }
    val seenByIndex = AtomicReference<SourceQuery>()
    val index =
      object : SearchSource {
        override val descriptor = descriptor("index", mode = ai.searchavo.shared.SourceMode.INDEXED)

        override fun search(query: SourceQuery): List<SourceHit> =
          emptyList<SourceHit>().also { seenByIndex.set(query) }
      }
    val model = QueryTranslator { _, _ ->
      if (semanticCalled.await(2, TimeUnit.SECONDS)) "document translation tool" else null
    }
    SearchResources(
        listOf(semantic, index),
        noCache,
        { emptyMap() },
        executor,
        Clock.systemUTC(),
        languages = languages,
        translator = model,
        translateTimeout = Duration.ofSeconds(3),
      )
      .search(SearchRequest("herramienta para traducir documentos"))

    assertEquals("document translation tool", seenByIndex.get().requiredVariants.first())
  }

  @Test
  fun `English queries never call the model`() {
    val calls = AtomicInteger()
    val (q, _) =
      run("tool to translate documents") { _, _ ->
        calls.incrementAndGet()
        "x"
      }
    assertEquals(0, calls.get())
    assertNull(q.language)
  }

  @Test
  fun `a slow model is cut off and the search runs untranslated`() {
    val (q, out) =
      run("herramienta para traducir documentos", Duration.ofMillis(200)) { _, _ ->
        Thread.sleep(5_000)
        "late"
      }
    assertEquals("es", q.language)
    assertTrue(q.requiredVariants.none { "late" in it })
    assertTrue(out.results.isEmpty())
  }

  @Test
  fun `no model or spent budget means untranslated search`() {
    val (q, _) = run("herramienta para firmar contratos", translator = QueryTranslator.NONE)
    assertEquals("es", q.language)
    assertEquals(null, q.translation)
    assertEquals(emptyList(), q.expandedTerms)
  }

  @Test
  fun `a failing model is not an error`() {
    val (q, _) =
      run("herramienta para traducir documentos") { _, _ ->
        throw IllegalStateException("provider down")
      }
    assertEquals("es", q.language)
  }

  @Test
  fun `model output is reduced to plain words and links are refused`() {
    val (q1, _) =
      run("herramienta para traducir documentos") { _, _ -> "\"Translate <b>docs</b>!\"" }
    assertEquals("translate b docs b", q1.requiredVariants.first())
    val (q2, _) =
      run("herramienta para traducir documentos") { _, _ -> "see https://evil.example now" }
    assertTrue(q2.requiredVariants.none { "evil" in it })
  }

  @Test
  fun `an answer that echoes the query, punctuation aside, is no translation`() {
    val (_, out) =
      run("¿herramienta para traducir documentos?") { _, _ ->
        "Herramienta para traducir documentos"
      }
    assertNull(out.interpretation.translation)
  }

  @Test
  fun `an answer about the query instead of a query is refused`() {
    val (q, out) =
      run("herramienta para traducir documentos") { _, _ -> "The query is already in English." }
    assertNull(out.interpretation.translation)
    assertTrue(q.requiredVariants.none { "english" in it })
  }

  @Test
  fun `a translation about words that cannot be translated is kept`() {
    val (q, out) =
      run("parole che non possono essere tradotte") { _, _ -> "Words that cannot be translated" }
    assertEquals("words that cannot be translated", out.interpretation.translation)
    assertEquals("words that cannot be translated", q.requiredVariants.first())
  }

  @Test
  fun `answers that say no translation is needed are refused`() {
    for (answer in
      listOf(
        "No translation required.",
        "\"The query is in English.\"",
        "This cannot be translated.",
        "It is already in English",
        "The query cannot be translated.",
        "Sorry, this cannot be translated.",
        "I cannot translate this.",
        "I'm unable to translate this query",
        "Unable to translate.",
      )) {
      val (q, out) = run("herramienta para traducir documentos") { _, _ -> answer }
      assertNull(out.interpretation.translation, answer)
      assertNull(q.translation, answer)
    }
  }

  private fun assertRefused(text: String, language: String, answers: List<String>) {
    for (answer in answers) {
      val calls = AtomicInteger()
      val (q, out) =
        run(text) { _, _ ->
          calls.incrementAndGet()
          answer
        }
      assertEquals(language, q.language, answer)
      assertEquals(1, calls.get(), answer)
      assertNull(out.interpretation.translation, answer)
      assertNull(q.translation, answer)
    }
  }

  private fun assertKept(text: String, language: String, answer: String) {
    val (q, out) = run(text) { _, _ -> answer }
    assertEquals(language, q.language)
    assertEquals(answer.lowercase(), out.interpretation.translation)
  }

  @Test
  fun `an Italian answer that says no translation is needed is refused`() {
    assertRefused(
      "uno strumento per tradurre i documenti",
      "it",
      listOf(
        "Nessuna traduzione necessaria.",
        "La query è già in inglese.",
        "Mi dispiace, non posso tradurre questa query.",
        "Questo testo non ha bisogno di traduzione",
        "L'input è in inglese.",
        "Non può essere tradotta.",
      ),
    )
  }

  @Test
  fun `a translation that quotes Italian words of regret is kept`() {
    assertKept("uno strumento per tradurre i documenti", "it", "How to say mi dispiace in English")
  }

  @Test
  fun `a German answer that says no translation is needed is refused`() {
    assertRefused(
      "Werkzeug zum Übersetzen von Dokumenten",
      "de",
      listOf(
        "Keine Übersetzung nötig.",
        "Die Anfrage ist bereits auf Englisch.",
        "Es tut mir leid, ich kann das nicht übersetzen.",
        "Dieser Text muss nicht übersetzt werden",
        "Leider kann ich das nicht übersetzen.",
      ),
    )
  }

  @Test
  fun `a translation that quotes German words of regret is kept`() {
    assertKept("Werkzeug zum Übersetzen von Dokumenten", "de", "How to say leider in English")
  }

  @Test
  fun `a Spanish answer that says no translation is needed is refused`() {
    assertRefused(
      "herramienta para traducir documentos",
      "es",
      listOf(
        "Lo siento, no puedo traducir.",
        "La consulta ya está en inglés.",
        "Este texto no necesita traducción",
        "No es necesario traducir.",
        "No puedo traducir esta consulta.",
      ),
    )
  }

  @Test
  fun `a translation that quotes Spanish words of regret is kept`() {
    assertKept("herramienta para traducir documentos", "es", "How to say lo siento in English")
  }

  @Test
  fun `a French answer that says no translation is needed is refused`() {
    assertRefused(
      "outil pour traduire des documents",
      "fr",
      listOf(
        "Aucune traduction nécessaire.",
        "La requête est déjà en anglais.",
        "Désolé, je ne peux pas traduire cette requête.",
        "Ce texte n'a pas besoin de traduction",
        "C'est en anglais.",
        "Déjà en anglais",
      ),
    )
  }

  @Test
  fun `a translation that quotes French words of regret is kept`() {
    assertKept("outil pour traduire des documents", "fr", "How to say désolé in English")
  }

  @Test
  fun `a translation that asks to translate is kept`() {
    val (q, _) =
      run("herramienta para traducir documentos") { _, _ -> "Translate documents to English" }
    assertEquals("translate documents to english", q.requiredVariants.first())
  }

  @Test
  fun `keyword sources search the content words of the translation (ADR 0029)`() {
    val (q, _) =
      run("biblioteca para crear agentes de ia") { _, _ -> "Library to create AI agents" }
    assertEquals(listOf("ai", "agents"), q.searchTerms)
    assertEquals("ai agents", q.keywords)
  }

  @Test
  fun `sources get the full translation, function words included`() {
    val (q, _) = run("modelo de texto a voz") { _, _ -> "Text to speech model" }
    assertEquals(listOf("text", "to", "speech", "model"), q.translation!!.lowercase().split(" "))
  }

  @Test
  fun `a query with lexicon terms and no translation keeps text and expansions`() {
    val (q, _) = run("uno strumento per tradurre i documenti", translator = QueryTranslator.NONE)
    assertEquals(emptyList(), q.searchTerms)
    assertTrue(q.keywords.startsWith("uno strumento per tradurre i documenti"))
  }

  @Test
  fun `an English query searches its own content words`() {
    val (q, _) = run("reranker model for retrieval", translator = QueryTranslator.NONE)
    assertEquals(listOf("reranker", "retrieval"), q.searchTerms)
  }
}
