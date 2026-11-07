// The resource catalog (package ai.helper2.catalog); the controllers stay in the app.
plugins { id("helper2.kotlin-library") }

dependencies {
  api(project(":libs:search"))
  api(project(":libs:identity"))

  testImplementation(testFixtures(project(":libs:platform")))
}
