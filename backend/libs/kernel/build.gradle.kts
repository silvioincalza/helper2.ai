// Shared value types of every module (package ai.helper2.shared).
plugins { id("helper2.kotlin-library") }

dependencies {
  // Language of queries and catalog text, for the matching Postgres stemmer (LanguageDetector).
  api("com.github.pemistahl:lingua:1.2.2")
}
