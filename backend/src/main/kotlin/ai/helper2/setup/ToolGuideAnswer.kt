package ai.helper2.setup

import java.time.Instant

/** Sections of a tool guide, in the order they are shown. */
enum class GuideSectionKind { REQUIREMENTS, INSTALL, CONFIGURE, USE, CONNECT }

/** The model's proposal: everything in it is checked by [ToolGuideValidator] before anyone sees it. */
data class ProposedStep(val text: String, val materialKey: String, val quote: String, val code: String? = null, val language: String? = null)

data class ProposedSection(val kind: String, val steps: List<ProposedStep>)

data class ProposedGuide(val sections: List<ProposedSection>)

data class Code(val language: String?, val text: String)

/**
 * One step the user can follow. [quote] is a passage of the material at [materialKey]; [code], when present, is
 * whole lines of one fenced block of that material (or one inline code span), as written there. [caution] marks
 * a command that hands text to a shell or interpreter (a pipe, `sh -c "$(curl …)"`, `bash <(curl …)`, `iex`),
 * uses sudo, opens permissions to everyone or deletes recursively: copied exactly from the document, not reviewed
 * for safety.
 */
data class GuideStep(val text: String, val materialKey: String, val quote: String, val code: Code?, val caution: Boolean)

data class GuideSection(val kind: GuideSectionKind, val steps: List<GuideStep>)

enum class ToolGuideStatus {
    /** At least one step passed the checks. */
    OK,
    /** The documents were read, but no proposed step passed the checks. */
    NO_STEPS,
    /** The resource has no document we can read. */
    NO_MATERIAL,
    /** No model configured, it failed, or today's model calls are used up. */
    LLM_UNAVAILABLE,
}

data class ToolGuideAnswer(
    val status: ToolGuideStatus,
    val sections: List<GuideSection>,
    /** The documents the steps were read from, without their text; steps refer to them by key. */
    val materials: List<MaterialRef>,
    val model: String?,
    val rejected: Int,
    val generatedAt: Instant,
    /** Why steps were removed, by reason; for logs and the eval. */
    val rejectedBy: Map<String, Int> = emptyMap(),
    /** Why the model was not used, when status is LLM_UNAVAILABLE: "budget" or "error". */
    val unavailable: String? = null,
)

/** The model could not answer now, and why. */
class ToolGuideUnavailable(val reason: Reason) : RuntimeException(reason.name) {

    enum class Reason(
        /** What [ToolGuideAnswer.unavailable] tells the user: "budget" or "error". */
        val shown: String,
    ) {
        /** Today's model calls are used up. */
        BUDGET("budget"),
        /** No connection, a timeout, a 429, a 5xx, a rejected call or the open `llm-chat` circuit. */
        PROVIDER("error"),
        /** The provider answered twice, and neither answer was the requested JSON. */
        UNREADABLE("error"),
    }
}
