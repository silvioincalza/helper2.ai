// The Flyway migrations (classpath db/migration) and how the apps apply them (ADR 0004): the
// migrate step of the public image (a Kubernetes Job in a deployment) migrates as the owner and
// opens the apps' logins; each app only checks the schema, as its own role. Applied files are
// byte-frozen: Flyway checks their checksums.
plugins { id("helper2.kotlin-library") }

dependencies {
  api("org.springframework.boot:spring-boot-starter-flyway")
  api("org.springframework.boot:spring-boot-starter-jdbc")
  implementation("org.flywaydb:flyway-database-postgresql")
  // PGConnection.alterUserPassword: the password is hashed (SCRAM) before it leaves the client.
  implementation("org.postgresql:postgresql")
}

// The squash gate of the first release (ADR 0004 §5, SquashEquivalenceIT): the old chain V1..V23,
// read from git, and the single V1 build the same database. It needs the git history, so it is not
// part of test: ./gradlew :libs:schema:squashCheck [-Ph2.squash.old-ref=<commit>]
tasks.named<Test>("test") { useJUnitPlatform { excludeTags("squash") } }

tasks.register<Test>("squashCheck") {
  description = "Runs @Tag(\"squash\"): the old migration chain against the single V1."
  group = "verification"
  testClassesDirs = sourceSets["test"].output.classesDirs
  classpath = sourceSets["test"].runtimeClasspath
  useJUnitPlatform { includeTags("squash") }
  providers.gradleProperty("h2.squash.old-ref").orNull?.let {
    systemProperty("h2.squash.old-ref", it)
  }
  testLogging { showStandardStreams = true }
  outputs.upToDateWhen { false }
}
