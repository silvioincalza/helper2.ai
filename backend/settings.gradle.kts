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
  ":libs:schema",
  ":libs:core",
  ":apps:public",
  ":apps:backoffice",
)

include(":loadtest")
