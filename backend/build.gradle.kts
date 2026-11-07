// The backend: libraries in libs/, the runnable app in apps/. Plugins, versions and test tasks
// live in the convention plugins of build-logic/.
plugins { id("helper2.format") }

group = "ai.helper2"

// One version for every project (ADR 0034); release.yml reads it from this line.
version = "2.0.0"

// ./gradlew spotlessCheck and spotlessApply also cover the convention plugins.
tasks.named("spotlessCheck") {
  dependsOn(gradle.includedBuild("build-logic").task(":spotlessCheck"))
}

tasks.named("spotlessApply") {
  dependsOn(gradle.includedBuild("build-logic").task(":spotlessApply"))
}

// Code lives in a project under libs/ or apps/. A file left in the old backend/src/ (a branch from
// before the multi-project move) would compile nowhere and fail silently: stop the build instead.
val strayRootSources =
  tasks.register("checkNoRootSources") {
    val root = layout.projectDirectory.dir("src").asFile
    doLast {
      val stray = root.walkTopDown().filter { it.isFile }.toList()
      check(stray.isEmpty()) {
        "Move these files into a project under libs/ or apps/: " +
          stray.joinToString { it.relativeTo(root.parentFile).path }
      }
    }
  }

tasks.named("check") { dependsOn(strayRootSources) }

subprojects {
  tasks
    .matching { it.name == "compileJava" || it.name == "compileKotlin" }
    .configureEach {
      dependsOn(strayRootSources)
    }
}
