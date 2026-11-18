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
  AWESOME_LIST,
  OTHER;

  val specificity: Int
    get() =
      when (this) {
        AWESOME_LIST -> 0
        MCP_SERVER -> 1
        SKILL -> 2
        AGENT -> 3
        PROMPT -> 4
        MODEL -> 5
        DATASET -> 6
        PRODUCT -> 7
        LIBRARY -> 8
        COURSE -> 9
        EVENT -> 10
        PAPER -> 11
        NEWS -> 12
        ARTICLE -> 13
        OTHER -> 14
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
  DEMO(isAiWritten = false);

  val isFromAModel: Boolean
    get() = isAiWritten || this == SELF_DECLARED
}

enum class Verification {
  VERIFIED,
  UNVERIFIED,
  CONFLICTING,
  REJECTED,
}

data class SourcedValue(val value: String, val sourceId: String)
