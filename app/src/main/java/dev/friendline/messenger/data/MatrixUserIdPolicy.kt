package dev.friendline.messenger.data

internal object MatrixUserIdPolicy {
    private val pattern = Regex("^@[^:\\s]+:[^\\s]+$")

    fun normalize(value: String): String? = value.trim().takeIf(pattern::matches)
}
