package ai.helper2.setup

internal object StepTexts {

  fun claims(sections: List<GuideSection>): List<StepClaim> =
    sections
      .flatMap { it.steps }
      .mapIndexed { i, step ->
        StepClaim(keyOf(i), step.text.orEmpty(), step.quote, step.code?.text)
      }

  fun keepConfirmed(sections: List<GuideSection>, confirmed: Set<String>): List<GuideSection> {
    var index = 0
    return sections.map { section ->
      section.copy(
        steps =
          section.steps.map { step ->
            if (keyOf(index++) in confirmed) step else step.copy(text = null)
          }
      )
    }
  }

  fun sourceOnly(sections: List<GuideSection>): List<GuideSection> = sections.map { section ->
    section.copy(steps = section.steps.map { it.copy(text = null) })
  }

  private fun keyOf(index: Int) = "s${index + 1}"
}
