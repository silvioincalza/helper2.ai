plugins { id("com.diffplug.spotless") }

spotless {
  java {
    target("src/**/*.java")
    googleJavaFormat("2.0.0")
  }
  kotlin {
    target("src/**/*.kt")
    ktfmt("0.64").googleStyle()
  }
  kotlinGradle {
    target("*.gradle.kts")
    ktfmt("0.64").googleStyle()
  }
}
