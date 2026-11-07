// Convention plugins of the backend build: one place for plugins, versions, BOMs and test tasks.
dependencyResolutionManagement {
  repositories {
    gradlePluginPortal()
    mavenCentral()
  }
}

rootProject.name = "build-logic"
