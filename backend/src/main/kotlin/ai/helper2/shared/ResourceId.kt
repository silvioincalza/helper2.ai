package ai.helper2.shared

import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.UUID

/** Stable resource identifier: RFC 9562 UUIDv5 of the canonical URL in a fixed Helper2 namespace. */
data class ResourceId(val value: UUID) {
    override fun toString() = value.toString()

    companion object {
        /** Never change: every bookmark and shared link depends on it. */
        private val NAMESPACE: UUID = UUID.fromString("6f1c2a8e-4b1d-5c3e-9a7f-2d4e6b8c0a12")

        @JvmStatic
        fun of(url: CanonicalUrl): ResourceId {
            val ns = ByteBuffer.allocate(16)
                .putLong(NAMESPACE.mostSignificantBits)
                .putLong(NAMESPACE.leastSignificantBits)
                .array()
            val sha1 = MessageDigest.getInstance("SHA-1")
            sha1.update(ns)
            val hash = sha1.digest(url.value.toByteArray(Charsets.UTF_8))
            hash[6] = ((hash[6].toInt() and 0x0f) or 0x50).toByte()
            hash[8] = ((hash[8].toInt() and 0x3f) or 0x80).toByte()
            val buf = ByteBuffer.wrap(hash, 0, 16)
            return ResourceId(UUID(buf.long, buf.long))
        }
    }
}
