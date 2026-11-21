package ai.helper2.scout

import java.net.URI

/**
 * What a site answers to a status request for one address. The request is the Scout's own, it reads
 * no body and follows no redirect: a redirect is an answer with its [Answered.location].
 */
sealed interface PageStatus {

  /** The HTTP status code of the answer and, for a redirect, the address it names. */
  data class Answered(val code: Int, val location: String?) : PageStatus

  /** No request was made, or it gave no answer. */
  data class Refused(val reason: Reason) : PageStatus

  enum class Reason {
    HOST_CHECK,
    NEVER_READ,
    PRIVATE_ADDRESS,
    ROBOTS,
    DAILY_CAP,
    HOST_CAP,
    TIMEOUT,
    UNAVAILABLE;

    val tag: String
      get() = name.lowercase().replace('_', '-')
  }
}

/** Asks a site for the status of one of its addresses. */
fun interface PageStatusReader {
  fun status(address: URI): PageStatus
}
