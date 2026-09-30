package dev.friendline.messenger.data

import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

data class FriendAddressContact(val matrixId: String, val homeserverUrl: String)

/** Versioned address-card QR data; it contains identity and routing only, never a credential. */
internal object FriendAddressQrPayload {
    private const val SCHEME = "friendline"
    private const val HOST = "friend"
    private const val VERSION = "1"
    private const val MAX_PAYLOAD_LENGTH = 2_048
    private val expectedFields = setOf("v", "matrix_id", "homeserver")

    fun encode(matrixId: String, homeserverUrl: String, allowPrivateHttp: Boolean = false): String {
        requireValidMatrixId(matrixId)
        val normalizedHomeserver = HomeserverUrlPolicy.normalize(homeserverUrl, allowPrivateHttp)
        val query = listOf(
            "v" to VERSION,
            "matrix_id" to matrixId,
            "homeserver" to normalizedHomeserver,
        ).joinToString("&") { (key, value) ->
            "$key=${encodeComponent(value)}"
        }
        return "$SCHEME://$HOST?$query"
    }

    fun parse(rawValue: String, allowPrivateHttp: Boolean = false): FriendAddressContact {
        require(rawValue.length <= MAX_PAYLOAD_LENGTH) { "Friend QR code is too large." }
        val uri = runCatching { URI(rawValue) }.getOrNull()
        require(
            uri != null && uri.scheme.equals(SCHEME, ignoreCase = true) &&
                uri.host.equals(HOST, ignoreCase = true) && uri.rawUserInfo == null &&
                uri.port == -1 && uri.rawPath.isNullOrEmpty() && uri.rawFragment == null,
        ) { "This is not a Friendline contact QR code." }

        val fields = parseQuery(uri.rawQuery ?: "")
        require(fields.keys == expectedFields && fields["v"] == VERSION) {
            "This Friendline contact QR code is invalid or unsupported."
        }
        val matrixId = requireNotNull(fields["matrix_id"])
        requireValidMatrixId(matrixId)
        val homeserverUrl = HomeserverUrlPolicy.normalize(requireNotNull(fields["homeserver"]), allowPrivateHttp)
        return FriendAddressContact(matrixId, homeserverUrl)
    }

    fun resolveForHomeserver(
        rawValue: String,
        activeHomeserverUrl: String,
        allowPrivateHttp: Boolean = false,
    ): String {
        val contact = parse(rawValue, allowPrivateHttp)
        val activeHomeserver = HomeserverUrlPolicy.normalize(activeHomeserverUrl, allowPrivateHttp)
        require(sameHomeserver(contact.homeserverUrl, activeHomeserver)) {
            "This contact QR belongs to a different homeserver. Enter the Matrix ID manually or sign in to the matching server."
        }
        return contact.matrixId
    }

    private fun parseQuery(rawQuery: String): Map<String, String> {
        require(rawQuery.isNotEmpty()) { "Friend QR code is missing its contact details." }
        val pairs = rawQuery.split('&').map { pair ->
            val separator = pair.indexOf('=')
            require(separator > 0) { "Friend QR code contains an invalid field." }
            URLDecoder.decode(pair.substring(0, separator), StandardCharsets.UTF_8) to
                URLDecoder.decode(pair.substring(separator + 1), StandardCharsets.UTF_8)
        }
        require(pairs.size == expectedFields.size && pairs.map { it.first }.toSet().size == pairs.size) {
            "Friend QR code contains duplicate or unsupported fields."
        }
        return pairs.toMap()
    }

    private fun requireValidMatrixId(matrixId: String) {
        require(matrixId.length <= 255 && Regex("^@[^:\\s]+:[^\\s]+$").matches(matrixId)) {
            "Friend QR code does not contain a complete Matrix user ID."
        }
    }

    private fun encodeComponent(value: String): String = buildString {
        value.toByteArray(StandardCharsets.UTF_8).forEach { byte ->
            val code = byte.toInt() and 0xff
            val unreserved = code in 'A'.code..'Z'.code || code in 'a'.code..'z'.code ||
                code in '0'.code..'9'.code || code == '-'.code || code == '.'.code ||
                code == '_'.code || code == '~'.code
            if (unreserved) {
                append(code.toChar())
            } else {
                append('%')
                append(HEX[code ushr 4])
                append(HEX[code and 0x0f])
            }
        }
    }

    private fun sameHomeserver(leftValue: String, rightValue: String): Boolean {
        val left = URI(leftValue)
        val right = URI(rightValue)
        return left.scheme.equals(right.scheme, ignoreCase = true) &&
            left.host.equals(right.host, ignoreCase = true) &&
            effectivePort(left) == effectivePort(right) &&
            left.path.trimEnd('/') == right.path.trimEnd('/')
    }

    private fun effectivePort(uri: URI): Int = when {
        uri.port != -1 -> uri.port
        uri.scheme.equals("https", ignoreCase = true) -> 443
        uri.scheme.equals("http", ignoreCase = true) -> 80
        else -> -1
    }

    private const val HEX = "0123456789ABCDEF"
}
