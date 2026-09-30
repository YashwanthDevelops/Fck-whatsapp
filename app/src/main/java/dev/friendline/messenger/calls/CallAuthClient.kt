package dev.friendline.messenger.calls

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.io.ByteArrayOutputStream

/** Matrix-membership-gated LiveKit credentials. The access token is sent only to the
 * configured homeserver origin and is never included in logs or exception messages. */
internal data class CallCredentials(
    val callId: String,
    val liveKitUrl: String,
    val liveKitToken: String,
    val expiresAtEpochSeconds: Long,
)

internal class CallAuthClient(
    private val connectTimeoutMillis: Int = 8_000,
    private val readTimeoutMillis: Int = 8_000,
) {
    fun createCall(homeserverUrl: String, accessToken: String, roomId: String): CallCredentials =
        request(homeserverUrl, accessToken, mapOf("matrix_room_id" to roomId), joinCallId = null)

    fun joinCall(
        homeserverUrl: String,
        accessToken: String,
        roomId: String,
        callId: String,
    ): CallCredentials {
        require(CallProtocol.isCanonicalCallId(callId)) { "Invalid call identifier" }
        return request(homeserverUrl, accessToken, mapOf("matrix_room_id" to roomId), callId)
    }

    private fun request(
        homeserverUrl: String,
        accessToken: String,
        body: Map<String, String>,
        joinCallId: String?,
    ): CallCredentials {
        require(accessToken.isNotBlank() && accessToken.length <= 4_096) { "Invalid session" }
        require(body.getValue("matrix_room_id").isNotBlank()) { "Invalid room" }
        val origin = parseHomeserverOrigin(homeserverUrl)
        val suffix = joinCallId?.let { "/${it.encodePathSegment()}" }.orEmpty()
        val connection = (URL("$origin/_friendline/calls/v1/calls$suffix").openConnection() as HttpURLConnection)
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = connectTimeoutMillis
            connection.readTimeout = readTimeoutMillis
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("Authorization", "Bearer $accessToken")
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            connection.doOutput = true
            connection.outputStream.use { it.write(JSONObject(body).toString().toByteArray(Charsets.UTF_8)) }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val raw = stream?.use { readBounded(it) } ?: ByteArray(0)
            check(raw.size <= MAX_RESPONSE_BYTES) { "Call service returned an invalid response" }
            check(status in 200..299) {
                when (status) {
                    401 -> "Your session is no longer authorized for calls"
                    403 -> "Calls require an encrypted room and current room membership"
                    404 -> "This call invitation has expired or is invalid"
                    else -> "The private call service is unavailable (HTTP $status)"
                }
            }
            return parseResponse(JSONObject(raw.toString(Charsets.UTF_8)), joinCallId)
        } finally {
            connection.disconnect()
        }
    }

    private fun parseResponse(json: JSONObject, expectedCallId: String?): CallCredentials {
        val callId = json.getString("call_id")
        val liveKitUrl = json.getString("url")
        val token = json.getString("token")
        val expiresAt = json.getLong("expires_at")
        check(CallProtocol.isCanonicalCallId(callId)) { "Call service returned an invalid call identifier" }
        check(expectedCallId == null || callId == expectedCallId) { "Call service returned the wrong call" }
        val liveKit = URI(liveKitUrl)
        check(liveKit.scheme == "wss" && !liveKit.host.isNullOrBlank() &&
            liveKit.rawUserInfo == null && liveKit.rawQuery == null && liveKit.rawFragment == null &&
            (liveKit.rawPath.isNullOrEmpty() || liveKit.rawPath == "/")) {
            "Call service returned an insecure media endpoint"
        }
        check(token.length in 32..8_192 && token.none(Char::isWhitespace)) {
            "Call service returned an invalid media token"
        }
        check(expiresAt > System.currentTimeMillis() / 1_000L) { "Call authorization has expired" }
        return CallCredentials(callId, liveKitUrl, token, expiresAt)
    }

    private fun parseHomeserverOrigin(value: String): String {
        val uri = URI(value.trim())
        val secure = uri.scheme == "https"
        val localDebug = uri.scheme == "http" && uri.host in setOf("10.0.2.2", "127.0.0.1", "localhost")
        require((secure || localDebug) && !uri.host.isNullOrBlank() && uri.rawUserInfo == null &&
            uri.rawQuery == null && uri.rawFragment == null && uri.rawPath in setOf(null, "", "/")) {
            "The call service requires a trusted HTTPS homeserver"
        }
        return "${uri.scheme}://${uri.rawAuthority}"
    }

    private fun String.encodePathSegment(): String =
        java.net.URLEncoder.encode(this, Charsets.UTF_8.name()).replace("+", "%20")

    private fun readBounded(input: java.io.InputStream): ByteArray {
        val result = ByteArrayOutputStream()
        val chunk = ByteArray(2_048)
        while (true) {
            val count = input.read(chunk)
            if (count < 0) break
            if (result.size() + count > MAX_RESPONSE_BYTES) return ByteArray(MAX_RESPONSE_BYTES + 1)
            result.write(chunk, 0, count)
        }
        return result.toByteArray()
    }

    private companion object {
        const val MAX_RESPONSE_BYTES = 16 * 1024
    }
}
