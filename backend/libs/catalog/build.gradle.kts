plugins { id("helper2.kotlin-library") }

dependencies {
  api(project(":libs:search"))

  testImplementation(testFixtures(project(":libs:platform")))
}
