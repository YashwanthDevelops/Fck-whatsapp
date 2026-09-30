import Foundation
import LiveKit

/// A single-use 256-bit media key received from the app's future verified-device
/// transport. This wrapper does not generate keys or provide a transport.
///
/// Callers must create a fresh instance for each call and must not reuse key bytes.
public final class FreshCallMediaKey {
    private var material: Data?

    public init(bytes: Data) throws {
        guard bytes.count == 32 else {
            throw LiveKitCallMediaSessionError.invalidCallKey
        }
        material = Data(bytes)
    }

    fileprivate func consume() throws -> Data {
        guard let material else {
            throw LiveKitCallMediaSessionError.callKeyAlreadyConsumed
        }
        self.material = nil
        return material
    }

    deinit {
        guard var material = material else { return }
        self.material = nil
        material.resetBytes(in: 0..<material.count)
    }
}

public enum LiveKitCallMediaSessionError: Error {
    case invalidCallKey
    case callKeyAlreadyConsumed
    case invalidJoinCredentials
    case sessionClosed
}

/// Standalone LiveKit media host. It requires an out-of-band, fresh call key and
/// deliberately contains no Matrix signaling or key-distribution behavior.
///
/// This uses LiveKit's frame E2EE options. The caller must not expose the join
/// token or media key to the token service, and must not connect until the key
/// was obtained through verified-device transport.
@MainActor
public final class LiveKitCallMediaSession {
    private enum State: Equatable {
        case connected
        case closing
        case closed
    }

    private var room: Room?
    // RoomOptions also retains this provider. Drop both references after disconnect.
    private var keyProvider: BaseKeyProvider?
    private var state: State = .connected
    private var closeWaiters: [CheckedContinuation<Void, Never>] = []

    private init(room: Room, keyProvider: BaseKeyProvider) {
        self.room = room
        self.keyProvider = keyProvider
    }

    /// Connects with the supplied call key already installed in LiveKit's shared
    /// BaseKeyProvider before Room.connect creates the E2EE manager.
    public static func connect(
        url: String,
        token: String,
        freshCallKey: FreshCallMediaKey
    ) async throws -> LiveKitCallMediaSession {
        guard !url.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty,
              !token.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
        else {
            throw LiveKitCallMediaSessionError.invalidJoinCredentials
        }

        let provider = try Self.makeKeyProvider(from: freshCallKey)

        let e2eeOptions = E2EEOptions(keyProvider: provider, encryptionType: .gcm)
        let room = Room(roomOptions: RoomOptions(e2eeOptions: e2eeOptions))

        do {
            try await room.connect(url: url, token: token)
            return LiveKitCallMediaSession(room: room, keyProvider: provider)
        } catch {
            // connect() may have partially initialized transports; always request
            // SDK cleanup before releasing our last references to room and provider.
            await room.disconnect()
            throw error
        }
    }

    private static func makeKeyProvider(from freshCallKey: FreshCallMediaKey) throws -> BaseKeyProvider {
        var rawKey = try freshCallKey.consume()
        defer { rawKey.resetBytes(in: 0..<rawKey.count) }

        let providerOptions = KeyProviderOptions(
            sharedKey: true,
            ratchetSalt: Data(defaultRatchetSalt.utf8),
            ratchetWindowSize: 0,
            uncryptedMagicBytes: Data(defaultMagicBytes.utf8),
            failureTolerance: -1,
            keyRingSize: 16,
            discardFrameWhenCryptorNotReady: false,
            keyDerivationAlgorithm: .pbkdf2
        )
        let provider = BaseKeyProvider(options: providerOptions)
        // Install the original random bytes directly. Android's wrapper has a
        // String-only setter, but its pinned WebRTC key provider exposes the same
        // raw-byte setSharedKey operation used by this Swift API.
        provider.setKey(keyData: rawKey, index: 0)
        return provider
    }

    /// Enables or mutes the microphone. The app must obtain microphone permission
    /// before enabling it.
    public func setMicrophoneEnabled(_ enabled: Bool) async throws {
        guard state == .connected, let room else {
            throw LiveKitCallMediaSessionError.sessionClosed
        }
        _ = try await room.localParticipant.setMicrophone(enabled: enabled)
        guard state == .connected else {
            if enabled { try? await room.localParticipant.setMicrophone(enabled: false) }
            throw LiveKitCallMediaSessionError.sessionClosed
        }
    }

    /// Enables or stops the camera. The app must obtain camera permission before
    /// enabling it.
    public func setCameraEnabled(_ enabled: Bool) async throws {
        guard state == .connected, let room else {
            throw LiveKitCallMediaSessionError.sessionClosed
        }
        _ = try await room.localParticipant.setCamera(enabled: enabled)
        guard state == .connected else {
            if enabled { try? await room.localParticipant.setCamera(enabled: false) }
            throw LiveKitCallMediaSessionError.sessionClosed
        }
    }

    /// Idempotently stop local capture and disconnect. The pinned SDK exposes no
    /// public key-wipe method. Input key buffers are wiped during setup; cleanup
    /// disconnects the room and drops this component's room/provider references.
    public func close() async {
        if state == .closed { return }
        if state == .closing {
            await withCheckedContinuation { continuation in
                closeWaiters.append(continuation)
            }
            return
        }
        state = .closing

        if let room {
            try? await room.localParticipant.setCamera(enabled: false)
            try? await room.localParticipant.setMicrophone(enabled: false)
            await room.disconnect()
}

        self.room = nil
        keyProvider = nil
        state = .closed
        let waiters = closeWaiters
        closeWaiters.removeAll()
        waiters.forEach { $0.resume() }
    }

}
