package ai.helper2.setup

import java.time.Instant

enum class GuideSectionKind {
  REQUIREMENTS,
  INSTALL,
  CONFIGURE,
  USE,
  CONNECT,
}

data class ProposedStep(
  val text: String,
  val materialKey: String,
  val quote: String,
  val code: String? = null,
  val language: String? = null,
)

data class ProposedSection(val kind: String, val steps: List<ProposedStep>)

data class ProposedGuide(val sections: List<ProposedSection>)

data class Code(val language: String?, val text: String)

data class GuideStep(
  val text: String?,
  val materialKey: String,
  val quote: String,
  val code: Code?,
  val caution: Boolean,
)

data class StepClaim(val key: String, val text: String, val quote: String, val code: String?)

data class GuideSection(val kind: GuideSectionKind, val steps: List<GuideStep>)

enum class ToolGuideStatus {
  OK,
  NO_STEPS,
  NO_MATERIAL,
  LLM_UNAVAILABLE,
}

data class ToolGuideAnswer(
  val status: ToolGuideStatus,
  val sections: List<GuideSection>,
  val materials: List<MaterialRef>,
  val model: String?,
  val rejected: Int,
  val generatedAt: Instant,
  val rejectedBy: Map<String, Int> = emptyMap(),
  val unavailable: String? = null,
  val retryAt: Instant? = null,
)

class ToolGuideUnavailable(val reason: Reason) : RuntimeException(reason.name) {

  enum class Reason(val shown: String) {
    BUDGET("budget"),
    PROVIDER("error"),
    UNREADABLE("error"),
  }
}
