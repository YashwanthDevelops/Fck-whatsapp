package dev.friendline.messenger.data

/** Recognizes Matrix requests that accidentally target this device instead of the server host. */
internal object HomeserverFailurePolicy {
    private val requestUrl = Regex(
        """https?://(?<host>\[[0-9a-fA-F:]+]|[^:/\s\",)]+)(?::\d+)?(?:[/\s),}]|$)""",
        RegexOption.IGNORE_CASE,
    )

    fun targetsLoopback(failure: Throwable): Boolean =
        generateSequence(failure) { it.cause }
            .mapNotNull { it.message }
            .flatMap { message -> requestUrl.findAll(message).map { it.groups["host"]?.value.orEmpty() } }
            .any { rawHost ->
                val host = rawHost.removePrefix("[").removeSuffix("]").lowercase()
                host == "localhost" || host.endsWith(".localhost") || host == "::1" ||
                    host == "0.0.0.0" || host.startsWith("127.")
            }
}
