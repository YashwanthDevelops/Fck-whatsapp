package dev.friendline.messenger.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import dev.friendline.messenger.push.PushPusherIdentity
import dev.friendline.messenger.push.PushPusherOperation
import dev.friendline.messenger.push.PushPusherRecord
import org.json.JSONObject
import org.matrix.rustcomponents.sdk.Session
import org.matrix.rustcomponents.sdk.SlidingSyncVersion
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Device-only key storage. The Matrix store key and session record never reach app preferences or backups. */
class DeviceVault(context: Context) {
    private val root = File(context.noBackupFilesDir, "private-messenger").apply { mkdirs() }
    private val storeKeyFile = File(root, "store-key.bin")
    private val sessionFile = File(root, "session.bin")
    private val settingsFile = File(root, "settings.bin")
    private val pushPusherFile = File(root, "push-pusher.bin")

    @Synchronized
    fun loadOrCreateStoreKey(): ByteArray {
        if (storeKeyFile.exists()) return open(storeKeyFile.readBytes())

        val key = ByteArray(32).also(SecureRandom()::nextBytes)
        writeAtomically(storeKeyFile, seal(key))
        return key
    }

    @Synchronized
    fun saveSession(session: Session) {
        val json = JSONObject()
            .put("accessToken", session.accessToken)
            .put("refreshToken", session.refreshToken)
            .put("userId", session.userId)
            .put("deviceId", session.deviceId)
            .put("homeserverUrl", session.homeserverUrl)
            .put("oauthData", session.oauthData)
            .put("slidingSyncVersion", session.slidingSyncVersion.name)
        writeAtomically(sessionFile, seal(json.toString().toByteArray(Charsets.UTF_8)))
    }

    @Synchronized
    fun loadSession(): Session? {
        if (!sessionFile.exists()) return null
        val json = JSONObject(String(open(sessionFile.readBytes()), Charsets.UTF_8))
        return Session(
            accessToken = json.getString("accessToken"),
            refreshToken = json.opt("refreshToken") as? String,
            userId = json.getString("userId"),
            deviceId = json.getString("deviceId"),
            homeserverUrl = json.getString("homeserverUrl"),
            oauthData = json.opt("oauthData") as? String,
            slidingSyncVersion = SlidingSyncVersion.valueOf(json.getString("slidingSyncVersion")),
        )
    }

    @Synchronized
    fun loadReadReceiptsEnabled(): Boolean {
        return loadSettings().optBoolean("readReceiptsEnabled", false)
    }

    @Synchronized
    fun saveReadReceiptsEnabled(enabled: Boolean) {
        writeSetting("readReceiptsEnabled", enabled)
    }

    @Synchronized
    fun loadPushNotificationsEnabled(): Boolean {
        return loadSettings().optBoolean("pushNotificationsEnabled", false)
    }

    @Synchronized
    fun savePushNotificationsEnabled(enabled: Boolean) {
        writeSetting("pushNotificationsEnabled", enabled)
    }

    @Synchronized
    fun savePushRemovalPending(pending: Boolean) {
        writeSetting("pushRemovalPending", pending)
    }

    @Synchronized
    fun loadPushRemovalPending(): Boolean = loadSettings().optBoolean("pushRemovalPending", false)

    /** Persist opt-out and its cleanup intent together in one encrypted atomic settings replacement. */
    @Synchronized
    fun savePushOptOutWithRemovalPending() {
        val settings = loadSettings()
            .put("pushNotificationsEnabled", false)
            .put("pushRemovalPending", true)
        writeAtomically(settingsFile, seal(settings.toString().toByteArray(Charsets.UTF_8)))
    }

    /** The encrypted record contains only pusher routing identity, provider token, and retry state. */
    @Synchronized
    internal fun loadPushPusherRecord(): PushPusherRecord? {
        if (!pushPusherFile.exists()) return null
        val plaintext = decryptLocalData(PUSH_PUSHER_PURPOSE, pushPusherFile.readBytes())
        val json = JSONObject(String(plaintext, Charsets.UTF_8))
        val identity = PushPusherIdentity(
            homeserverUrl = json.getString("homeserverUrl"),
            userId = json.getString("userId"),
            deviceId = json.getString("deviceId"),
            appId = json.getString("appId"),
        )
        val pushToken = if (json.isNull("pushToken")) null else json.getString("pushToken")
        return PushPusherRecord(
            identity = identity,
            pushToken = pushToken,
            operation = PushPusherOperation.valueOf(json.getString("operation")),
        )
    }

    @Synchronized
    internal fun savePushPusherRecord(record: PushPusherRecord) {
        val json = JSONObject()
            .put("homeserverUrl", record.identity.homeserverUrl)
            .put("userId", record.identity.userId)
            .put("deviceId", record.identity.deviceId)
            .put("appId", record.identity.appId)
            .put("pushToken", record.pushToken ?: JSONObject.NULL)
            .put("operation", record.operation.name)
        val encrypted = encryptLocalData(PUSH_PUSHER_PURPOSE, json.toString().toByteArray(Charsets.UTF_8))
        writeAtomically(pushPusherFile, encrypted)
    }

    @Synchronized
    internal fun clearPushPusherRecord() {
        if (pushPusherFile.exists() && !pushPusherFile.delete()) {
            throw IOException("Couldn't remove the encrypted push registration record")
        }
    }

    @Synchronized
    internal fun hasPushPusherRecord(): Boolean = pushPusherFile.isFile

    private fun loadSettings(): JSONObject {
        if (!settingsFile.exists()) return JSONObject()
        return JSONObject(String(open(settingsFile.readBytes()), Charsets.UTF_8))
    }

    private fun writeSetting(name: String, value: Boolean) {
        val settings = loadSettings().put(name, value)
        writeAtomically(settingsFile, seal(settings.toString().toByteArray(Charsets.UTF_8)))
    }

    /** Encrypt small local journals with purpose-bound AAD under the device Keystore key. */
    @Synchronized
    fun encryptLocalData(purpose: String, plaintext: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        cipher.updateAAD(purpose.toByteArray(Charsets.UTF_8))
        val encrypted = cipher.doFinal(plaintext)
        return ByteBuffer.allocate(2 + cipher.iv.size + encrypted.size)
            .put(LOCAL_FILE_VERSION)
            .put(cipher.iv.size.toByte())
            .put(cipher.iv)
            .put(encrypted)
            .array()
    }

    @Synchronized
    fun decryptLocalData(purpose: String, payload: ByteArray): ByteArray {
        val buffer = ByteBuffer.wrap(payload)
        require(buffer.remaining() >= 2 && buffer.get() == LOCAL_FILE_VERSION) { "Encrypted local data is invalid" }
        val ivLength = buffer.get().toInt() and 0xff
        require(ivLength == 12 && buffer.remaining() > ivLength + GCM_TAG_BYTES) { "Encrypted local data is invalid" }
        val iv = ByteArray(ivLength).also(buffer::get)
        val encrypted = ByteArray(buffer.remaining()).also(buffer::get)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(128, iv))
        cipher.updateAAD(purpose.toByteArray(Charsets.UTF_8))
        return cipher.doFinal(encrypted)
    }

    /** Stream media through AES-GCM so large attachments never need to be held in memory. */
    @Synchronized
    fun encryptLocalFile(purpose: String, source: File, destination: File) {
        destination.parentFile?.mkdirs()
        val temporary = File(destination.parentFile, "${destination.name}.tmp")
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        cipher.updateAAD(purpose.toByteArray(Charsets.UTF_8))
        try {
            FileOutputStream(temporary).use { output ->
                output.write(LOCAL_FILE_VERSION.toInt())
                output.write(cipher.iv.size)
                output.write(cipher.iv)
                FileInputStream(source).use { input ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        cipher.update(buffer, 0, count)?.let { output.write(it) }
                    }
                }
                cipher.doFinal()?.let { output.write(it) }
                output.fd.sync()
            }
            replaceAtomically(temporary, destination)
        } catch (error: Exception) {
            temporary.delete()
            throw error
        }
    }

    @Synchronized
    fun decryptLocalFile(purpose: String, source: File, destination: File) {
        destination.parentFile?.mkdirs()
        val temporary = File(destination.parentFile, "${destination.name}.restore")
        try {
            FileInputStream(source).use { input ->
                require(input.read() == LOCAL_FILE_VERSION.toInt()) { "Encrypted local media is invalid" }
                val ivLength = input.read()
                require(ivLength == 12) { "Encrypted local media is invalid" }
                val iv = ByteArray(ivLength)
                var read = 0
                while (read < iv.size) {
                    val count = input.read(iv, read, iv.size - read)
                    require(count > 0) { "Encrypted local media is incomplete" }
                    read += count
                }
                val cipher = Cipher.getInstance(TRANSFORMATION)
                cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(128, iv))
                cipher.updateAAD(purpose.toByteArray(Charsets.UTF_8))
                FileOutputStream(temporary).use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        val plainChunk = cipher.update(buffer, 0, count)
                        if (plainChunk != null) output.write(plainChunk)
                    }
                    // CipherInputStream may suppress authentication failures for some
                    // providers. Only doFinal verifies the GCM tag, so publish the
                    // temporary plaintext after this call succeeds.
                    val finalChunk = cipher.doFinal()
                    if (finalChunk.isNotEmpty()) output.write(finalChunk)
                    output.fd.sync()
                }
            }
            replaceAtomically(temporary, destination)
        } catch (error: Exception) {
            temporary.delete()
            throw error
        }
    }

    @Synchronized
    fun clearSession() {
        val files = listOf(sessionFile, storeKeyFile, settingsFile, pushPusherFile)
        val snapshots = files.filter { it.isFile }.associateWith { it.readBytes() }
        val store = keyStore()
        try {
            snapshots.keys.forEach { file ->
                if (file.exists() && !file.delete()) throw IOException("Couldn't remove encrypted local state")
            }
            if (store.containsAlias(KEY_ALIAS)) store.deleteEntry(KEY_ALIAS)
            if (store.containsAlias(KEY_ALIAS)) throw IOException("Couldn't remove the local Keystore key")
        } catch (error: Exception) {
            // Keep logout retryable: restore encrypted records while the wrapping key is
            // still available whenever any delete operation fails.
            var restoreFailure: Exception? = null
            snapshots.forEach { (file, bytes) ->
                if (!file.exists()) {
                    runCatching { writeAtomically(file, bytes) }
                        .onFailure { restoreFailure = it as? Exception ?: IOException("Couldn't restore encrypted local state") }
                }
            }
            restoreFailure?.let(error::addSuppressed)
            throw IOException("Couldn't securely clear the saved session", error)
        }
    }

    private fun seal(plainText: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val encrypted = cipher.doFinal(plainText)
        return ByteBuffer.allocate(1 + cipher.iv.size + encrypted.size)
            .put(cipher.iv.size.toByte())
            .put(cipher.iv)
            .put(encrypted)
            .array()
    }

    private fun open(payload: ByteArray): ByteArray {
        require(payload.isNotEmpty()) { "Encrypted local data is empty" }
        val buffer = ByteBuffer.wrap(payload)
        val ivLength = buffer.get().toInt() and 0xff
        require(ivLength == 12 && buffer.remaining() > ivLength) { "Encrypted local data is invalid" }
        val iv = ByteArray(ivLength).also(buffer::get)
        val encrypted = ByteArray(buffer.remaining()).also(buffer::get)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), javax.crypto.spec.GCMParameterSpec(128, iv))
        return cipher.doFinal(encrypted)
    }

    private fun getOrCreateKey(): SecretKey {
        val store = keyStore()
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .build(),
        )
        return generator.generateKey()
    }

    private fun keyStore(): KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    private fun writeAtomically(destination: File, bytes: ByteArray) {
        val temporary = File(root, "${destination.name}.tmp")
        FileOutputStream(temporary).use { stream ->
            stream.write(bytes)
            stream.fd.sync()
        }
        replaceAtomically(temporary, destination)
    }

    private fun replaceAtomically(temporary: File, destination: File) {
        try {
            Files.move(temporary.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(temporary.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "private-messenger-device-wrap-v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val LOCAL_FILE_VERSION: Byte = 1
        const val GCM_TAG_BYTES = 16
        const val PUSH_PUSHER_PURPOSE = "android-matrix-pusher-v1"
    }
}
