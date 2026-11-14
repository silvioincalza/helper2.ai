package ai.helper2.shared

object Secrets {
  @JvmStatic fun mask(secret: String?): String = if (secret.isNullOrBlank()) "none" else "***"
}
