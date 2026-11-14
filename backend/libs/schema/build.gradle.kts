plugins { id("helper2.kotlin-library") }

dependencies {
  api("org.springframework.boot:spring-boot-starter-flyway")
  api("org.springframework.boot:spring-boot-starter-jdbc")
  implementation("org.flywaydb:flyway-database-postgresql")
  implementation("org.postgresql:postgresql")
}
