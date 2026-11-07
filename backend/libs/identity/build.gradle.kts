// Users, sign-in and sessions (package ai.helper2.identity); the controllers stay in the app.
plugins { id("helper2.kotlin-library") }

dependencies {
  api(project(":libs:platform"))
  api(project(":libs:monetisation"))
  api("org.springframework.boot:spring-boot-starter-security")
  api("org.springframework.boot:spring-boot-starter-security-oauth2-client")
  // Sign-in sessions in Redis, so a signed-in admin is recognised by every replica.
  api("org.springframework.boot:spring-boot-starter-session-data-redis")

  testImplementation(testFixtures(project(":libs:platform")))
}
