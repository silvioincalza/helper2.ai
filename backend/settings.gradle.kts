pluginManagement {
  includeBuild("build-logic")
}

plugins {
  id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement { repositories { mavenCentral() } }

rootProject.name = "helper2-backend"

include(
  ":libs:kernel",
  ":libs:platform",
  ":libs:identity",
  ":libs:search",
  ":libs:catalog",
  ":libs:examples",
  ":libs:sources",
  ":libs:schema",
  ":libs:web-contract",
  ":apps:public",
  ":apps:backoffice",
)

include(":loadtest")
