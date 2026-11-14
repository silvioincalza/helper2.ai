plugins { id("helper2.kotlin-library") }

dependencies {
  api(project(":libs:platform"))
  api("org.springframework.boot:spring-boot-starter-security")
  api("org.springframework.boot:spring-boot-starter-security-oauth2-client")
  api("org.springframework.boot:spring-boot-starter-session-data-redis")

  testImplementation(testFixtures(project(":libs:platform")))
}
