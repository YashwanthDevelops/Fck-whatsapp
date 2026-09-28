package dev.friendline.messenger.data

import java.net.URI

/** Enforces TLS for credentials and session tokens; private HTTP exists only for local debug builds. */
internal object HomeserverUrlPolicy {
    fun normalize(value: String, allowPrivateHttp: Boolean): String {
        val normalized = value.trim().trimEnd('/')
        val uri = runCatching { URI(normalized) }.getOrNull()
        val scheme = uri?.scheme?.lowercase()
        val host = uri?.host?.removePrefix("[")?.removeSuffix("]")?.lowercase()
        require(uri != null && host != null && uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null) {
            "Enter a valid homeserver URL without credentials, query parameters, or fragments."
        }
        val https = scheme == "https"
        val debugPrivateHttp = allowPrivateHttp && scheme == "http" && isDevelopmentHost(host)
        require(https || debugPrivateHttp) {
            "Homeservers must use HTTPS. HTTP is allowed only for a private development server in debug builds."
        }
        require(uri.port == -1 || uri.port in 1..65535) { "The homeserver URL has an invalid port." }
        return normalized
    }

    private fun isDevelopmentHost(host: String): Boolean {
        if (host == "localhost" || host.endsWith(".localhost") || host == "::1" || host == "10.0.2.2") return true
        val octets = host.split('.').map(String::toIntOrNull)
        if (octets.size != 4 || octets.any { it == null }) return false
        val first = octets[0] ?: return false
        val second = octets[1] ?: return false
        if (octets.any { value -> value == null || value !in 0..255 }) return false
        return first == 127 || first == 10 || first == 192 && second == 168 || first == 172 && second in 16..31
    }
}
