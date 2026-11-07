pluginManagement {
  // Convention plugins: helper2.kotlin-library, helper2.spring-app, helper2.format.
  includeBuild("build-logic")
}

plugins {
  // Downloads the Java toolchain (JDK 25) automatically when it is not installed locally.
  id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement { repositories { mavenCentral() } }

rootProject.name = "helper2-backend"

// Shared code in libraries, two runnable apps (ADR 0068, 0069). A library never depends on an app.
include(
  ":libs:kernel",
  ":libs:platform",
  ":libs:monetisation",
  ":libs:identity",
  ":libs:search",
  ":libs:catalog",
  ":libs:examples",
  ":libs:sources",
  ":libs:schema",
  ":apps:public",
  ":apps:backoffice",
)
