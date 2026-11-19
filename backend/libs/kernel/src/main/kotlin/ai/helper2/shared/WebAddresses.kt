package ai.helper2.shared

import java.net.URI
import java.net.URISyntaxException
import java.util.Locale

object WebAddresses {
  const val MAX_LENGTH = CanonicalUrl.MAX_LENGTH

  private const val HTTPS_PORT = 443

  @JvmField
  val SECOND_LEVEL: Set<String> =
    setOf("co", "com", "net", "org", "gov", "edu", "ac", "or", "ne", "go")

  enum class Refusal {
    MALFORMED,
    NOT_HTTPS,
    USER_INFO,
    PORT,
    IP_ADDRESS,
    PRIVATE_HOST,
    PUNYCODE,
    NOT_A_HOST_NAME;

    val tag: String
      get() = name.lowercase(Locale.ROOT).replace('_', '-')
  }

  sealed interface Checked {
    data class Public(val uri: URI, val host: String) : Checked

    data class Refused(val refusal: Refusal) : Checked
  }

  private val INVISIBLE = Regex("[\\p{Cc}\\p{Cf}\\s]")

  private val NUMERIC_LABEL = Regex("0x[0-9a-f]*|[0-9]+")

  private val LABEL = Regex("[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?")

  private val TOP_LEVEL = Regex("[a-z]{2,63}")

  private val PRIVATE_SUFFIXES =
    setOf(
      "localhost",
      "localdomain",
      "local",
      "internal",
      "intranet",
      "private",
      "lan",
      "home",
      "corp",
      "test",
      "example",
      "invalid",
      "onion",
      "arpa",
    )

  @JvmStatic
  fun check(raw: String?): Checked {
    if (raw.isNullOrEmpty() || raw.length > MAX_LENGTH || INVISIBLE.containsMatchIn(raw)) {
      return Checked.Refused(Refusal.MALFORMED)
    }
    val uri =
      try {
        URI(raw)
      } catch (_: URISyntaxException) {
        return Checked.Refused(Refusal.MALFORMED)
      }
    return check(uri)
  }

  @JvmStatic
  fun check(uri: URI): Checked {
    if (!"https".equals(uri.scheme, ignoreCase = true)) {
      return Checked.Refused(Refusal.NOT_HTTPS)
    }
    val host =
      uri.host?.lowercase(Locale.ROOT)
        ?: return Checked.Refused(
          if (numericAuthority(uri)) Refusal.IP_ADDRESS else Refusal.MALFORMED
        )
    val refusal =
      when {
        uri.rawUserInfo != null -> Refusal.USER_INFO
        uri.port != -1 && uri.port != HTTPS_PORT -> Refusal.PORT
        else -> hostRefusal(host)
      }
    return if (refusal == null) Checked.Public(uri, host) else Checked.Refused(refusal)
  }

  @JvmStatic fun refusal(uri: URI): Refusal? = (check(uri) as? Checked.Refused)?.refusal

  @JvmStatic
  fun hostRefusal(host: String): Refusal? {
    val name = host.lowercase(Locale.ROOT)
    if (name.startsWith("[") || ':' in name) {
      return Refusal.IP_ADDRESS
    }
    val labels = name.split(".")
    return when {
      labels.all { NUMERIC_LABEL.matches(it) } -> Refusal.IP_ADDRESS
      labels.size < 2 || labels.last() in PRIVATE_SUFFIXES -> Refusal.PRIVATE_HOST
      labels.any { it.startsWith("xn--") } -> Refusal.PUNYCODE
      labels.any { !LABEL.matches(it) } || !TOP_LEVEL.matches(labels.last()) ->
        Refusal.NOT_A_HOST_NAME
      labels.size == 2 && labels.first() in SECOND_LEVEL -> Refusal.NOT_A_HOST_NAME
      else -> null
    }
  }

  private fun numericAuthority(uri: URI): Boolean {
    val authority = uri.rawAuthority ?: return false
    if ('@' in authority) return false
    val host = authority.replace(Regex(":\\d*$"), "").lowercase(Locale.ROOT).removeSuffix(".")
    return host.isNotEmpty() && host.split(".").all { NUMERIC_LABEL.matches(it) }
  }
}
