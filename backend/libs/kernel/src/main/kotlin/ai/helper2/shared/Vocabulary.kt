package ai.helper2.shared

enum class ResourceType {
  PRODUCT,
  MODEL,
  AGENT,
  PROMPT,
  SKILL,
  MCP_SERVER,
  LIBRARY,
  DATASET,
  ARTICLE,
  PAPER,
  NEWS,
  EVENT,
  COURSE,
  OTHER;

  val specificity: Int
    get() =
      when (this) {
        MCP_SERVER -> 0
        SKILL -> 1
        AGENT -> 2
        PROMPT -> 3
        MODEL -> 4
        DATASET -> 5
        PRODUCT -> 6
        LIBRARY -> 7
        COURSE -> 8
        EVENT -> 9
        PAPER -> 10
        NEWS -> 11
        ARTICLE -> 12
        OTHER -> 13
      }
}

enum class Audience {
  USE_AI,
  BUILD_WITH_AI,
  BOTH,
  UNKNOWN;

  fun combine(other: Audience): Audience =
    when {
      this == other -> this
      this == UNKNOWN -> other
      other == UNKNOWN -> this
      else -> BOTH
    }

  fun matches(path: AudiencePath): Boolean =
    when (this) {
      BOTH -> true
      UNKNOWN -> false
      USE_AI -> path == AudiencePath.USE_AI
      BUILD_WITH_AI -> path == AudiencePath.BUILD_WITH_AI
    }
}

enum class AudiencePath {
  USE_AI,
  BUILD_WITH_AI,
}

enum class TechLevel {
  BEGINNER,
  INTERMEDIATE,
  ADVANCED,
}

enum class Pricing {
  FREE,
  FREEMIUM,
  PAID,
  OPEN_SOURCE_SELF_HOSTED,
}

enum class EventKind {
  CONFERENCE,
  HACKATHON,
  MEETUP,
  TALK,
  WORKSHOP,
}

enum class EventFormat {
  IN_PERSON,
  ONLINE,
  HYBRID,
}

enum class SourceMode {
  LIVE,
  INDEXED,
  CURATED,
  DEMO,
}

enum class ValueOrigin(val isAiWritten: Boolean) {
  SOURCE_PROVIDED(isAiWritten = false),
  AI_EXTRACTED(isAiWritten = true),
  AI_SUMMARY(isAiWritten = true),
  AI_ESTIMATED(isAiWritten = true),
  SELF_DECLARED(isAiWritten = false),
  CURATED(isAiWritten = false),
  COMMUNITY(isAiWritten = false),
  DEMO(isAiWritten = false),
}

enum class Verification {
  VERIFIED,
  UNVERIFIED,
  CONFLICTING,
  REJECTED,
}

data class SourcedValue(val value: String, val sourceId: String)
