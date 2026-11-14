plugins {
  id("helper2.kotlin-library")
  `java-test-fixtures`
}

dependencies {
  api(project(":libs:search"))

  testImplementation(testFixtures(project(":libs:platform")))
  testFixturesImplementation(project(":libs:search"))
  testFixturesImplementation(testFixtures(project(":libs:platform")))
}
