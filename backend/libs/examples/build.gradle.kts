plugins { id("helper2.kotlin-library") }

dependencies {
  api(project(":libs:catalog"))
  implementation("org.jetbrains.kotlin:kotlin-reflect")
  implementation("tools.jackson.module:jackson-module-kotlin")

  testImplementation(testFixtures(project(":libs:platform")))
}
