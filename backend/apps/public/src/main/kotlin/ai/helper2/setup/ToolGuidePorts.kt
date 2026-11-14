package ai.helper2.setup

import java.time.Duration

interface ToolGuideModel {
  val model: String

  val configFingerprint: String

  fun write(ref: ToolRef, materials: List<Material>): ProposedGuide

  fun confirm(ref: ToolRef, steps: List<StepClaim>): Set<String>
}

interface ToolGuideCache {
  fun get(key: String): ToolGuideAnswer?

  fun put(key: String, answer: ToolGuideAnswer, ttl: Duration)
}

data class SetupGuidesLeft(val limit: Int, val remaining: Int)

data class ToolGuideTtls(val answer: Duration, val unreadable: Duration)
