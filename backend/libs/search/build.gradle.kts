plugins {
  id("helper2.kotlin-library")
  `java-test-fixtures`
}

dependencies {
  api(project(":libs:platform"))
  implementation("com.github.pemistahl:lingua:1.2.2")
  implementation("org.jetbrains.kotlin:kotlin-reflect")
  implementation("tools.jackson.module:jackson-module-kotlin")

  testImplementation(testFixtures(project(":libs:platform")))
  testImplementation("io.micrometer:micrometer-registry-prometheus")
  testFixturesImplementation("org.jetbrains.kotlin:kotlin-reflect")
}
