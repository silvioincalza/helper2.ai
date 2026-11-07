// The metasearch core (package ai.helper2.search): fan-out, merging, ranking; the controllers stay
// in the app.
plugins {
  id("helper2.kotlin-library")
  // Fixtures.kt: search hits and source descriptors for the tests of search and of the app.
  `java-test-fixtures`
}

dependencies {
  api(project(":libs:platform"))
  implementation("org.jetbrains.kotlin:kotlin-reflect")
  implementation("tools.jackson.module:jackson-module-kotlin")

  testImplementation(testFixtures(project(":libs:platform")))
  testImplementation("io.micrometer:micrometer-registry-prometheus")
  testFixturesImplementation("org.jetbrains.kotlin:kotlin-reflect")
}
