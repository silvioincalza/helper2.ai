package ai.helper2.shared

/**
 * Hides secrets where a value is printed: a record's `toString`, a log line. A record prints every
 * component by default, so a record that holds a token overrides `toString` and passes the token
 * through [mask]. The text says only whether a value is set, never any part of it.
 */
object Secrets {

  /** `***` when the secret is set, `none` when it is null or blank. */
  @JvmStatic fun mask(secret: String?): String = if (secret.isNullOrBlank()) "none" else "***"
}
