// The live search sources (package ai.helper2.sources): GitHub, Hugging Face, npm, arXiv and the
// live web, with their shared HTTP mapping and settings. Both apps search; the Scout's collectors
// stay in the back office (ADR 0032).
plugins {
  id("helper2.kotlin-library")
  // SourceFixtures: settings and hits for the tests of the sources and of the collectors.
  `java-test-fixtures`
}

dependencies {
  api(project(":libs:search"))

  testImplementation(testFixtures(project(":libs:platform")))
  testFixturesImplementation(project(":libs:search"))
  testFixturesImplementation(testFixtures(project(":libs:platform")))
}
