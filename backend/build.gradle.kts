// The backend: libraries in libs/, the runnable app in apps/. Plugins, versions and test tasks
// live in the convention plugins of build-logic/.
plugins { id("helper2.format") }

group = "ai.helper2"

// One version for every project (ADR 0029); release.yml reads it from this line.
version = "2.0.0"

// ./gradlew spotlessCheck and spotlessApply also cover the convention plugins.
tasks.named("spotlessCheck") {
  dependsOn(gradle.includedBuild("build-logic").task(":spotlessCheck"))
}

tasks.named("spotlessApply") {
  dependsOn(gradle.includedBuild("build-logic").task(":spotlessApply"))
}
