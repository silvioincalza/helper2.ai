package ai.helper2.guide

import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

class IntentRulesLanguagesTest {

  @ParameterizedTest(name = "{0}: {1}")
  @CsvSource(
    delimiter = '|',
    value =
      [
        "en | the latest image models",
        "it | ultimi usciti tra i modelli",
        "de | die neuesten Modelle",
        "fr | les modèles les plus récents",
        "es | los modelos más recientes",
      ],
  )
  fun `a question for the newest asks for the newest`(lang: String, question: String) {
    assertEquals(GuideIntent.NEWEST, IntentRules.intentOf(question), lang)
  }

  @ParameterizedTest(name = "{0}: {1}")
  @CsvSource(
    delimiter = '|',
    value =
      [
        "en | any new open-source models",
        "it | nuovi modelli open source",
        "de | neue Modelle für Text",
        "fr | nouveaux modèles de traduction",
        "es | nuevas herramientas de diseño",
      ],
  )
  fun `new right before a kind of resource asks for the newest`(lang: String, question: String) {
    assertEquals(GuideIntent.NEWEST, IntentRules.intentOf(question), lang)
  }

  @ParameterizedTest(name = "{0}: {1}")
  @CsvSource(
    delimiter = '|',
    value =
      [
        "en | alternatives to Cursor",
        "it | alternative a Cursor",
        "de | Alternativen zu Cursor",
        "fr | alternatives à Cursor",
        "es | alternativas a Cursor",
      ],
  )
  fun `a question for alternatives asks for alternatives`(lang: String, question: String) {
    assertEquals(GuideIntent.ALTERNATIVES, IntentRules.intentOf(question), lang)
  }

  @ParameterizedTest(name = "{0}: {1}")
  @CsvSource(
    delimiter = '|',
    value =
      [
        "en | Ollama vs LM Studio",
        "it | confronta Ollama e LM Studio",
        "de | Vergleich Ollama und LM Studio",
        "fr | comparer Ollama et LM Studio",
        "es | comparar Ollama y LM Studio",
      ],
  )
  fun `a comparison asks to compare`(lang: String, question: String) {
    assertEquals(GuideIntent.COMPARE, IntentRules.intentOf(question), lang)
  }

  @ParameterizedTest(name = "{0}: {1}")
  @CsvSource(
    delimiter = '|',
    value =
      [
        "en | what is LangGraph",
        "it | cos'è LangGraph",
        "de | was ist LangGraph",
        "fr | qu'est-ce que LangGraph",
        "es | qué es LangGraph",
      ],
  )
  fun `a question that starts with what is asks what it is`(lang: String, question: String) {
    assertEquals(GuideIntent.WHAT_IS, IntentRules.intentOf(question), lang)
  }

  @ParameterizedTest(name = "{0}: {1}")
  @CsvSource(
    delimiter = '|',
    value =
      [
        "en | when is NeurIPS",
        "it | quando è NeurIPS",
        "de | wann ist NeurIPS",
        "fr | quand est NeurIPS",
        "es | cuándo es NeurIPS",
      ],
  )
  fun `a question that starts with when asks when`(lang: String, question: String) {
    assertEquals(GuideIntent.WHEN, IntentRules.intentOf(question), lang)
  }

  @ParameterizedTest(name = "{0}: {1}")
  @CsvSource(
    delimiter = '|',
    value =
      [
        "en | how to run Llama on a laptop",
        "it | come posso usare Llama",
        "de | wie kann ich Llama nutzen",
        "fr | comment faire tourner Llama",
        "es | cómo usar Llama",
      ],
  )
  fun `a question that starts with how asks how to`(lang: String, question: String) {
    assertEquals(GuideIntent.HOW_TO, IntentRules.intentOf(question), lang)
  }

  @ParameterizedTest(name = "{0}: {1}")
  @CsvSource(
    delimiter = '|',
    value =
      [
        "en | a course on RAG",
        "it | un corso su RAG",
        "de | ein Kurs über RAG",
        "fr | une formation sur RAG",
        "es | un curso de RAG",
      ],
  )
  fun `a question for a course asks to learn`(lang: String, question: String) {
    assertEquals(GuideIntent.LEARN, IntentRules.intentOf(question), lang)
  }

  @ParameterizedTest(name = "{0}: {1}")
  @CsvSource(
    delimiter = '|',
    value =
      [
        "en | models from the last 7 days",
        "it | modelli degli ultimi 7 giorni",
        "de | Modelle der letzten 7 Tage",
        "fr | les modèles des 7 derniers jours",
        "es | modelos de los últimos 7 días",
      ],
  )
  fun `a past period with a number is that many units back`(lang: String, question: String) {
    assertEquals(PlannedWindow(7, WindowUnit.DAY), IntentRules.windowOf(question), lang)
  }

  @ParameterizedTest(name = "{0}: {1}")
  @CsvSource(
    delimiter = '|',
    value =
      [
        "en | tools from last week",
        "it | strumenti della settimana scorsa",
        "de | Werkzeuge der letzten Woche",
        "fr | les outils de la semaine dernière",
        "es | herramientas de la semana pasada",
      ],
  )
  fun `a past period without a number is one unit back`(lang: String, question: String) {
    assertEquals(PlannedWindow(1, WindowUnit.WEEK), IntentRules.windowOf(question), lang)
  }

  @ParameterizedTest(name = "{0}: {1}")
  @CsvSource(
    delimiter = '|',
    value =
      [
        "en | events in the next 2 weeks",
        "it | eventi nelle prossime 2 settimane",
        "de | Events in den nächsten 2 Wochen",
        "fr | les événements des 2 prochaines semaines",
        "es | eventos de las próximas 2 semanas",
      ],
  )
  fun `a next period looks ahead`(lang: String, question: String) {
    assertEquals(
      PlannedWindow(2, WindowUnit.WEEK, WindowDirection.NEXT),
      IntentRules.windowOf(question),
      lang,
    )
  }

  @ParameterizedTest(name = "{0}: {1}")
  @CsvSource(
    delimiter = '|',
    value =
      [
        "en | models released this month",
        "it | modelli usciti questo mese",
        "de | Modelle von diesem Monat",
        "fr | les modèles de ce mois",
        "es | modelos de este mes",
      ],
  )
  fun `this period is the current unit`(lang: String, question: String) {
    assertEquals(PlannedWindow(1, WindowUnit.MONTH), IntentRules.windowOf(question), lang)
  }

  @ParameterizedTest(name = "{0}: {1}")
  @CsvSource(
    delimiter = '|',
    value =
      [
        "en | AI news today",
        "it | notizie AI di oggi",
        "de | KI Nachrichten heute",
        "fr | les actualités IA d'aujourd'hui",
        "es | noticias de IA de hoy",
      ],
  )
  fun `today is one day`(lang: String, question: String) {
    assertEquals(PlannedWindow(1, WindowUnit.DAY), IntentRules.windowOf(question), lang)
  }

  @ParameterizedTest(name = "{0}: {1}")
  @CsvSource(
    delimiter = '|',
    value =
      [
        "en | models released yesterday",
        "it | modelli usciti ieri",
        "de | Modelle seit gestern",
        "fr | modèles sortis hier",
        "es | modelos publicados ayer",
      ],
  )
  fun `a question that names a time mentions time`(lang: String, question: String) {
    assertTrue(IntentRules.mentionsTime(question), lang)
  }

  @ParameterizedTest(name = "{0}: {1}")
  @CsvSource(
    delimiter = '|',
    value =
      [
        "en | recently launched agents",
        "it | modelli recenti",
        "de | kürzlich erschienene Modelle",
        "fr | modèles récents",
        "es | modelos recientes",
      ],
  )
  fun `a question for something new mentions newness`(lang: String, question: String) {
    assertTrue(IntentRules.mentionsNewness(question), lang)
    assertTrue(!IntentRules.mentionsTime(question), lang)
  }

  @ParameterizedTest(name = "{0}: {1}")
  @CsvSource(
    delimiter = '|',
    value = ["fr | sortie structurée pour LLM"],
  )
  fun `a word that also means output does not ask for something new`(
    lang: String,
    question: String,
  ) {
    assertTrue(!IntentRules.mentionsNewness(question), question)
    assertNotEquals(GuideIntent.NEWEST, IntentRules.intentOf(question), question)
  }

  @ParameterizedTest(name = "{0}: {1}")
  @CsvSource(
    delimiter = '|',
    value =
      [
        "en | a free OCR tool",
        "it | un OCR gratuito",
        "de | ein kostenloses OCR",
        "fr | un OCR gratuit",
        "es | un OCR gratis",
      ],
  )
  fun `a free requirement is stated in every language`(lang: String, question: String) {
    assertTrue(IntentRules.constraintStated("free", question), lang)
  }

  @ParameterizedTest(name = "{0}: {1}")
  @CsvSource(
    delimiter = '|',
    value =
      [
        "en | speech to text that works without internet",
        "it | trascrizione in locale",
        "de | Spracherkennung ohne Internet",
        "fr | transcription hors ligne",
        "es | transcripción sin conexión",
      ],
  )
  fun `an offline requirement is stated in every language`(lang: String, question: String) {
    assertTrue(IntentRules.constraintStated("works offline", question), lang)
  }

  @ParameterizedTest(name = "{0}: {1}")
  @CsvSource(
    delimiter = '|',
    value =
      [
        "en | hackathons in Berlin",
        "it | conferenze a Milano",
        "de | Konferenzen in Berlin",
        "fr | conférences à Paris",
        "es | conferencias en Madrid",
      ],
  )
  fun `a word for an event names an event`(lang: String, question: String) {
    assertTrue(IntentRules.namesEvent(question), lang)
  }

  @ParameterizedTest(name = "{0}: {1}")
  @CsvSource(
    delimiter = '|',
    value =
      [
        "en | the Gemini model",
        "it | il modello Gemini",
        "de | das Gemini Modell",
        "fr | le modèle Gemini",
        "es | el modelo Gemini",
      ],
  )
  fun `a word for a kind names the subject's kind`(lang: String, question: String) {
    assertTrue(IntentRules.namesKind(question), lang)
  }

  @ParameterizedTest(name = "{0}: {1}")
  @CsvSource(
    delimiter = '|',
    value =
      [
        "en | the best new AI tools for Ollama | Ollama",
        "it | i migliori strumenti per Ollama | per Ollama",
        "de | die besten neuen KI Werkzeuge für Ollama | Ollama",
        "fr | les meilleurs outils pour Ollama | Ollama",
        "es | las mejores herramientas para Ollama | Ollama",
      ],
  )
  fun `generic words name no subject`(lang: String, question: String, subject: String) {
    assertEquals(subject, IntentRules.subjectWords(question), lang)
  }

  @ParameterizedTest(name = "{0}: {1}")
  @CsvSource(
    delimiter = '|',
    value =
      [
        "en | the latest new AI tools",
        "it | i nuovi strumenti",
        "de | die neuesten KI Werkzeuge",
        "fr | les nouveaux outils",
        "es | las nuevas herramientas",
      ],
  )
  fun `a question of only generic words names no subject`(lang: String, question: String) {
    assertEquals("", IntentRules.subjectWords(question), lang)
  }

  @ParameterizedTest(name = "{0}: {1}")
  @CsvSource(
    delimiter = '|',
    value =
      [
        "en | free open source OCR",
        "it | OCR gratuito",
        "de | OCR kostenlos",
        "fr | OCR gratuit",
        "es | OCR gratuito",
      ],
  )
  fun `a requirement is dropped from a planned query`(lang: String, query: String) {
    assertEquals("OCR", withoutQualifiers(query), lang)
  }

  @ParameterizedTest(name = "{0}: {1}")
  @CsvSource(
    delimiter = '|',
    value =
      [
        "en | papers on RAG",
        "it | articoli su RAG",
        "de | Artikel über RAG",
        "fr | articles sur RAG",
        "es | artículos sobre RAG",
      ],
  )
  fun `a question for things to read wants reading`(lang: String, question: String) {
    assertTrue(wantsReading(question, null), lang)
  }
}
