package dev.friendline.messenger.calls

import android.content.Context
import io.livekit.android.LiveKit
import io.livekit.android.RoomOptions
import io.livekit.android.e2ee.BaseKeyProvider
import io.livekit.android.e2ee.E2EEOptions
import io.livekit.android.room.Room
import io.livekit.android.room.track.VideoTrack
import io.livekit.android.events.RoomEvent
import io.livekit.android.renderer.TextureViewRenderer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import livekit.org.webrtc.FrameCryptorKeyDerivationAlgorithm

/**
 * A narrow LiveKit media session. The caller must obtain a fresh 32-byte key from the
 * verified Matrix to-device layer before opening this session. No key is generated here,
 * and the key is never sent to the LiveKit token endpoint.
 *
 * Android now wires this session into its permission-gated call flow and renders subscribed
 * remote video tracks. Cross-platform interoperability and physical-device validation remain.
 */
class LiveKitE2eeCallSession private constructor(
    private val room: Room,
) {
    private val stateMutex = Mutex()
    @Volatile private var state = State.CONNECTED
    private val eventScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mutableRemoteVideoTrack = MutableStateFlow<VideoTrack?>(null)
    val remoteVideoTrack = mutableRemoteVideoTrack.asStateFlow()
    private val attachedVideoRenderers = ConcurrentHashMap<TextureViewRenderer, VideoTrack>()

    init {
        refreshRemoteVideoTrack()
        eventScope.launch {
            room.events.events.collect { event ->
                when (event) {
                    is RoomEvent.TrackSubscribed -> (event.track as? VideoTrack)?.let { track ->
                        mutableRemoteVideoTrack.value = track
                    }
                    is RoomEvent.TrackUnsubscribed -> if (mutableRemoteVideoTrack.value === event.track) {
                        mutableRemoteVideoTrack.value = null
                    }
                    is RoomEvent.ParticipantDisconnected -> mutableRemoteVideoTrack.value = null
                    else -> Unit
                }
            }
        }
    }

    private fun refreshRemoteVideoTrack() {
        mutableRemoteVideoTrack.value = room.remoteParticipants.values
            .asSequence()
            .flatMap { participant -> participant.videoTrackPublications.asSequence() }
            .mapNotNull { (_, track) -> track as? VideoTrack }
            .firstOrNull()
    }

    /** Initialize and attach a renderer to a currently subscribed remote track. */
    fun attachVideoRenderer(track: VideoTrack, renderer: TextureViewRenderer) {
        synchronized(attachedVideoRenderers) {
            check(state == State.CONNECTED) { "Call session is closed" }
            check(mutableRemoteVideoTrack.value === track) { "Remote video track is no longer subscribed" }
            room.initVideoRenderer(renderer)
            track.addRenderer(renderer)
            attachedVideoRenderers[renderer] = track
        }
    }

    /** Detach and release a renderer when its view leaves composition. */
    fun detachVideoRenderer(renderer: TextureViewRenderer) {
        synchronized(attachedVideoRenderers) {
            val track = attachedVideoRenderers.remove(renderer) ?: return
            track.removeRenderer(renderer)
            renderer.release()
        }
    }

    private enum class State {
        CONNECTED,
        CLOSED,
    }

    /**
     * Enable or mute the microphone. Requires a connected session and Android's runtime
     * RECORD_AUDIO permission when enabling.
     */
    suspend fun setMicrophoneEnabled(enabled: Boolean): Boolean = stateMutex.withLock {
        check(state == State.CONNECTED) { "Call session is closed" }
        room.localParticipant.setMicrophoneEnabled(enabled)
    }

    /**
     * Enable or stop the camera. Requires a connected session and Android's runtime CAMERA
     * permission when enabling.
     */
    suspend fun setCameraEnabled(enabled: Boolean): Boolean = stateMutex.withLock {
        check(state == State.CONNECTED) { "Call session is closed" }
        room.localParticipant.setCameraEnabled(enabled)
    }

    /** Idempotently stop local capture, disconnect and release the LiveKit room. */
    suspend fun close() = stateMutex.withLock {
        if (state == State.CLOSED) return@withLock
        state = State.CLOSED
        eventScope.cancel()
        mutableRemoteVideoTrack.value = null
        synchronized(attachedVideoRenderers) {
            attachedVideoRenderers.entries.toList().forEach { (renderer, track) ->
                if (attachedVideoRenderers.remove(renderer, track)) {
                    runCatching { track.removeRenderer(renderer) }
                    runCatching { renderer.release() }
                }
            }
        }
        try {
            runCatching { room.localParticipant.setCameraEnabled(false) }
            runCatching { room.localParticipant.setMicrophoneEnabled(false) }
        } finally {
            try {
                room.disconnect()
            } finally {
                room.release()
            }
        }
    }

    companion object {
        private const val REQUIRED_CALL_KEY_BYTES = 32
        private const val RATCHET_SALT = "LKFrameEncryptionKey"
        private const val UNENCRYPTED_MAGIC_BYTES = "LK-ROCKS"
        private const val RATCHET_WINDOW_SIZE = 0
        private const val FAILURE_TOLERANCE = -1
        private const val KEY_RING_SIZE = 16
        private const val DISCARD_FRAME_WHEN_CRYPTOR_NOT_READY = false

        /**
         * Connect a LiveKit room using an ephemeral key already delivered by the app's
         * verified-device key transport. The caller must supply the server-issued URL and
         * short-lived JWT separately from the media key.
         */
        suspend fun open(
            context: Context,
            url: String,
            token: String,
            freshCallKey: ByteArray,
        ): LiveKitE2eeCallSession {
            require(url.isNotBlank()) { "LiveKit URL is required" }
            require(token.isNotBlank()) { "LiveKit participant token is required" }
            require(freshCallKey.size == REQUIRED_CALL_KEY_BYTES) {
                "A fresh 32-byte call key from verified Matrix to-device transport is required"
            }

            // Swift LiveKit 2.17.0 has a default ratchet window of 0, while Android's
            // default is 16. Set every provider parameter explicitly so both native SDKs
            // use identical derivation and ratchet settings.
            val keyProvider = BaseKeyProvider(
                ratchetSalt = RATCHET_SALT,
                uncryptedMagicBytes = UNENCRYPTED_MAGIC_BYTES,
                ratchetWindowSize = RATCHET_WINDOW_SIZE,
                enableSharedKey = true,
                failureTolerance = FAILURE_TOLERANCE,
                keyRingSize = KEY_RING_SIZE,
                discardFrameWhenCryptorNotReady = DISCARD_FRAME_WHEN_CRYPTOR_NOT_READY,
                keyDerivationAlgorithm = FrameCryptorKeyDerivationAlgorithm.PBKDF2,
            )

            // Install the exact raw key bytes used by Swift's setKey(keyData:index:). The
            // SDK copies key material into its native provider; clear our defensive copy as
            // soon as that synchronous call returns.
            val keyCopy = freshCallKey.copyOf()
            try {
                check(keyProvider.rtcKeyProvider.setSharedKey(0, keyCopy)) {
                    "LiveKit rejected the call key"
                }
            } finally {
                keyCopy.fill(0)
            }

            val room = LiveKit.create(
                appContext = context.applicationContext,
                options = RoomOptions(
                    e2eeOptions = E2EEOptions(keyProvider = keyProvider),
                ),
            )

            return try {
                room.connect(url = url, token = token)
                LiveKitE2eeCallSession(room)
            } catch (failure: Throwable) {
                room.release()
                throw failure
            }
        }
    }
}
