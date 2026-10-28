package ai.helper2.shared

/** Shared kernel vocabulary. Pure Kotlin: no Spring, no persistence, no Jackson. */

enum class ResourceType {
    PRODUCT, MODEL, AGENT, PROMPT, SKILL, MCP_SERVER, LIBRARY, DATASET,
    /** Knowledge about AI rather than tools. */
    ARTICLE, PAPER, NEWS, EVENT,
    /**
     * A course or a certification (ADR 0032 §8): neither a tool type nor a reading type. Its platform is a
     * PLATFORM label; credential and format are tags (see R__courses.sql).
     */
    COURSE,
    OTHER;

    /** Lower number = more specific. Used when sources classify the same resource differently. */
    val specificity: Int
        get() = when (this) {
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
    USE_AI, BUILD_WITH_AI, BOTH, UNKNOWN;

    fun combine(other: Audience): Audience = when {
        this == other -> this
        this == UNKNOWN -> other
        other == UNKNOWN -> this
        else -> BOTH
    }

    fun matches(path: AudiencePath): Boolean = when (this) {
        BOTH -> true
        UNKNOWN -> false
        USE_AI -> path == AudiencePath.USE_AI
        BUILD_WITH_AI -> path == AudiencePath.BUILD_WITH_AI
    }
}

enum class AudiencePath { USE_AI, BUILD_WITH_AI }

enum class TechLevel { BEGINNER, INTERMEDIATE, ADVANCED }

enum class Pricing { FREE, FREEMIUM, PAID, OPEN_SOURCE_SELF_HOSTED }

/** EVENT only: what the source calls the event. */
enum class EventKind { CONFERENCE, HACKATHON, MEETUP, TALK, WORKSHOP }

/** EVENT only: where it happens, as the source states it. */
enum class EventFormat { IN_PERSON, ONLINE, HYBRID }

/** How a source's data reaches the user. */
enum class SourceMode { LIVE, INDEXED, CURATED, DEMO }

/** Where a displayed value came from. */
enum class ValueOrigin { SOURCE_PROVIDED, AI_EXTRACTED, AI_SUMMARY, CURATED, COMMUNITY, DEMO }

enum class Verification { VERIFIED, UNVERIFIED, CONFLICTING, REJECTED }

data class SourcedValue(val value: String, val sourceId: String)
