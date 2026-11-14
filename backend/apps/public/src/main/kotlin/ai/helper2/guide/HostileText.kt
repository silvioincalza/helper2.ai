package ai.helper2.guide

object QuestionDirections {
  private val SENTENCE = Regex("[^.;!?\\n]+")

  private const val EACH = "(?:the|every|each|your|all)"

  private val WHAT_TO_WRITE =
    Regex(
      "(?i)\\b(?:in|to|into)\\s+$EACH\\s+reasons?\\b|" +
        "\\b(?:say|write|add|mention|state|include|put|note|append)\\b.*\\b(?:in|to|into)\\s+$EACH\\s+" +
        "(?:answers?|repl(?:y|ies)|responses?|explanations?|descriptions?)\\b|" +
        "\\b(?:nella|nelle)\\s+motivazion[ei]\\b|\\bnel\\s+motivo\\b"
    )

  private val WHAT_THE_USER_DOES =
    Regex(
      "(?i)\\b(?:tell|remind|warn|instruct|advise|inform)\\s+(?:the|every|each)\\s+users?\\b|" +
        "\\b(?:the|every|each)\\s+users?\\s+(?:must|should|shall|ha(?:s|ve)\\s+to|needs?\\s+to|needs?\\s+not|" +
        "(?:does|do)\\s+not\\s+(?:need|have)|(?:doesn't|don't)\\s+(?:need|have)|(?:is|are)\\s+required)\\b|" +
        "\\busers\\s+(?:must|should|have\\s+to|need\\s+to)\\b|" +
        "\\bl['’]utente\\s+(?:non\\s+)?(?:deve|dovrebbe)\\b|\\bgli\\s+utenti\\s+(?:non\\s+)?(?:devono|dovrebbero)\\b"
    )

  private val NOT_A_PROPERTY =
    Regex(
      "(?i)\\b(?:reasons?|prompts?|instructions?|says?|tells?|writes?|mentions?|passwords?|passphrases?|" +
        "the\\s+users?|users?\\s+(?:must|should|needs?|ha(?:s|ve)))\\b"
    )

  @JvmStatic
  fun needOf(question: String): String {
    val sentences = SENTENCE.findAll(question).map { it.value }.toList()
    val asked = sentences.filterNot(::isDirection)
    if (asked.size == sentences.size) return question
    return asked.map { it.trim() }.filter { it.isNotEmpty() }.joinToString(" ")
  }

  @JvmStatic
  fun isResourceProperty(constraint: String): Boolean = !NOT_A_PROPERTY.containsMatchIn(constraint)

  private fun isDirection(sentence: String) =
    WHAT_TO_WRITE.containsMatchIn(sentence) || WHAT_THE_USER_DOES.containsMatchIn(sentence)
}

object SecretWords {
  private const val START = "(?i)(?<![\\p{L}])(?:"
  private const val END = ")(?![\\p{L}])"

  private val KINDS =
    listOf(
        "pass(?:word|phrase|code)s?",
        "(?:api|secret|private|access|licen[cs]e)[-_ ]?keys?|chiav[ei]\\s+(?:api|segret[ae]|privat[ae])",
        "(?:access|auth|authentication|bearer|api|secret|session|security)[-_ ]?tokens?",
        "(?:credit|debit|payment)[- ]?cards?|card\\s+numbers?|cvv|iban|cart[ae]\\s+di\\s+(?:credito|debito)",
        "log[- ]?ins?|sign(?:ing|ed)?[- ]?in|credentials?|usernames?|credenzial[ei]",
      )
      .map { Regex(START + it + END) }

  @JvmStatic
  fun onlyInReason(reason: String, evidence: String): Boolean = KINDS.any {
    it.containsMatchIn(reason) && !it.containsMatchIn(evidence)
  }
}
