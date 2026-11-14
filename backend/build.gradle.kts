plugins { id("helper2.format") }

group = "ai.helper2"

version = "2026.11.3"

tasks.named("spotlessCheck") {
  dependsOn(gradle.includedBuild("build-logic").task(":spotlessCheck"))
}

tasks.named("spotlessApply") {
  dependsOn(gradle.includedBuild("build-logic").task(":spotlessApply"))
}

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
