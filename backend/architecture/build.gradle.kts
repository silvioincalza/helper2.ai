plugins { id("searchavo.kotlin-library") }

dependencies { testImplementation(testFixtures(project(":libs:platform"))) }

tasks.named<Test>("test") {
  inputs
    .files(rootProject.files("../deploy/helm/searchavo/files/redis-users.acl"))
    .withPropertyName("redisUsers")
    .withPathSensitivity(PathSensitivity.RELATIVE)
  inputs
    .files(
      rootProject.fileTree(".") {
        include("libs/*/src/*/java/**", "libs/*/src/*/kotlin/**")
        include("apps/*/src/main/java/**", "apps/*/src/main/kotlin/**")
      }
    )
    .withPropertyName("backendSources")
    .withPathSensitivity(PathSensitivity.RELATIVE)
}
