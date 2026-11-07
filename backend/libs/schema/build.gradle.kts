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
