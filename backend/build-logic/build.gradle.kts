plugins {
  `kotlin-dsl`
  // Code format of the convention plugins themselves; the backend's spotlessCheck runs it.
  id("com.diffplug.spotless") version "8.11.0"
}

// The Gradle plugins the conventions apply, at the versions the backend used as one project.
dependencies {
  implementation("org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.21")
  implementation("org.jetbrains.kotlin:kotlin-allopen:2.4.21")
  implementation("org.springframework.boot:spring-boot-gradle-plugin:4.1.2")
  implementation("io.spring.gradle:dependency-management-plugin:1.1.7")
  implementation("org.openapitools:openapi-generator-gradle-plugin:7.26.0")
  // The generator's own OpenAPI parser and writer, for BundleContract (same version as the plugin).
  implementation("org.openapitools:openapi-generator:7.26.0")
  implementation("com.diffplug.spotless:spotless-plugin-gradle:8.11.0")
}

spotless {
  kotlin {
    target("src/**/*.kt")
    ktfmt("0.64").googleStyle()
  }
  kotlinGradle {
    target("*.gradle.kts", "src/**/*.gradle.kts")
    ktfmt("0.64").googleStyle()
  }
}
