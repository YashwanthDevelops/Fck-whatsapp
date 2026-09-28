import Combine
import AVFoundation
import AudioToolbox
import CryptoKit
import Foundation
import MatrixRustSDK
import Security
import UniformTypeIdentifiers

struct Conversation: Identifiable, Equatable {
    let id: String
    let title: String
    let preview: String
    let timestamp: UInt64
    let unreadCount: Int
    let isEncrypted: Bool
    let isGroup: Bool
}

struct ChatMessage: Identifiable, Equatable {
    let id: String
    let eventId: String?
    let isRemote: Bool
    let sender: String
    let body: String
    let timestamp: UInt64
    let isOwn: Bool
    let sendState: String
    let canRetry: Bool
    let canReply: Bool
    let replyToEventId: String?
    let reactions: [MessageReaction]
    let hasBeenRead: Bool
    let attachment: ChatAttachment?
}

enum AttachmentKind: Equatable {
    case image
    case video
    case audio
    case file
}

struct ChatAttachment: Equatable {
    let fileName: String
    let mimeType: String
    let sourceJson: String
    let kind: AttachmentKind
    let operationId: String?
}

struct MessageReaction: Identifiable, Equatable {
    var id: String { key }
    let key: String
    let count: Int
    let sentByMe: Bool
}

struct MessageSearchHit: Identifiable, Equatable {
    var id: String { "\(roomId):\(eventId)" }
    let roomId: String
    let roomTitle: String
    let eventId: String
    let sender: String
    let body: String
    let timestamp: UInt64
}

enum VerificationStep: Equatable {
    case idle, incomingRequest, waitingForPeer, comparingSas, confirming, verified, failed, cancelled
}

enum PeerTrustState: Equatable {
    case unknown, unverified, verified, changed
}

private enum RequestedClientSceneState: Equatable {
    case foreground
    case background
}

struct VerificationSasEmoji: Equatable, Identifiable, Sendable {
    var id: Int { index }
    let index: Int
    let symbol: String
    let description: String
}

private enum VerificationDelegateEvent: Sendable {
    case request(senderId: String, flowId: String, deviceId: String, displayName: String?)
    case accepted
    case sasStarted
    case emojiSas([VerificationSasEmoji])
    case decimalSas([UInt16])
    case failed
    case cancelled
    case finished
}

@MainActor
private final class AttachmentDrainRace {
    private var continuation: CheckedContinuation<Bool, Never>?

    init(_ continuation: CheckedContinuation<Bool, Never>) {
        self.continuation = continuation
    }

    func finish(_ drained: Bool) {
        guard let continuation else { return }
        self.continuation = nil
        continuation.resume(returning: drained)
    }
}

private final class MessengerVerificationDelegate: SessionVerificationControllerDelegate, @unchecked Sendable {
    private let handler: @Sendable (VerificationDelegateEvent) -> Void

    init(handler: @escaping @Sendable (VerificationDelegateEvent) -> Void) {
        self.handler = handler
    }

    func didReceiveVerificationRequest(details: SessionVerificationRequestDetails) {
        handler(.request(senderId: details.senderProfile.userId, flowId: details.flowId,
                         deviceId: details.deviceId, displayName: details.deviceDisplayName))
    }

    func didAcceptVerificationRequest() { handler(.accepted) }
    func didStartSasVerification() { handler(.sasStarted) }

    func didReceiveVerificationData(data: SessionVerificationData) {
        switch data {
        case let .emojis(emojis, indices):
            let selected = indices.enumerated().compactMap { position, value -> VerificationSasEmoji? in
                let emojiIndex = Int(value)
                guard emojis.indices.contains(emojiIndex) else { return nil }
                return VerificationSasEmoji(index: position, symbol: emojis[emojiIndex].symbol(),
                                            description: emojis[emojiIndex].description())
            }
            guard selected.count == indices.count, selected.count == 7 else {
                handler(.failed)
                return
            }
            handler(.emojiSas(selected))
        case let .decimals(values):
            guard values.count == 3 else {
                handler(.failed)
                return
            }
            handler(.decimalSas(values))
        }
    }

    func didFail() { handler(.failed) }
    func didCancel() { handler(.cancelled) }
    func didFinish() { handler(.finished) }
}

enum MessengerMediaStorage {
    static func directory() throws -> URL {
        var root = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("PrivateMessengerMedia", isDirectory: true)
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        var values = URLResourceValues()
        values.isExcludedFromBackup = true
        try root.setResourceValues(values)
        try FileManager.default.setAttributes([.protectionKey: FileProtectionType.complete], ofItemAtPath: root.path)
        return root
    }

    static func newRecordingURL() throws -> URL {
        try directory().appendingPathComponent("voice-note-\(UUID().uuidString).m4a")
    }

    static func protectPrivateFile(at url: URL) throws {
        try FileManager.default.setAttributes([.protectionKey: FileProtectionType.complete], ofItemAtPath: url.path)
        var values = URLResourceValues()
        values.isExcludedFromBackup = true
        var protectedURL = url
        try protectedURL.setResourceValues(values)
    }

    static func removeTemporaryMedia(at url: URL) {
        guard url.deletingLastPathComponent().standardizedFileURL == (try? directory().standardizedFileURL) else { return }
        try? FileManager.default.removeItem(at: url)
    }

    static func safeFileName(_ value: String) -> String {
        let leaf = URL(fileURLWithPath: value).lastPathComponent
        let safe = String(leaf.filter { $0.isLetter || $0.isNumber || " ._-()".contains($0) }.prefix(120))
        return safe.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ? "attachment" : safe
    }

    static func removeAll() {
        guard let root = try? directory() else { return }
        for item in (try? FileManager.default.contentsOfDirectory(at: root, includingPropertiesForKeys: nil)) ?? [] {
            try? FileManager.default.removeItem(at: item)
        }
    }
}

struct PendingAttachmentManifest: Codable, Equatable {
    let operationId: String
    let roomId: String
    let userId: String
    let homeserverUrl: String
    let displayFileName: String
    let audioDuration: TimeInterval?
    let sdkEventOrTransactionId: String?

    init(operationId: String, roomId: String, userId: String, homeserverUrl: String,
         displayFileName: String, audioDuration: TimeInterval?, sdkEventOrTransactionId: String? = nil) {
        self.operationId = operationId
        self.roomId = roomId
        self.userId = userId
        self.homeserverUrl = homeserverUrl
        self.displayFileName = displayFileName
        self.audioDuration = audioDuration
        self.sdkEventOrTransactionId = sdkEventOrTransactionId
    }
}

struct OutboxAttachmentFile {
    let url: URL
    let operationId: String
    let displayFileName: String
}

private enum MessengerProtectedMetadata {
    private static let keyContext = "dev.friendline.messenger.local-metadata.v1"

    static func seal(_ plaintext: Data, purpose: String) throws -> Data {
        let key = try DeviceVault().loadLocalMetadataKey()
        let box = try AES.GCM.seal(plaintext, using: SymmetricKey(data: key),
                                   authenticating: Data("\(keyContext)|\(purpose)".utf8))
        guard let combined = box.combined else { throw MessengerError.attachmentUnavailable }
        return combined
    }

    static func open(_ ciphertext: Data, purpose: String) throws -> Data {
        let box = try AES.GCM.SealedBox(combined: ciphertext)
        let key = try DeviceVault().loadLocalMetadataKey()
        return try AES.GCM.open(box, using: SymmetricKey(data: key),
                                authenticating: Data("\(keyContext)|\(purpose)".utf8))
    }
}

enum MessengerAttachmentOutbox {
    private static let folderName = "MediaOutbox"
    private static let manifestName = "pending-attachment.json"
    private static let marker = "\u{2063}"
    private static let markerAlphabet: [UnicodeScalar] = ["\u{200B}", "\u{200C}", "\u{200D}", "\u{2060}"]

    static func stagePickedFile(from source: URL) throws -> URL {
        try stageCopy(from: source, displayFileName: MessengerMediaStorage.safeFileName(source.lastPathComponent)).url
    }

    static func stageCopy(from source: URL, displayFileName: String) throws -> OutboxAttachmentFile {
        let operationId = UUID().uuidString.lowercased()
        let displayName = MessengerMediaStorage.safeFileName(displayFileName)
        let values = try source.resourceValues(forKeys: [.fileSizeKey])
        guard (values.fileSize ?? 0) <= 100 * 1024 * 1024 else { throw MessengerError.attachmentTooLarge }
        let directory = try operationDirectory(operationId: operationId, create: true)
        let staged = directory.appendingPathComponent("payload.sealed")
        do {
            let plaintext = try Data(contentsOf: source, options: [.mappedIfSafe])
            let key = try DeviceVault().loadMediaOutboxKey()
            let associatedData = Data("\(operationId)|\(displayName)".utf8)
            let sealed = try AES.GCM.seal(plaintext, using: SymmetricKey(data: key), authenticating: associatedData)
            guard let combined = sealed.combined else { throw MessengerError.attachmentUnavailable }
            try combined.write(to: staged, options: [.atomic])
            try protect(at: staged)
            let metadataURL = directory.appendingPathComponent("metadata.json")
            let metadata = try JSONEncoder().encode(OutboxMetadata(displayFileName: displayName))
            let encryptedMetadata = try MessengerProtectedMetadata.seal(
                metadata, purpose: "attachment-metadata|\(operationId)"
            )
            try encryptedMetadata.write(to: metadataURL, options: [.atomic])
            try protect(at: metadataURL)
            return OutboxAttachmentFile(url: staged, operationId: operationId, displayFileName: displayName)
        } catch {
            try? FileManager.default.removeItem(at: directory)
            throw error
        }
    }

    static func describe(_ url: URL) throws -> OutboxAttachmentFile {
        let root = try directory()
        let standardized = url.standardizedFileURL
        let operationDirectory = standardized.deletingLastPathComponent()
        guard operationDirectory.deletingLastPathComponent().standardizedFileURL == root.standardizedFileURL,
              standardized.lastPathComponent == "payload.sealed",
              UUID(uuidString: operationDirectory.lastPathComponent) != nil else {
            throw MessengerError.attachmentUnavailable
        }
        let metadataURL = operationDirectory.appendingPathComponent("metadata.json")
        let encryptedMetadata = try Data(contentsOf: metadataURL)
        let metadataPurpose = "attachment-metadata|\(operationDirectory.lastPathComponent.lowercased())"
        let metadataData: Data
        if let opened = try? MessengerProtectedMetadata.open(encryptedMetadata, purpose: metadataPurpose) {
            metadataData = opened
        } else {
            // Migrate the earlier protected-but-plaintext filename record.
            let migrated = try JSONDecoder().decode(OutboxMetadata.self, from: encryptedMetadata)
            metadataData = try JSONEncoder().encode(migrated)
            let reencrypted = try MessengerProtectedMetadata.seal(metadataData, purpose: metadataPurpose)
            try reencrypted.write(to: metadataURL, options: [.atomic])
            try protect(at: metadataURL)
        }
        let metadata = try JSONDecoder().decode(OutboxMetadata.self, from: metadataData)
        return OutboxAttachmentFile(url: standardized, operationId: operationDirectory.lastPathComponent.lowercased(),
                                    displayFileName: MessengerMediaStorage.safeFileName(metadata.displayFileName))
    }

    static func fileURL(for manifest: PendingAttachmentManifest) throws -> URL {
        let expectedDirectory = try operationDirectory(operationId: manifest.operationId, create: false)
        let url = expectedDirectory.appendingPathComponent("payload.sealed")
        guard url.deletingLastPathComponent().standardizedFileURL == expectedDirectory.standardizedFileURL,
              FileManager.default.fileExists(atPath: url.path) else {
            throw MessengerError.attachmentUnavailable
        }
        return url
    }

    static func materializeForUpload(_ manifest: PendingAttachmentManifest) throws -> URL {
        let encryptedURL = try fileURL(for: manifest)
        let sealedData = try Data(contentsOf: encryptedURL, options: [.mappedIfSafe])
        let box = try AES.GCM.SealedBox(combined: sealedData)
        let key = try DeviceVault().loadMediaOutboxKey()
        let associatedData = Data("\(manifest.operationId)|\(manifest.displayFileName)".utf8)
        let plaintext = try AES.GCM.open(box, using: SymmetricKey(data: key), authenticating: associatedData)
        let uploadDirectory = try MessengerMediaStorage.directory()
        let destination = uploadDirectory.appendingPathComponent(
            uploadFileName(operationId: manifest.operationId, displayFileName: manifest.displayFileName)
        )
        try plaintext.write(to: destination, options: [.atomic])
        try MessengerMediaStorage.protectPrivateFile(at: destination)
        return destination
    }

    static func temporaryUploadURL(for manifest: PendingAttachmentManifest) throws -> URL {
        try MessengerMediaStorage.directory().appendingPathComponent(
            uploadFileName(operationId: manifest.operationId, displayFileName: manifest.displayFileName)
        )
    }

    static func loadPending() throws -> PendingAttachmentManifest? {
        let url = try directory().appendingPathComponent(manifestName)
        guard FileManager.default.fileExists(atPath: url.path) else { return nil }
        let encryptedManifest = try Data(contentsOf: url)
        let manifestData: Data
        if let opened = try? MessengerProtectedMetadata.open(encryptedManifest, purpose: "pending-attachment-manifest") {
            manifestData = opened
        } else {
            // Migrate the earlier protected-but-plaintext minimal manifest.
            let legacy = try JSONDecoder().decode(PendingAttachmentManifest.self, from: encryptedManifest)
            manifestData = try JSONEncoder().encode(legacy)
            let reencrypted = try MessengerProtectedMetadata.seal(manifestData, purpose: "pending-attachment-manifest")
            try reencrypted.write(to: url, options: [.atomic])
            try protect(at: url)
        }
        let manifest = try JSONDecoder().decode(PendingAttachmentManifest.self, from: manifestData)
        guard UUID(uuidString: manifest.operationId) != nil,
              !manifest.roomId.isEmpty, !manifest.userId.isEmpty, !manifest.homeserverUrl.isEmpty,
              manifest.displayFileName == MessengerMediaStorage.safeFileName(manifest.displayFileName),
              (manifest.sdkEventOrTransactionId?.count ?? 0) <= 1024 else {
            throw MessengerError.attachmentUnavailable
        }
        return manifest
    }

    static func savePending(_ manifest: PendingAttachmentManifest) throws {
        let destination = try directory().appendingPathComponent(manifestName)
        let data = try JSONEncoder().encode(manifest)
        let encrypted = try MessengerProtectedMetadata.seal(data, purpose: "pending-attachment-manifest")
        try encrypted.write(to: destination, options: [.atomic])
        try protect(at: destination)
    }

    static func removePending(_ manifest: PendingAttachmentManifest) {
        do {
            let manifestURL = try directory().appendingPathComponent(manifestName)
            guard FileManager.default.fileExists(atPath: manifestURL.path),
                  let encrypted = try? Data(contentsOf: manifestURL),
                  let data = try? MessengerProtectedMetadata.open(encrypted, purpose: "pending-attachment-manifest"),
                  let stored = try? JSONDecoder().decode(PendingAttachmentManifest.self, from: data),
                  stored.operationId == manifest.operationId else { return }
            try FileManager.default.removeItem(at: manifestURL)
            removeOperationDirectory(manifest.operationId)
        } catch { return }
    }

    static func removeStagedFile(at url: URL) {
        guard let file = try? describe(url) else { return }
        removeOperationDirectory(file.operationId)
    }

    static func removeAll() {
        guard let root = try? directory() else { return }
        for item in (try? FileManager.default.contentsOfDirectory(at: root, includingPropertiesForKeys: nil)) ?? [] {
            try? FileManager.default.removeItem(at: item)
        }
    }

    static func cleanupOrphans() {
        guard let root = try? directory() else { return }
        let manifest: PendingAttachmentManifest?
        do {
            manifest = try loadPending()
        } catch {
            // Complete file protection may make the manifest unavailable until
            // first unlock. Never interpret an unreadable manifest as an empty
            // outbox and delete a user's pending encrypted upload.
            return
        }
        let keepId = manifest?.operationId.lowercased()
        for item in (try? FileManager.default.contentsOfDirectory(at: root, includingPropertiesForKeys: nil)) ?? [] {
            if item.lastPathComponent == manifestName { continue }
            if item.lastPathComponent.lowercased() != keepId { try? FileManager.default.removeItem(at: item) }
        }
        if manifest == nil {
            try? FileManager.default.removeItem(at: root.appendingPathComponent(manifestName))
        }
    }

    static func decodeUploadFileName(_ fileName: String) -> (operationId: String, displayFileName: String)? {
        parseUploadFileName(fileName)
    }

    static func hasPersistentState() -> Bool {
        let base = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("PrivateMessenger", isDirectory: true)
            .appendingPathComponent(folderName, isDirectory: true)
        return FileManager.default.fileExists(atPath: base.path)
    }

    private static func directory() throws -> URL {
        let applicationSupport = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        let root = applicationSupport.appendingPathComponent("PrivateMessenger", isDirectory: true)
            .appendingPathComponent(folderName, isDirectory: true)
        try FileManager.default.createDirectory(at: root.deletingLastPathComponent(), withIntermediateDirectories: true)
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        try protectDirectory(at: root.deletingLastPathComponent())
        try protectDirectory(at: root)
        return root
    }

    private static func operationDirectory(operationId: String, create: Bool) throws -> URL {
        guard UUID(uuidString: operationId) != nil else { throw MessengerError.attachmentUnavailable }
        let root = try directory()
        let child = root.appendingPathComponent(operationId.lowercased(), isDirectory: true)
        guard child.deletingLastPathComponent().standardizedFileURL == root.standardizedFileURL else {
            throw MessengerError.attachmentUnavailable
        }
        if create {
            try FileManager.default.createDirectory(at: child, withIntermediateDirectories: true)
            try protectDirectory(at: child)
        }
        return child
    }

    private static func removeOperationDirectory(_ operationId: String) {
        guard let child = try? operationDirectory(operationId: operationId, create: false) else { return }
        try? FileManager.default.removeItem(at: child)
    }

    private static func protectDirectory(at url: URL) throws {
        try FileManager.default.setAttributes([.protectionKey: FileProtectionType.complete], ofItemAtPath: url.path)
        var values = URLResourceValues()
        values.isExcludedFromBackup = true
        var protectedURL = url
        try protectedURL.setResourceValues(values)
    }

    private static func protect(at url: URL) throws {
        try FileManager.default.setAttributes([.protectionKey: FileProtectionType.complete], ofItemAtPath: url.path)
        var values = URLResourceValues()
        values.isExcludedFromBackup = true
        var protectedURL = url
        try protectedURL.setResourceValues(values)
    }

    private static func uploadFileName(operationId: String, displayFileName: String) -> String {
        let hex = Array(UUID(uuidString: operationId)!.uuidString.replacingOccurrences(of: "-", with: ""))
        let bytes = stride(from: 0, to: hex.count, by: 2).compactMap { offset -> UInt8? in
            UInt8(String(hex[offset..<offset + 2]), radix: 16)
        }
        let encodedMarker = bytes.flatMap { byte in
            [markerAlphabet[Int((byte >> 6) & 0x03)], markerAlphabet[Int((byte >> 4) & 0x03)],
             markerAlphabet[Int((byte >> 2) & 0x03)], markerAlphabet[Int(byte & 0x03)]]
        }
        return marker + String(String.UnicodeScalarView(encodedMarker)) + MessengerMediaStorage.safeFileName(displayFileName)
    }

    private struct OutboxMetadata: Codable {
        let displayFileName: String
    }

    private static func parseUploadFileName(_ fileName: String) -> (operationId: String, displayFileName: String)? {
        let scalars = Array(fileName.unicodeScalars)
        guard scalars.first?.value == 0x2063, scalars.count > 65 else { return nil }
        var bytes = [UInt8]()
        bytes.reserveCapacity(16)
        var offset = 1
        while offset < 65 {
            var byte: UInt8 = 0
            for _ in 0..<4 {
                guard let value = markerAlphabet.firstIndex(of: scalars[offset]) else { return nil }
                byte = (byte << 2) | UInt8(value)
                offset += 1
            }
            bytes.append(byte)
        }
        guard bytes.count == 16 else { return nil }
        let hex = bytes.map { String(format: "%02x", $0) }.joined()
        let formatted = "\(hex.prefix(8))-\(hex.dropFirst(8).prefix(4))-\(hex.dropFirst(12).prefix(4))-\(hex.dropFirst(16).prefix(4))-\(hex.dropFirst(20))"
        guard let operationId = UUID(uuidString: formatted)?.uuidString.lowercased() else { return nil }
        let displayName = MessengerMediaStorage.safeFileName(String(String.UnicodeScalarView(scalars.dropFirst(65))))
        return (operationId, displayName)
    }
}

private struct DeliveryAcknowledgementLedger: Codable, Equatable {
    var accountKey: String
    var pendingByRoom: [String: Set<String>] = [:]
    // Targets whose ACK already has a durable local echo in the Matrix SDK
    // send queue. Keep these separate from `sent`: queue insertion is not
    // server acceptance, and a queued echo can later fail.
    var queuedByRoom: [String: Set<String>] = [:]
    // Target event IDs whose ACK was confirmed by the homeserver.
    var sent: Set<String> = []
    // Target event IDs for which an ACK was received from the peer.
    var received: Set<String> = []
    var expectedMembersByRoom: [String: [String: Set<String>]] = [:]
    var acknowledgedMembersByRoom: [String: [String: Set<String>]] = [:]

    private enum CodingKeys: String, CodingKey {
        case accountKey, pendingByRoom, queuedByRoom, sent, received
        case expectedMembersByRoom, acknowledgedMembersByRoom
    }

    init(accountKey: String) {
        self.accountKey = accountKey
    }

    init(from decoder: Decoder) throws {
        let values = try decoder.container(keyedBy: CodingKeys.self)
        accountKey = try values.decode(String.self, forKey: .accountKey)
        pendingByRoom = try values.decodeIfPresent([String: Set<String>].self, forKey: .pendingByRoom) ?? [:]
        // This key was added after the initial ledger format. Existing protected
        // ledgers remain readable and are upgraded the next time they change.
        queuedByRoom = try values.decodeIfPresent([String: Set<String>].self, forKey: .queuedByRoom) ?? [:]
        sent = try values.decodeIfPresent(Set<String>.self, forKey: .sent) ?? []
        received = try values.decodeIfPresent(Set<String>.self, forKey: .received) ?? []
        expectedMembersByRoom = try values.decodeIfPresent([String: [String: Set<String>]].self, forKey: .expectedMembersByRoom) ?? [:]
        acknowledgedMembersByRoom = try values.decodeIfPresent([String: [String: Set<String>]].self, forKey: .acknowledgedMembersByRoom) ?? [:]
    }
}

func deliveryStatusLabel(
    expectedMemberIds: Set<String>,
    acknowledgedMemberIds: Set<String>,
    fallback: String = "Sent",
    legacyDelivered: Bool = false
) -> String {
    let acknowledged = acknowledgedMemberIds.intersection(expectedMemberIds)
    if expectedMemberIds.count <= 1 {
        return legacyDelivered || (!expectedMemberIds.isEmpty && acknowledged.isSuperset(of: expectedMemberIds))
            ? "Delivered"
            : fallback
    }
    guard !acknowledged.isEmpty else { return fallback }
    return acknowledged.isSuperset(of: expectedMemberIds)
        ? "Delivered to all \(expectedMemberIds.count)"
        : "Delivered to \(acknowledged.count) of \(expectedMemberIds.count)"
}

private struct SessionRecord: Codable {
    let accessToken: String
    let refreshToken: String?
    let userId: String
    let deviceId: String
    let homeserverUrl: String
    let oauthData: String?
    let slidingSyncVersion: String

    init(_ session: Session) {
        accessToken = session.accessToken
        refreshToken = session.refreshToken
        userId = session.userId
        deviceId = session.deviceId
        homeserverUrl = session.homeserverUrl
        oauthData = session.oauthData
        slidingSyncVersion = session.slidingSyncVersion == .native ? "native" : "none"
    }

    var sdkSession: Session {
        Session(
            accessToken: accessToken,
            refreshToken: refreshToken,
            userId: userId,
            deviceId: deviceId,
            homeserverUrl: homeserverUrl,
            oauthData: oauthData,
            slidingSyncVersion: slidingSyncVersion == "native" ? .native : .none
        )
    }
}

@MainActor
final class MessengerStore: ObservableObject {
    @Published var homeserver = "http://127.0.0.1:8008"
    @Published var username = ""
    @Published var password = ""
    @Published private(set) var userId: String?
    @Published private(set) var connection = "Offline"
    @Published private(set) var conversations: [Conversation] = []
    @Published private(set) var messages: [ChatMessage] = []
    @Published private(set) var typingUsers: [String] = []
    @Published private(set) var currentRoomId: String?
    @Published private(set) var currentRoomTitle = ""
    @Published private(set) var currentRoomEncrypted = false
    @Published private(set) var currentRoomIsGroup = false
    @Published private(set) var currentPeerUserId: String?
    @Published private(set) var currentPeerTrust: PeerTrustState = .unknown
    @Published private(set) var draft = ""
    @Published private(set) var replyTarget: ChatMessage?
    @Published var messageSearchQuery = ""
    @Published private(set) var searchResults: [MessageSearchHit] = []
    @Published private(set) var searchHasMore = false
    @Published private(set) var searchLoading = false
    @Published private(set) var readReceiptsEnabled = false
    @Published private(set) var isSendingAttachment = false
    @Published private(set) var pendingAttachmentURL: URL?
    @Published private(set) var pendingAttachmentDisplayName = ""
    @Published private(set) var hasPendingAttachment = false
    @Published private(set) var pendingAttachmentForCurrentRoom = false
    @Published private(set) var pendingAttachmentIsInSendQueue = false
    @Published private(set) var isRoomTimelineReady = false
    @Published private(set) var verificationStep: VerificationStep = .idle
    @Published private(set) var verificationPeer = ""
    @Published private(set) var verificationDeviceId = ""
    @Published private(set) var verificationEmojis: [VerificationSasEmoji] = []
    @Published private(set) var verificationDecimals: [UInt16] = []
    @Published private(set) var verificationIsBusy = false
    @Published private(set) var isBusy = false
    @Published private(set) var isSigningOut = false
    @Published var errorMessage: String?

    private var client: Client?
    private var syncService: SyncService?
    private var syncObserver: TaskHandle?
    private var sendQueueStatusHandle: TaskHandle?
    private var activeTimeline: Timeline?
    private var timelineObserver: TaskHandle?
    private var typingObserver: TaskHandle?
    private var refreshTask: Task<Void, Never>?
    private var draftTask: Task<Void, Never>?
    private var typingStopTask: Task<Void, Never>?
    private var phaseTransitionTask: Task<Void, Never>?
    private var foregroundResumeRetryTask: Task<Void, Never>?
    private var searchDebounceTask: Task<Void, Never>?
    private var searchService: SearchService?
    private var searchResultsHandle: TaskHandle?
    private var searchPaginationHandle: TaskHandle?
    private var searchResultsObserver: SearchResultsObserver?
    private var searchPaginationObserver: SearchPaginationObserver?
    private var lastTypingNotice = Date.distantPast
    private var deliveryAckLedger = DeliveryAcknowledgementLedger(accountKey: "")
    private var deliveryAckLedgerDirty = false
    private var deliveryAckInFlight = Set<String>()
    private var deliveryAckSendTasks: [String: Task<Void, Never>] = [:]
    private var deliveryAckRetryTasks: [String: Task<Void, Never>] = [:]
    private var deliveryAckSendHandles: [String: SendHandle] = [:]
    private var deliveryAckFailureRecoverability: [String: Bool] = [:]
    private var expectedDeliveryMemberIdsByRoom: [String: Set<String>] = [:]
    private var persistentStorageReady = false
    private var clientPausedForBackground = false
    private var syncServiceStoppedForBackground = false
    private var syncServiceReadyForSceneTransitions = false
    private var requestedClientSceneState: RequestedClientSceneState?
    private var sendQueuesEnabled = false
    private var syncGeneration = UUID()
    private var sendQueueStateRevision: UInt64 = 0
    private var sendQueueTransitionTask: Task<Void, Never>?
    private var activeClientOperations = 0
    private var clientOperationDrainWaiters: [CheckedContinuation<Void, Never>] = []
    private var logoutAttemptActive = false
    private var pendingAttachment: PendingAttachmentManifest?
    private var pendingAttachmentRoomId: String?
    private var pendingAttachmentAudioDuration: TimeInterval?
    private var attachmentOperation: Task<Void, Never>?
    private var attachmentOperationId: UUID?
    private var activeAttachmentHandle: SendAttachmentJoinHandle?
    private var verificationController: SessionVerificationController?
    private var verificationDelegate: MessengerVerificationDelegate?
    private var incomingVerificationSenderId: String?
    private var incomingVerificationFlowId: String?
    private var verificationWasInitiatedHere = false
    private let vault = DeviceVault()

    init() {
        let localStateExists = FileManager.default.fileExists(atPath: Self.applicationDataRoot().path)
        do {
            _ = try vault.ensureInstallGeneration(localStateExists: localStateExists)
            persistentStorageReady = true
        } catch {
            errorMessage = "Secure local storage could not be verified. Restart the app before signing in."
        }
        // Decrypted previews and recordings are transient. Pending uploads have a
        // separate protected outbox and survive process restarts.
        MessengerMediaStorage.removeAll()
        MessengerAttachmentOutbox.cleanupOrphans()
    }

    private func beginClientOperation() -> Bool {
        guard !isSigningOut else { return false }
        activeClientOperations += 1
        return true
    }

    private func endClientOperation() {
        guard activeClientOperations > 0 else { return }
        activeClientOperations -= 1
        guard activeClientOperations == 0 else { return }
        let waiters = clientOperationDrainWaiters
        clientOperationDrainWaiters.removeAll()
        for waiter in waiters { waiter.resume() }
    }

    private func waitForClientOperations() async {
        guard activeClientOperations > 0 else { return }
        await withCheckedContinuation { (continuation: CheckedContinuation<Void, Never>) in
            clientOperationDrainWaiters.append(continuation)
        }
    }

    private func waitForAttachmentDrain(_ task: Task<Void, Never>, timeout: Duration) async -> Bool {
        await withCheckedContinuation { (continuation: CheckedContinuation<Bool, Never>) in
            let race = AttachmentDrainRace(continuation)
            Task { @MainActor in
                await task.value
                race.finish(true)
            }
            Task { @MainActor in
                try? await Task.sleep(for: timeout)
                race.finish(false)
            }
        }
    }

    func restoreSession() async {
        guard beginClientOperation() else { return }
        defer { endClientOperation() }
        guard userId == nil, !isBusy else { return }
        guard persistentStorageReady else {
            errorMessage = "Secure local storage could not be verified. Restart the app before signing in."
            return
        }
        guard let record = try? vault.loadSession() else { return }
        readReceiptsEnabled = (try? vault.loadReadReceiptsEnabled()) ?? false
        isBusy = true
        do {
            let matrix = try await buildClient(homeserverUrl: record.homeserverUrl)
            try await matrix.restoreSession(session: record.sdkSession)
            await matrix.encryption().waitForE2eeInitializationTasks()
            try vault.saveStoreKeyIfNeeded()
            try vault.saveMediaOutboxKeyIfNeeded()
            try prepareAccountStorage(userId: record.userId, homeserverUrl: record.homeserverUrl)
            client = matrix
            userId = record.userId
            homeserver = record.homeserverUrl
            await configureVerification(matrix)
            await beginSync(matrix)
            isBusy = false
            if !isSigningOut { beginRoomRefresh() }
        } catch {
            isBusy = false
            errorMessage = "The saved session could not be opened. Sign in again to continue."
        }
    }

    func signIn() async {
        guard beginClientOperation() else { return }
        defer { endClientOperation() }
        guard persistentStorageReady else {
            errorMessage = "Secure local storage could not be verified. Restart the app before signing in."
            return
        }
        guard !username.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty,
              !password.isEmpty,
              let validatedHomeserverUrl = Self.validatedHomeserverURL(homeserver) else {
            errorMessage = "Enter a valid HTTPS homeserver, Matrix ID, and password. HTTP is available only for private development servers in debug builds."
            return
        }

        isBusy = true
        errorMessage = nil
        do {
            let matrix = try await buildClient(homeserverUrl: validatedHomeserverUrl)
            try await matrix.login(
                username: username.trimmingCharacters(in: .whitespacesAndNewlines),
                password: password,
                initialDeviceName: "Private Messenger",
                deviceId: nil
            )
            await matrix.encryption().waitForE2eeInitializationTasks()
            let session = try matrix.session()
            try vault.save(session: SessionRecord(session))
            try vault.saveMediaOutboxKeyIfNeeded()
            try prepareAccountStorage(userId: session.userId, homeserverUrl: validatedHomeserverUrl)
            readReceiptsEnabled = (try? vault.loadReadReceiptsEnabled()) ?? false
            client = matrix
            userId = session.userId
            homeserver = validatedHomeserverUrl
            password = ""
            await configureVerification(matrix)
            await beginSync(matrix)
            isBusy = false
            if !isSigningOut { beginRoomRefresh() }
        } catch {
            isBusy = false
            errorMessage = "Couldn't sign in. Check the homeserver, Matrix ID, and password, then try again."
        }
    }

    func createConversation(matrixIds: String, name: String, isGroup: Bool) async {
        guard beginClientOperation() else { return }
        defer { endClientOperation() }
        let parsedIds = matrixIds
            .split(whereSeparator: { $0 == "," || $0 == ";" || $0.isNewline })
            .map { $0.trimmingCharacters(in: .whitespacesAndNewlines) }
            .filter { !$0.isEmpty }
        var seenIds = Set<String>()
        let ids = parsedIds.filter { seenIds.insert($0).inserted }
        guard ids.allSatisfy({ $0.range(of: "^@[^:\\s]+:[^\\s]+$", options: .regularExpression) != nil }) else {
            errorMessage = "Enter complete Matrix IDs, such as @alex:example.org."
            return
        }
        guard isGroup ? ids.count >= 2 : ids.count == 1 else {
            errorMessage = isGroup
                ? "Enter at least two distinct Matrix IDs to create a group."
                : "Enter exactly one Matrix ID for a one-to-one conversation."
            return
        }
        guard let client else { return }
        if let accountUserId = userId, ids.contains(accountUserId) {
            errorMessage = "Do not include your own Matrix ID in the invitees."
            return
        }
        isBusy = true
        errorMessage = nil
        do {
            let trimmedName = name.trimmingCharacters(in: .whitespacesAndNewlines)
            let request = CreateRoomParameters(
                name: trimmedName.isEmpty ? nil : trimmedName,
                isEncrypted: true,
                isDirect: !isGroup,
                visibility: .private,
                preset: .privateChat,
                invite: ids,
                joinRuleOverride: .invite
            )
            let roomId = try await client.createRoom(request: request)
            guard let createdRoom = client.rooms().first(where: { $0.id() == roomId }),
                  createdRoom.encryptionState() == .encrypted else {
                throw MessengerError.encryptedRoomCreationFailed
            }
            await refreshConversations()
            await openConversation(roomId)
            isBusy = false
        } catch {
            isBusy = false
            errorMessage = "Couldn't create the encrypted conversation. Check the invited Matrix IDs and try again."
        }
    }

    func openConversation(_ roomId: String) async {
        guard beginClientOperation() else { return }
        defer { endClientOperation() }
        guard let client,
              let room = client.rooms().first(where: { $0.id() == roomId }) else { return }
        timelineObserver?.cancel()
        typingObserver?.cancel()
        activeTimeline?.close()
        activeTimeline = nil
        messages = []
        currentRoomId = roomId
        isRoomTimelineReady = false
        restorePendingAttachmentForCurrentRoom()
        currentRoomEncrypted = room.encryptionState() == .encrypted
        currentRoomIsGroup = false
        currentPeerUserId = nil
        currentPeerTrust = .unknown
        isBusy = true
        do {
            let info = try await room.roomInfo()
            currentRoomTitle = info.displayName ?? roomId
            currentRoomIsGroup = !info.isDirect
            let expectedMembers: Set<String>
            if currentRoomEncrypted {
                expectedMembers = try await activeHumanMemberIds(in: room)
            } else {
                expectedMembers = []
            }
            expectedDeliveryMemberIdsByRoom[roomId] = expectedMembers
            if currentRoomEncrypted, expectedMembers.count == 1, let peerUserId = expectedMembers.first {
                currentPeerUserId = peerUserId
                await refreshCurrentPeerTrust(fallbackToServer: true)
            }
            let timeline = try await room.timeline()
            activeTimeline = timeline
            let listener = TimelineObserver { [weak self] diff in
                Task { @MainActor in
                    guard let self, !self.isSigningOut else { return }
                    self.apply(diff: diff, roomId: roomId)
                }
            }
            timelineObserver = await timeline.addListener(listener: listener)
            let typing = TypingObserver { [weak self] ids in
                Task { @MainActor in self?.typingUsers = ids.filter { $0 != self?.userId } }
            }
            typingObserver = room.subscribeToTypingNotifications(listener: typing)
            if readReceiptsEnabled { try? await timeline.markAsRead(receiptType: .read) }
            draft = try await room.loadComposerDraft(threadRoot: nil)?.plainText ?? ""
            isBusy = false
        } catch {
            currentRoomId = nil
            currentRoomIsGroup = false
            expectedDeliveryMemberIdsByRoom[roomId] = nil
            pendingAttachmentURL = nil
            pendingAttachmentForCurrentRoom = false
            isBusy = false
            errorMessage = "Couldn't open this conversation. Try again after syncing."
        }
    }

    func closeConversation() {
        let roomId = currentRoomId
        let savedDraft = draft
        draftTask?.cancel()
        typingStopTask?.cancel()
        Task {
            await setTyping(false, roomId: roomId)
            await persistDraft(roomId: roomId, text: savedDraft)
        }
        timelineObserver?.cancel()
        typingObserver?.cancel()
        timelineObserver = nil
        typingObserver = nil
        activeTimeline?.close()
        activeTimeline = nil
        currentRoomId = nil
        isRoomTimelineReady = false
        pendingAttachmentURL = nil
        pendingAttachmentForCurrentRoom = false
        pendingAttachmentIsInSendQueue = false
        currentRoomTitle = ""
        currentRoomEncrypted = false
        currentRoomIsGroup = false
        currentPeerUserId = nil
        currentPeerTrust = .unknown
        messages = []
        draft = ""
        replyTarget = nil
        messageSearchQuery = ""
        typingUsers = []
    }

    func setReadReceiptsEnabled(_ enabled: Bool) {
        guard !isSigningOut else { return }
        do {
            try vault.saveReadReceiptsEnabled(enabled)
            readReceiptsEnabled = enabled
            if enabled, let activeTimeline {
                Task { @MainActor [weak self] in
                    guard let self, self.beginClientOperation() else { return }
                    defer { self.endClientOperation() }
                    try? await activeTimeline.markAsRead(receiptType: .read)
                }
            }
        } catch {
            errorMessage = "Couldn't save your privacy setting."
        }
    }

    func requestDeviceVerification() async {
        guard beginClientOperation() else { return }
        defer { endClientOperation() }
        guard let verificationController, !verificationIsBusy,
              verificationStep == .idle || verificationStep == .verified || verificationStep == .failed || verificationStep == .cancelled else { return }
        resetVerificationPresentation()
        verificationWasInitiatedHere = true
        verificationPeer = "Another device on this account"
        verificationStep = .waitingForPeer
        verificationIsBusy = true
        defer { verificationIsBusy = false }
        do { try await verificationController.requestDeviceVerification() }
        catch {
            verificationStep = .failed
            errorMessage = "Couldn't request verification. Make sure another device on this account is online."
        }
    }

    func requestPeerVerification() async {
        guard beginClientOperation() else { return }
        defer { endClientOperation() }
        guard let verificationController, let peerUserId = currentPeerUserId,
              currentRoomEncrypted, !verificationIsBusy,
              verificationStep == .idle || verificationStep == .verified || verificationStep == .failed || verificationStep == .cancelled else {
            errorMessage = "Open an encrypted one-to-one conversation to verify its other member."
            return
        }
        resetVerificationPresentation()
        verificationWasInitiatedHere = true
        verificationPeer = currentRoomTitle
        verificationDeviceId = ""
        verificationStep = .waitingForPeer
        verificationIsBusy = true
        defer { verificationIsBusy = false }
        do { try await verificationController.requestUserVerification(userId: peerUserId) }
        catch {
            verificationStep = .failed
            errorMessage = "Couldn't request verification. Ask the other person to open this conversation and accept."
        }
    }

    func respondToVerificationRequest(accept: Bool) async {
        guard beginClientOperation() else { return }
        defer { endClientOperation() }
        guard let verificationController, let senderId = incomingVerificationSenderId,
              let flowId = incomingVerificationFlowId, !verificationIsBusy else { return }
        verificationIsBusy = true
        defer { verificationIsBusy = false }
        do {
            try await verificationController.acknowledgeVerificationRequest(senderId: senderId, flowId: flowId)
            if accept {
                try await verificationController.acceptVerificationRequest()
                verificationWasInitiatedHere = false
                verificationStep = .waitingForPeer
            } else {
                try await verificationController.cancelVerification()
                verificationStep = .cancelled
            }
        } catch {
            verificationStep = .failed
            errorMessage = "Couldn't respond to the verification request. It may have expired."
        }
    }

    func confirmVerificationMatches() async {
        guard beginClientOperation() else { return }
        defer { endClientOperation() }
        guard verificationStep == .comparingSas, let verificationController, !verificationIsBusy else { return }
        verificationIsBusy = true
        verificationStep = .confirming
        defer { verificationIsBusy = false }
        do { try await verificationController.approveVerification() }
        catch {
            verificationStep = .comparingSas
            errorMessage = "Couldn't confirm this verification. Compare the codes again before retrying."
        }
    }

    func rejectVerificationMismatch() async {
        guard beginClientOperation() else { return }
        defer { endClientOperation() }
        guard verificationStep == .comparingSas, let verificationController, !verificationIsBusy else { return }
        verificationIsBusy = true
        defer { verificationIsBusy = false }
        do { try await verificationController.declineVerification() }
        catch {
            verificationStep = .failed
            errorMessage = "Couldn't reject this verification. Cancel the flow and try again."
        }
    }

    func cancelVerification() async {
        guard beginClientOperation() else { return }
        defer { endClientOperation() }
        guard let verificationController, !verificationIsBusy else { return }
        verificationIsBusy = true
        defer { verificationIsBusy = false }
        do { try await verificationController.cancelVerification() }
        catch {
            verificationStep = .failed
            errorMessage = "Couldn't cancel verification. It may have already ended."
        }
    }

    private func configureVerification(_ client: Client) async {
        do {
            let controller = try await client.getSessionVerificationController()
            let delegate = MessengerVerificationDelegate { [weak self] event in
                Task { @MainActor [weak self] in self?.handleVerificationEvent(event) }
            }
            verificationController = controller
            verificationDelegate = delegate
            controller.setDelegate(delegate: delegate)
        } catch {
            verificationController = nil
            verificationDelegate = nil
            errorMessage = "Device verification is unavailable on this connection."
        }
    }

    private func handleVerificationEvent(_ event: VerificationDelegateEvent) {
        guard !isSigningOut else { return }
        switch event {
        case let .request(senderId, flowId, deviceId, displayName):
            guard verificationStep == .idle || verificationStep == .verified || verificationStep == .failed || verificationStep == .cancelled else { return }
            resetVerificationPresentation()
            incomingVerificationSenderId = senderId
            incomingVerificationFlowId = flowId
            verificationWasInitiatedHere = false
            verificationPeer = senderId
            verificationDeviceId = deviceId
            if let displayName, !displayName.isEmpty { verificationPeer += " · \(displayName)" }
            verificationStep = .incomingRequest
        case .accepted:
            guard verificationWasInitiatedHere, let verificationController else { return }
            verificationStep = .waitingForPeer
            Task { @MainActor [weak self] in
                guard let self, self.beginClientOperation() else { return }
                defer { self.endClientOperation() }
                do { try await verificationController.startSasVerification() }
                catch {
                    self.verificationStep = .failed
                    self.errorMessage = "Couldn't start the short-code comparison."
                }
            }
        case .sasStarted:
            verificationStep = .comparingSas
        case let .emojiSas(values):
            verificationEmojis = values
            verificationDecimals = []
            verificationStep = .comparingSas
        case let .decimalSas(values):
            verificationDecimals = values
            verificationEmojis = []
            verificationStep = .comparingSas
        case .failed:
            verificationStep = .failed
        case .cancelled:
            verificationStep = .cancelled
        case .finished:
            // A successful SAS is authoritative only after the SDK reports Done.
            verificationStep = .verified
            verificationEmojis = []
            verificationDecimals = []
            Task { await refreshCurrentPeerTrust() }
        }
    }

    private func resetVerificationPresentation() {
        incomingVerificationSenderId = nil
        incomingVerificationFlowId = nil
        verificationPeer = ""
        verificationDeviceId = ""
        verificationEmojis = []
        verificationDecimals = []
        verificationStep = .idle
    }

    private func activeHumanMemberIds(in room: Room) async throws -> Set<String> {
        guard let userId else { return [] }
        let iterator = try await room.members()
        var memberIds = Set<String>()
        while let chunk = iterator.nextChunk(chunkSize: 64) {
            for member in chunk where member.userId != userId && !member.isServiceMember &&
                (member.membership == .join || member.membership == .invite) {
                memberIds.insert(member.userId)
            }
        }
        return memberIds
    }

    private func refreshCurrentPeerTrust(fallbackToServer: Bool = false) async {
        guard beginClientOperation() else { return }
        defer { endClientOperation() }
        guard let client, let peerUserId = currentPeerUserId else {
            currentPeerTrust = .unknown
            return
        }
        do {
            guard let identity = try await client.encryption().userIdentity(userId: peerUserId, fallbackToServer: fallbackToServer) else {
                currentPeerTrust = .unverified
                return
            }
            if identity.hasVerificationViolation() {
                currentPeerTrust = .changed
            } else if identity.isVerified() {
                currentPeerTrust = .verified
            } else {
                currentPeerTrust = .unverified
            }
        } catch {
            currentPeerTrust = .unverified
        }
    }

    func searchMessagesDebounced(_ query: String) {
        guard !isSigningOut else { return }
        searchDebounceTask?.cancel()
        searchDebounceTask = Task { [weak self] in
            try? await Task.sleep(for: .milliseconds(250))
            guard !Task.isCancelled else { return }
            await self?.searchMessages(query)
        }
    }

    func paginateMessageSearch() {
        guard !isSigningOut else { return }
        guard let searchService else { return }
        Task {
            guard self.beginClientOperation() else { return }
            defer { self.endClientOperation() }
            do { try await searchService.paginate() }
            catch { errorMessage = "Couldn't load more search results." }
        }
    }

    func openSearchHit(_ hit: MessageSearchHit) async {
        messageSearchQuery = ""
        await openConversation(hit.roomId)
    }

    private func searchMessages(_ query: String) async {
        guard beginClientOperation() else { return }
        defer { endClientOperation() }
        let term = query.trimmingCharacters(in: .whitespacesAndNewlines)
        guard let client else { return }
        if term.isEmpty {
            searchResults = []
            searchHasMore = false
            searchLoading = false
            if let searchService { try? await searchService.setQuery(query: "") }
            return
        }
        do {
            let service: SearchService
            if let current = searchService {
                service = current
            } else {
                let created = client.searchService()
                let resultsObserver = SearchResultsObserver { [weak self] updates in
                    Task { @MainActor [weak self] in self?.applySearchUpdates(updates) }
                }
                let paginationObserver = SearchPaginationObserver { [weak self] state in
                    Task { @MainActor [weak self] in
                        switch state {
                        case let .idle(endReached):
                            self?.searchHasMore = !endReached
                            self?.searchLoading = false
                        case .loading:
                            self?.searchLoading = true
                        }
                    }
                }
                searchResultsObserver = resultsObserver
                searchPaginationObserver = paginationObserver
                searchPaginationHandle = created.subscribeToPaginationStateUpdates(listener: paginationObserver)
                searchResultsHandle = await created.subscribeToResults(listener: resultsObserver)
                searchService = created
                service = created
            }
            searchResults = []
            searchLoading = true
            try await service.setQuery(query: term)
        } catch {
            searchLoading = false
            errorMessage = "Couldn't search your encrypted message index."
        }
    }

    private func applySearchUpdates(_ updates: [SearchServiceResultsUpdate]) {
        var rows = searchResults
        for update in updates {
            switch update {
            case let .append(values): rows.append(contentsOf: values.compactMap(makeSearchHit))
            case .clear: rows.removeAll()
            case let .pushFront(value): if let hit = makeSearchHit(value) { rows.insert(hit, at: 0) }
            case let .pushBack(value): if let hit = makeSearchHit(value) { rows.append(hit) }
            case .popFront: if !rows.isEmpty { rows.removeFirst() }
            case .popBack: if !rows.isEmpty { rows.removeLast() }
            case let .insert(index, value):
                if let hit = makeSearchHit(value) { rows.insert(hit, at: min(Int(index), rows.count)) }
            case let .set(index, value):
                if Int(index) < rows.count, let hit = makeSearchHit(value) { rows[Int(index)] = hit }
            case let .remove(index): if Int(index) < rows.count { rows.remove(at: Int(index)) }
            case let .truncate(length): rows = Array(rows.prefix(Int(length)))
            case let .reset(values): rows = values.compactMap(makeSearchHit)
            }
        }
        var seen = Set<String>()
        searchResults = rows.filter { seen.insert($0.id).inserted }
    }

    private func makeSearchHit(_ result: SearchServiceResult) -> MessageSearchHit? {
        guard case let .message(roomId, message) = result else { return nil }
        let body = message.content.previewText.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !body.isEmpty else { return nil }
        return MessageSearchHit(
            roomId: roomId,
            roomTitle: conversations.first(where: { $0.id == roomId })?.title ?? "Private conversation",
            eventId: message.eventId,
            sender: message.sender,
            body: body,
            timestamp: message.timestamp
        )
    }

    private func closeSearchService() {
        searchResultsHandle?.cancel()
        searchPaginationHandle?.cancel()
        searchResultsHandle = nil
        searchPaginationHandle = nil
        searchResultsObserver = nil
        searchPaginationObserver = nil
        searchService = nil
        searchResults = []
        searchHasMore = false
        searchLoading = false
    }

    func updateDraft(_ value: String) {
        guard !isSigningOut else { return }
        draft = value
        let roomId = currentRoomId
        typingStopTask?.cancel()
        if value.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
            Task { await setTyping(false, roomId: roomId) }
        } else {
            if Date().timeIntervalSince(lastTypingNotice) >= 2.5 {
                lastTypingNotice = Date()
                Task { await setTyping(true, roomId: roomId) }
            }
            typingStopTask = Task { [weak self] in
                try? await Task.sleep(for: .seconds(4))
                guard !Task.isCancelled else { return }
                await self?.setTyping(false, roomId: roomId)
            }
        }
        draftTask?.cancel()
        draftTask = Task { [weak self] in
            try? await Task.sleep(for: .milliseconds(350))
            guard !Task.isCancelled else { return }
            await self?.persistDraft()
        }
    }

    func setReplyTarget(_ message: ChatMessage?) {
        guard message == nil || (message?.canReply == true && message?.eventId != nil) else { return }
        replyTarget = message
    }

    func toggleReaction(_ message: ChatMessage, key: String) async {
        guard beginClientOperation() else { return }
        defer { endClientOperation() }
        guard let roomId = currentRoomId,
              let room = client?.rooms().first(where: { $0.id() == roomId }),
              let timeline = activeTimeline,
              room.encryptionState() == .encrypted else { return }
        let identifier: EventOrTransactionId = message.isRemote
            ? .eventId(eventId: message.eventId ?? message.id)
            : .transactionId(transactionId: message.id)
        do { _ = try await timeline.toggleReaction(itemId: identifier, key: key) }
        catch { errorMessage = "Couldn't send that encrypted reaction. Try again after syncing." }
    }

    func sendMessage(_ text: String) async {
        guard beginClientOperation() else { return }
        defer { endClientOperation() }
        let body = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !body.isEmpty, let roomId = currentRoomId,
              let client,
              let room = client.rooms().first(where: { $0.id() == roomId }),
              let timeline = activeTimeline else { return }
        guard room.encryptionState() == .encrypted else {
            errorMessage = "Sending is disabled because this conversation is not encrypted."
            return
        }
        errorMessage = nil
        let replyEventId = replyTarget?.eventId
        do {
            let text = TextMessageContent(body: body, formatted: nil)
            guard let content = timeline.createMessageContent(msgType: .text(content: text)) else {
                throw MessengerError.messageUnavailable
            }
            if let replyEventId {
                _ = try await timeline.sendReply(msg: content, eventId: replyEventId)
            } else {
                _ = try await timeline.send(msg: content)
            }
            replyTarget = nil
            await setTyping(false, roomId: roomId)
            try await room.clearComposerDraft(threadRoot: nil)
            draft = ""
            UIImpactFeedbackGenerator(style: .light).impactOccurred()
            await refreshConversations()
        } catch {
            draft = body
            errorMessage = "Couldn't send this message. It is still in the composer; try again."
        }
    }

    func sendAttachment(fileURL: URL, forRoomId expectedRoomId: String, audioDuration: TimeInterval? = nil) async {
        guard beginClientOperation() else {
            if pendingAttachmentURL != fileURL { MessengerAttachmentOutbox.removeStagedFile(at: fileURL) }
            return
        }
        defer { endClientOperation() }
        guard !isSendingAttachment else {
            if pendingAttachmentURL != fileURL { MessengerAttachmentOutbox.removeStagedFile(at: fileURL) }
            return
        }
        guard currentRoomId == expectedRoomId,
              let room = client?.rooms().first(where: { $0.id() == expectedRoomId }),
              room.encryptionState() == .encrypted else {
            if fileURL.lastPathComponent == "payload.sealed",
               let staged = try? MessengerAttachmentOutbox.describe(fileURL),
               pendingAttachment?.operationId != staged.operationId {
                MessengerAttachmentOutbox.removeStagedFile(at: fileURL)
            }
            errorMessage = "Attachments can only be sent in an encrypted conversation."
            return
        }

        let staged: OutboxAttachmentFile
        do {
            if fileURL.lastPathComponent == "payload.sealed" {
                staged = try MessengerAttachmentOutbox.describe(fileURL)
            } else {
                let displayName = audioDuration == nil ? fileURL.lastPathComponent : "Voice note.m4a"
                staged = try MessengerAttachmentOutbox.stageCopy(from: fileURL, displayFileName: displayName)
                MessengerMediaStorage.removeTemporaryMedia(at: fileURL)
            }
        } catch MessengerError.attachmentTooLarge {
            errorMessage = "This attachment is larger than the 100 MB app limit."
            return
        } catch {
            errorMessage = "That attachment could not be saved securely on this device."
            return
        }

        if let pendingAttachment, pendingAttachment.operationId != staged.operationId {
            errorMessage = "Retry or discard the pending attachment before choosing another one."
            MessengerAttachmentOutbox.removeStagedFile(at: staged.url)
            return
        }

        let manifest: PendingAttachmentManifest
        if let pendingAttachment, pendingAttachment.operationId == staged.operationId {
            guard pendingAttachment.roomId == expectedRoomId else {
                errorMessage = "This attachment is saved for a different conversation."
                return
            }
            manifest = pendingAttachment
        } else {
            guard let userId else {
                MessengerAttachmentOutbox.removeStagedFile(at: staged.url)
                errorMessage = "Sign in again before sending an attachment."
                return
            }
            let created = PendingAttachmentManifest(
                operationId: staged.operationId,
                roomId: expectedRoomId,
                userId: userId,
                homeserverUrl: homeserver,
                displayFileName: staged.displayFileName,
                audioDuration: audioDuration
            )
            do {
                try MessengerAttachmentOutbox.savePending(created)
                manifest = created
            } catch {
                MessengerAttachmentOutbox.removeStagedFile(at: staged.url)
                errorMessage = "The attachment could not be saved for a safe retry."
                return
            }
        }

        pendingAttachment = manifest
        pendingAttachmentRoomId = manifest.roomId
        pendingAttachmentAudioDuration = manifest.audioDuration
        pendingAttachmentDisplayName = manifest.displayFileName
        hasPendingAttachment = true
        pendingAttachmentURL = manifest.roomId == currentRoomId ? staged.url : nil
        pendingAttachmentForCurrentRoom = manifest.roomId == currentRoomId
        guard manifest.roomId == expectedRoomId else { return }
        if pendingAttachmentIsInSendQueue {
            retryFailedMessages()
            return
        }

        isSendingAttachment = true
        let operationId = UUID()
        let operation = Task { @MainActor [weak self] in
            await self?.performAttachmentSend(manifest: manifest)
        }
        attachmentOperation = operation
        attachmentOperationId = operationId
        await operation.value
        isSendingAttachment = false
        if attachmentOperationId == operationId {
            attachmentOperation = nil
            attachmentOperationId = nil
        }
    }

    private func performAttachmentSend(manifest: PendingAttachmentManifest) async {
        let roomId = manifest.roomId
        guard !pendingAttachmentIsInSendQueue,
              currentRoomId == roomId,
              let client,
              let room = client.rooms().first(where: { $0.id() == roomId }),
              room.encryptionState() == .encrypted,
              let timeline = activeTimeline else { return }

        isSendingAttachment = true
        pendingAttachmentURL = try? MessengerAttachmentOutbox.fileURL(for: manifest)
        errorMessage = nil
        var uploadURL: URL?
        var eventQueued = false
        var didSend = false
        defer {
            isSendingAttachment = false
            activeAttachmentHandle = nil
            if didSend {
                clearPendingAttachment(manifest)
            } else if !eventQueued, let uploadURL {
                MessengerMediaStorage.removeTemporaryMedia(at: uploadURL)
            }
        }

        do {
            try Task.checkCancellation()
            let uploadSource = try MessengerAttachmentOutbox.materializeForUpload(manifest)
            uploadURL = uploadSource
            let values = try uploadSource.resourceValues(forKeys: [.fileSizeKey])
            let byteCount = UInt64(max(0, values.fileSize ?? 0))
            let serverLimit = try await client.getMaxMediaUploadSize()
            try Task.checkCancellation()
            let limit = min(serverLimit > 0 ? serverLimit : 100 * 1024 * 1024, 100 * 1024 * 1024)
            guard byteCount <= limit else { throw MessengerError.attachmentTooLarge }

            let mimeType = UTType(filenameExtension: uploadSource.pathExtension)?.preferredMIMEType
                ?? "application/octet-stream"
            let parameters = UploadParameters(
                source: .file(filename: uploadSource.path),
                caption: nil,
                formattedCaption: nil,
                mentions: nil,
                inReplyTo: replyTarget?.eventId
            )

            if mimeType.lowercased().hasPrefix("image/") {
                let info = ImageInfo(height: nil, width: nil, mimetype: mimeType, size: byteCount,
                                     thumbnailInfo: nil, thumbnailSource: nil, blurhash: nil, isAnimated: nil)
                let handle = try timeline.sendImage(params: parameters, thumbnailSource: nil, imageInfo: info)
                eventQueued = true
                pendingAttachmentIsInSendQueue = true
                activeAttachmentHandle = handle
                try await handle.join()
            } else if mimeType.lowercased().hasPrefix("video/") {
                let info = VideoInfo(duration: nil, height: nil, width: nil, mimetype: mimeType, size: byteCount,
                                     thumbnailInfo: nil, thumbnailSource: nil, blurhash: nil)
                let handle = try timeline.sendVideo(params: parameters, thumbnailSource: nil, videoInfo: info)
                eventQueued = true
                pendingAttachmentIsInSendQueue = true
                activeAttachmentHandle = handle
                try await handle.join()
            } else if mimeType.lowercased().hasPrefix("audio/") {
                let info = AudioInfo(duration: manifest.audioDuration, size: byteCount, mimetype: mimeType)
                let handle = try timeline.sendAudio(params: parameters, audioInfo: info)
                eventQueued = true
                pendingAttachmentIsInSendQueue = true
                activeAttachmentHandle = handle
                try await handle.join()
            } else {
                let info = FileInfo(mimetype: mimeType, size: byteCount,
                                    thumbnailInfo: nil, thumbnailSource: nil)
                let handle = try timeline.sendFile(params: parameters, fileInfo: info)
                eventQueued = true
                pendingAttachmentIsInSendQueue = true
                activeAttachmentHandle = handle
                try await handle.join()
            }

            try Task.checkCancellation()
            didSend = true
            replyTarget = nil
            try? await room.clearComposerDraft(threadRoot: nil)
            await refreshConversations()
        } catch MessengerError.attachmentTooLarge {
            errorMessage = "This attachment is larger than the homeserver upload limit (up to 100 MB)."
            if !eventQueued { clearPendingAttachment(manifest) }
        } catch {
            if Task.isCancelled {
                errorMessage = "Attachment sending was cancelled."
            } else {
                errorMessage = "Couldn't send this attachment. It is saved on this device; retry when connected."
            }
        }
    }

    func retryPendingAttachment() {
        guard !isSigningOut else { return }
        guard let manifest = pendingAttachment, pendingAttachmentURL != nil,
              currentRoomId == manifest.roomId, isRoomTimelineReady else { return }
        if pendingAttachmentIsInSendQueue {
            retryFailedMessages()
            return
        }
        guard let encryptedURL = try? MessengerAttachmentOutbox.fileURL(for: manifest) else { return }
        Task {
            await sendAttachment(fileURL: encryptedURL, forRoomId: manifest.roomId,
                                 audioDuration: manifest.audioDuration)
        }
    }

    func discardPendingAttachment() {
        guard let pendingAttachment, !pendingAttachmentIsInSendQueue else { return }
        MessengerAttachmentOutbox.removePending(pendingAttachment)
        if let uploadURL = try? MessengerAttachmentOutbox.temporaryUploadURL(for: pendingAttachment) {
            MessengerMediaStorage.removeTemporaryMedia(at: uploadURL)
        }
        self.pendingAttachment = nil
        pendingAttachmentURL = nil
        pendingAttachmentForCurrentRoom = false
        pendingAttachmentRoomId = nil
        pendingAttachmentAudioDuration = nil
        pendingAttachmentDisplayName = ""
        pendingAttachmentIsInSendQueue = false
        hasPendingAttachment = false
    }

    func handleScenePhase(isActive: Bool, isBackground: Bool) {
        guard !isSigningOut else { return }
        if isBackground {
            requestedClientSceneState = .background
            sendQueuesEnabled = false
        } else if isActive {
            requestedClientSceneState = .foreground
        } else {
            return
        }
        scheduleClientSceneTransition()
    }

    private func scheduleClientSceneTransition() {
        guard phaseTransitionTask == nil, !isSigningOut,
              let client, syncService != nil, syncServiceReadyForSceneTransitions,
              requestedClientSceneState != nil else { return }
        let generation = syncGeneration
        phaseTransitionTask = Task { @MainActor [weak self] in
            guard let self else { return }
            guard self.beginClientOperation() else {
                self.phaseTransitionTask = nil
                return
            }
            defer {
                self.endClientOperation()
                self.phaseTransitionTask = nil
                if self.syncGeneration != generation {
                    self.scheduleClientSceneTransition()
                }
            }
            await self.reconcileClientSceneState(client: client, generation: generation)
        }
    }

    // Scene notifications can arrive while SDK calls are suspended. Keep only
    // the latest requested state, finish the current safe transition, then
    // reconcile again. Background order is stop sync -> disable queues -> pause;
    // foreground order is resume -> restart sync. Never cancel a transition to
    // start its opposite halfway through that ordering.
    private func reconcileClientSceneState(client: Client, generation: UUID) async {
        while !Task.isCancelled, generation == syncGeneration, !isSigningOut {
            guard let requested = requestedClientSceneState else { return }
            switch requested {
            case .background:
                if !syncServiceStoppedForBackground {
                    refreshTask?.cancel()
                    refreshTask = nil
                    await syncService?.stop()
                    guard generation == syncGeneration else { return }
                    syncServiceStoppedForBackground = true
                    guard !isSigningOut else { return }
                }

                guard await disableSendQueuesAndWait(client: client, generation: generation) else { return }

                if !clientPausedForBackground {
                    do {
                        try await client.pause()
                        // Record the completed SDK operation even if sign-out
                        // began while it was suspended, so logout recovery knows
                        // whether this client must be resumed.
                        guard generation == syncGeneration else { return }
                        clientPausedForBackground = true
                        connection = "Offline"
                    } catch {
                        connection = "Reconnecting"
                        if requestedClientSceneState == .foreground { continue }
                        return
                    }
                }

                guard !isSigningOut else { return }
                if requestedClientSceneState == .background { return }

            case .foreground:
                guard clientPausedForBackground || syncServiceStoppedForBackground else { return }
                sendQueuesEnabled = false
                do {
                    if clientPausedForBackground {
                        try await client.resume()
                        guard generation == syncGeneration else { return }
                        clientPausedForBackground = false
                    }
                    guard !isSigningOut else { return }

                    if syncServiceStoppedForBackground {
                        syncServiceStoppedForBackground = false
                        await syncService?.start()
                        guard generation == syncGeneration else { return }
                    }
                    guard !isSigningOut else { return }
                    connection = "Syncing"
                    await refreshConversations()
                    guard generation == syncGeneration, !isSigningOut else { return }
                    beginRoomRefresh()
                } catch {
                    connection = "Reconnecting"
                    if requestedClientSceneState == .background { continue }
                    scheduleForegroundResumeRetry(generation: generation)
                    return
                }

                if requestedClientSceneState == .foreground { return }
            }
        }
    }

    private func disableSendQueuesAndWait(client: Client, generation: UUID) async -> Bool {
        enqueueSendQueueTransition(enable: false, client: client, generation: generation)
        while generation == syncGeneration, !isSigningOut {
            let revision = sendQueueStateRevision
            let transition = sendQueueTransitionTask
            await transition?.value
            guard generation == syncGeneration, !isSigningOut else { return false }
            if revision == sendQueueStateRevision, !sendQueuesEnabled { return true }
        }
        return false
    }

    private func scheduleForegroundResumeRetry(generation: UUID) {
        guard foregroundResumeRetryTask == nil else { return }
        foregroundResumeRetryTask = Task { @MainActor [weak self] in
            guard let self else { return }
            defer { self.foregroundResumeRetryTask = nil }

            for attempt in 0..<3 {
                try? await Task.sleep(for: .seconds(Int64(1 << attempt)))
                guard !Task.isCancelled, self.syncGeneration == generation,
                      !self.isSigningOut, self.requestedClientSceneState == .foreground else { return }

                if let transition = self.phaseTransitionTask { await transition.value }
                guard !Task.isCancelled, self.syncGeneration == generation,
                      !self.isSigningOut, self.requestedClientSceneState == .foreground else { return }
                guard self.clientPausedForBackground || self.syncServiceStoppedForBackground else { return }

                self.scheduleClientSceneTransition()
                if let transition = self.phaseTransitionTask { await transition.value }
                guard !Task.isCancelled, self.syncGeneration == generation,
                      !self.isSigningOut, self.requestedClientSceneState == .foreground else { return }
                if !self.clientPausedForBackground && !self.syncServiceStoppedForBackground { return }
            }
        }
    }

    func loadAttachmentForViewing(_ attachment: ChatAttachment) async -> URL? {
        guard beginClientOperation() else { return nil }
        defer { endClientOperation() }
        guard let client else { return nil }
        var stagedDestination: URL?
        do {
            let root = try MessengerMediaStorage.directory()
            let source = try MediaSource.fromJson(json: attachment.sourceJson)
            let handle = try await client.getMediaFile(
                mediaSource: source,
                filename: attachment.fileName,
                mimeType: attachment.mimeType,
                useCache: false,
                tempDir: root.path
            )
            let sourceURL = URL(fileURLWithPath: try handle.path())
            let destination = root.appendingPathComponent("view-\(UUID().uuidString)-\(MessengerMediaStorage.safeFileName(attachment.fileName))")
            stagedDestination = destination
            try FileManager.default.copyItem(at: sourceURL, to: destination)
            try MessengerMediaStorage.protectPrivateFile(at: destination)
            withExtendedLifetime(handle) { }
            return destination
        } catch {
            if let stagedDestination { MessengerMediaStorage.removeTemporaryMedia(at: stagedDestination) }
            errorMessage = "Couldn't decrypt or open this attachment. Check that this device is trusted."
            return nil
        }
    }

    func removeTemporaryMedia(at url: URL) {
        MessengerMediaStorage.removeTemporaryMedia(at: url)
    }

    func retryFailedMessages() {
        guard !isSigningOut else { return }
        guard let roomId = currentRoomId,
              sendQueuesEnabled, connection == "Connected", !clientPausedForBackground,
              let room = client?.rooms().first(where: { $0.id() == roomId }),
              room.encryptionState() == .encrypted else { return }
        room.enableSendQueue(enable: true)
    }

    func logout() async {
        guard !logoutAttemptActive else { return }
        logoutAttemptActive = true
        isSigningOut = true
        defer {
            isSigningOut = false
            logoutAttemptActive = false
        }

        let retainedClient = client
        let retainedSyncService = syncService
        refreshTask?.cancel()
        draftTask?.cancel()
        typingStopTask?.cancel()
        searchDebounceTask?.cancel()
        foregroundResumeRetryTask?.cancel()
        if let foregroundResumeRetryTask { await foregroundResumeRetryTask.value }

        // Keep the SDK queue and sync worker live until in-flight native sends
        // drain; isSigningOut already blocks new app-owned sends.
        let attachmentTask = attachmentOperation
        let attachmentHandle = activeAttachmentHandle
        if let attachmentTask {
            guard await waitForAttachmentDrain(attachmentTask, timeout: .seconds(30)) else {
                errorMessage = "Attachment sending is still finishing. Secure sign out is paused; keep this session open and retry after it completes."
                await resumeLiveSessionAfterFailedLogout(
                    client: client ?? retainedClient,
                    service: syncService ?? retainedSyncService,
                    restartIfRunning: true
                )
                return
            }
        } else if attachmentHandle != nil {
            errorMessage = "Attachment sending could not be safely confirmed. Secure sign out is paused; keep this session open and retry after it completes."
            await resumeLiveSessionAfterFailedLogout(
                client: client ?? retainedClient,
                service: syncService ?? retainedSyncService,
                restartIfRunning: true
            )
            return
        }

        let ackSendTasks = Array(deliveryAckSendTasks.values)
        let ackRetryTasks = Array(deliveryAckRetryTasks.values)
        for task in ackRetryTasks { task.cancel() }
        for task in ackSendTasks + ackRetryTasks { await task.value }

        if let refreshTask { await refreshTask.value }
        if let draftTask { await draftTask.value }
        if let typingStopTask { await typingStopTask.value }
        if let phaseTransitionTask { await phaseTransitionTask.value }
        if let searchDebounceTask { await searchDebounceTask.value }
        await waitForClientOperations()

        // Stop sync only after app-owned operations and the attachment task's
        // uncancelled SDK join have drained.
        let clientToStop = client ?? retainedClient
        let serviceToStop = syncService ?? retainedSyncService
        sendQueueStateRevision &+= 1
        sendQueuesEnabled = false
        await sendQueueTransitionTask?.value
        if let clientToStop { await clientToStop.enableAllSendQueues(enable: false) }
        await sendQueueTransitionTask?.value
        await serviceToStop?.stop()

        do {
            try vault.clear()
        } catch {
            if let vaultError = error as? VaultError, case .clearRollbackFailed = vaultError {
                persistentStorageReady = false
            }
            errorMessage = "Secure sign out could not complete. The current app session remains open; keep the app open and retry when secure storage is available."
            await resumeLiveSessionAfterFailedLogout(client: clientToStop, service: serviceToStop)
            return
        }

        if let verificationController {
            try? await verificationController.cancelVerification()
            verificationController.setDelegate(delegate: nil)
        }
        verificationController = nil
        verificationDelegate = nil
        resetVerificationPresentation()
        verificationWasInitiatedHere = false
        self.attachmentOperation = nil
        attachmentOperationId = nil
        activeAttachmentHandle = nil
        isSendingAttachment = false
        pendingAttachment = nil
        pendingAttachmentURL = nil
        pendingAttachmentForCurrentRoom = false
        pendingAttachmentRoomId = nil
        pendingAttachmentAudioDuration = nil
        pendingAttachmentDisplayName = ""
        pendingAttachmentIsInSendQueue = false
        hasPendingAttachment = false
        closeSearchService()
        timelineObserver?.cancel()
        syncObserver?.cancel()
        sendQueueStatusHandle?.cancel()
        deliveryAckSendTasks.removeAll()
        deliveryAckRetryTasks.removeAll()
        deliveryAckInFlight.removeAll()
        deliveryAckSendHandles.removeAll()
        deliveryAckFailureRecoverability.removeAll()
        typingObserver?.cancel()
        activeTimeline?.close()
        do { try await client?.logout() } catch { }
        syncService = nil
        syncServiceStoppedForBackground = false
        syncServiceReadyForSceneTransitions = false
        clientPausedForBackground = false
        activeTimeline = nil
        client = nil
        currentRoomId = nil
        expectedDeliveryMemberIdsByRoom.removeAll()
        currentRoomIsGroup = false
        isRoomTimelineReady = false
        userId = nil
        conversations = []
        messages = []
        typingUsers = []
        replyTarget = nil
        searchResults = []
        searchHasMore = false
        searchLoading = false
        deliveryAckLedger = DeliveryAcknowledgementLedger(accountKey: "")
        deliveryAckLedgerDirty = false
        deliveryAckSendHandles.removeAll()
        deliveryAckFailureRecoverability.removeAll()
        connection = "Offline"
        MessengerMediaStorage.removeAll()
        MessengerAttachmentOutbox.removeAll()
        readReceiptsEnabled = false
        do {
            try FileManager.default.removeItem(at: Self.applicationDataRoot())
        } catch let error as CocoaError where error.code == .fileNoSuchFile {
            // Already empty.
        } catch {
            // Do not permit this process to create a new crypto key for an old
            // database. On next launch the sentinel mismatch forces a full wipe.
            vault.invalidateInstallGenerationDefault()
            persistentStorageReady = false
            errorMessage = "You are signed out, but secure local data cleanup needs verification. Restart the app before signing in again."
            return
        }
        do {
            _ = try vault.ensureInstallGeneration(localStateExists: false)
            persistentStorageReady = true
        } catch {
            persistentStorageReady = false
            errorMessage = "You are signed out, but secure local storage could not be reinitialized. Restart the app before signing in again."
        }
    }

    private func resumeLiveSessionAfterFailedLogout(
        client: Client?,
        service: SyncService?,
        restartIfRunning: Bool = false
    ) async {
        if let phaseTransitionTask { await phaseTransitionTask.value }
        guard let client else {
            isSigningOut = false
            return
        }
        if clientPausedForBackground {
            do {
                try await client.resume()
                clientPausedForBackground = false
            } catch {
                connection = "Reconnecting"
                isSigningOut = false
                return
            }
        }

        connection = "Syncing"
        if let service {
            if restartIfRunning {
                await service.stop()
                syncServiceStoppedForBackground = true
            }
            syncServiceStoppedForBackground = false
            isSigningOut = false
            await service.start()
            syncServiceReadyForSceneTransitions = true
        } else {
            isSigningOut = false
            await beginSync(client)
        }
        scheduleClientSceneTransition()
        beginRoomRefresh()
    }

    private func buildClient(homeserverUrl: String) async throws -> Client {
        guard let validatedHomeserverUrl = Self.validatedHomeserverURL(homeserverUrl) else {
            throw MessengerError.insecureHomeserver
        }
        try vault.saveStoreKeyIfNeeded()
        try vault.saveMediaOutboxKeyIfNeeded()
        try vault.saveLocalMetadataKeyIfNeeded()
        let root = try storageRoot()
        let cache = root.appendingPathComponent("Cache", isDirectory: true)
        var search = root.appendingPathComponent("SearchIndex", isDirectory: true)
        try FileManager.default.createDirectory(at: search, withIntermediateDirectories: true)
        var searchValues = URLResourceValues()
        searchValues.isExcludedFromBackup = true
        try search.setResourceValues(searchValues)
        let storeKey = try vault.loadStoreKey()
        let store = SqliteStoreBuilder(dataPath: root.appendingPathComponent("matrix.db").path,
                                       cachePath: cache.appendingPathComponent("matrix-cache.db").path)
            .key(key: storeKey)
        let matrix = try await ClientBuilder()
            .homeserverUrl(url: validatedHomeserverUrl)
            .autoEnableCrossSigning(autoEnableCrossSigning: true)
            .slidingSyncVersionBuilder(versionBuilder: .native)
            .roomKeyRecipientStrategy(strategy: .onlyTrustedDevices)
            .withSearchIndexStore(path: search.path, password: storeKey.base64EncodedString())
            .sqliteStore(config: store)
            .build()
        matrix.enableAutomaticBackpagination()
        return matrix
    }

    private static func validatedHomeserverURL(_ value: String) -> String? {
        let normalized = value.trimmingCharacters(in: .whitespacesAndNewlines)
            .trimmingCharacters(in: CharacterSet(charactersIn: "/"))
        guard let components = URLComponents(string: normalized),
              let scheme = components.scheme?.lowercased(),
              let host = components.host?.lowercased(),
              components.user == nil, components.password == nil,
              components.query == nil, components.fragment == nil,
              components.url != nil,
              components.port.map({ (1...65_535).contains($0) }) ?? true else { return nil }
        if scheme == "https" { return normalized }
        #if DEBUG
        if scheme == "http", isDevelopmentHomeserverHost(host) { return normalized }
        #endif
        return nil
    }

    private static func isDevelopmentHomeserverHost(_ host: String) -> Bool {
        if host == "localhost" || host.hasSuffix(".localhost") || host == "::1" { return true }
        let labels = host.split(separator: ".")
        guard labels.count == 4 else { return false }
        let octets = labels.compactMap { Int($0) }
        guard octets.count == 4, octets.allSatisfy({ (0...255).contains($0) }) else { return false }
        return octets[0] == 10 || octets[0] == 127 || (octets[0] == 192 && octets[1] == 168) ||
            (octets[0] == 172 && (16...31).contains(octets[1]))
    }

    private func beginSync(_ client: Client) async {
        let generation = UUID()
        syncGeneration = generation
        syncServiceReadyForSceneTransitions = false
        sendQueueStateRevision &+= 1
        sendQueuesEnabled = false
        await sendQueueTransitionTask?.value
        guard syncGeneration == generation else { return }
        // Restored Matrix SDK queues may contain unfinished sends. Keep them
        // disabled until this client's sync service reports a live connection.
        await client.enableAllSendQueues(enable: false)
        guard syncGeneration == generation else { return }
        sendQueueStatusHandle?.cancel()
        sendQueueStatusHandle = client.subscribeToSendQueueStatus(listener: SendQueueErrorObserver())
        do {
            let service = try await client.syncService().finish()
            guard syncGeneration == generation else { return }
            syncService = service
            syncServiceStoppedForBackground = false
            let observer = SyncObserver { [weak self] state in
                Task { @MainActor in
                    guard let self, self.syncGeneration == generation, !self.isSigningOut else { return }
                    switch state {
                    case .running:
                        guard self.requestedClientSceneState != .background,
                              !self.clientPausedForBackground,
                              !self.syncServiceStoppedForBackground else {
                            self.connection = self.clientPausedForBackground ? "Offline" : "Syncing"
                            self.enqueueSendQueueTransition(enable: false, client: client, generation: generation)
                            return
                        }
                        self.connection = "Connected"
                        self.enqueueSendQueueTransition(enable: true, client: client, generation: generation)
                        if let roomId = self.currentRoomId {
                            self.queuePendingDeliveryAcknowledgements(in: roomId)
                            self.retryFailedDeliveryAcknowledgements(in: roomId)
                        }
                        if self.readReceiptsEnabled, let timeline = self.activeTimeline {
                            Task { @MainActor [weak self] in
                                guard let self, self.beginClientOperation() else { return }
                                defer { self.endClientOperation() }
                                try? await timeline.markAsRead(receiptType: .read)
                            }
                        }
                    case .offline, .error, .terminated:
                        self.connection = self.clientPausedForBackground ? "Offline" : "Reconnecting"
                        self.enqueueSendQueueTransition(enable: false, client: client, generation: generation)
                    case .idle:
                        self.connection = self.clientPausedForBackground ? "Offline" : "Syncing"
                        self.enqueueSendQueueTransition(enable: false, client: client, generation: generation)
                    }
                }
            }
            syncObserver = service.state(listener: observer)
            connection = "Syncing"
            await service.start()
            guard syncGeneration == generation else { return }
            syncServiceReadyForSceneTransitions = true
            // A scene callback can arrive before this service exists. Reconcile
            // the latest requested state now that stop/start is available.
            scheduleClientSceneTransition()
            await refreshConversations()
        } catch {
            if syncGeneration == generation { syncServiceReadyForSceneTransitions = false }
            if syncGeneration == generation {
                connection = "Reconnecting"
                enqueueSendQueueTransition(enable: false, client: client, generation: generation)
            }
        }
    }

    private func enqueueSendQueueTransition(enable: Bool, client: Client, generation: UUID) {
        sendQueueStateRevision &+= 1
        let revision = sendQueueStateRevision
        if !enable { sendQueuesEnabled = false }
        let previous = sendQueueTransitionTask
        sendQueueTransitionTask = Task { @MainActor [weak self] in
            await previous?.value
            guard let self, self.syncGeneration == generation,
                  self.sendQueueStateRevision == revision,
                  !self.isSigningOut || !enable else { return }
            await client.enableAllSendQueues(enable: enable)
            guard self.syncGeneration == generation,
                  self.sendQueueStateRevision == revision,
                  !self.isSigningOut || !enable else { return }
            self.sendQueuesEnabled = enable
        }
    }

    private func beginRoomRefresh() {
        guard !isSigningOut else { return }
        refreshTask?.cancel()
        refreshTask = Task { [weak self] in
            while !Task.isCancelled {
                await self?.refreshConversations()
                try? await Task.sleep(for: .seconds(4))
            }
        }
    }

    private func refreshConversations() async {
        guard beginClientOperation() else { return }
        defer { endClientOperation() }
        guard let client else { return }
        var rows: [Conversation] = []
        for room in client.rooms() {
            do {
                let info = try await room.roomInfo()
                let latest = await room.latestEvent()
                let preview: String
                let timestamp: UInt64
                switch latest {
                case .none:
                    preview = "No messages yet"
                    timestamp = 0
                case let .remote(time, _, _, _, content), let .local(time, _, _, content, _):
                    preview = content.previewText
                    timestamp = time
                case .remoteInvite:
                    preview = "Invitation received"
                    timestamp = 0
                }
                rows.append(Conversation(
                    id: room.id(),
                    title: info.displayName ?? "Private conversation",
                    preview: preview,
                    timestamp: timestamp,
                    unreadCount: Int(min(info.numUnreadMessages, UInt64(Int.max))),
                    isEncrypted: info.encryptionState == .encrypted,
                    isGroup: !info.isDirect
                ))
            } catch { continue }
        }
        conversations = rows.sorted { $0.timestamp > $1.timestamp }
        if currentRoomId != nil { await refreshCurrentPeerTrust() }
    }

    private func persistDraft(roomId: String? = nil, text: String? = nil) async {
        guard beginClientOperation() else { return }
        defer { endClientOperation() }
        guard let roomId = roomId ?? currentRoomId, let client,
              let room = client.rooms().first(where: { $0.id() == roomId }) else { return }
        let body = text ?? draft
        do {
            if body.isEmpty {
                try await room.clearComposerDraft(threadRoot: nil)
            } else {
                let value = ComposerDraft(plainText: body, htmlText: nil, draftType: .newMessage, attachments: [])
                try await room.saveComposerDraft(draft: value, threadRoot: nil)
            }
        } catch { }
    }

    private func apply(diff: [TimelineDiff], roomId: String) {
        guard !isSigningOut, currentRoomId == roomId else { return }
        var acknowledgements = [String]()
        for update in diff {
            switch update {
            case let .append(values):
                for value in values { if let message = makeMessage(value, roomId: roomId, acknowledgements: &acknowledgements) { messages.append(message) } }
            case .clear: messages = []
            case let .pushFront(value):
                if let message = makeMessage(value, roomId: roomId, acknowledgements: &acknowledgements) { messages.insert(message, at: 0) }
            case let .pushBack(value):
                if let message = makeMessage(value, roomId: roomId, acknowledgements: &acknowledgements) { messages.append(message) }
            case .popFront: if !messages.isEmpty { messages.removeFirst() }
            case .popBack: if !messages.isEmpty { messages.removeLast() }
            case let .insert(index, value):
                if let message = makeMessage(value, roomId: roomId, acknowledgements: &acknowledgements) { messages.insert(message, at: min(Int(index), messages.count)) }
            case let .set(index, value):
                if Int(index) < messages.count, let message = makeMessage(value, roomId: roomId, acknowledgements: &acknowledgements) { messages[Int(index)] = message }
            case let .remove(index): if Int(index) < messages.count { messages.remove(at: Int(index)) }
            case let .truncate(length): messages = Array(messages.prefix(Int(length)))
            case let .reset(values):
                var restored = [ChatMessage]()
                for value in values { if let message = makeMessage(value, roomId: roomId, acknowledgements: &acknowledgements) { restored.append(message) } }
                messages = Array(restored.suffix(300))
            }
        }
        if messages.count > 300 { messages = Array(messages.suffix(300)) }
        var positions = [String: Int]()
        var uniqueMessages = [ChatMessage]()
        for message in messages {
            if let index = positions[message.id] {
                uniqueMessages[index] = message
            } else {
                positions[message.id] = uniqueMessages.count
                uniqueMessages.append(message)
            }
        }
        messages = uniqueMessages
        refreshDeliveryStates()
        if diff.contains(where: { if case .reset = $0 { return true }; return false }) {
            isRoomTimelineReady = true
        }
        reconcilePendingAttachment(in: roomId)
        if deliveryAckLedgerDirty || !acknowledgements.isEmpty || !(deliveryAckLedger.pendingByRoom[roomId] ?? []).isEmpty {
            queuePendingDeliveryAcknowledgements(in: roomId)
        }
        if readReceiptsEnabled, currentRoomEncrypted, let timeline = activeTimeline {
            Task { @MainActor [weak self] in
                guard let self, self.beginClientOperation() else { return }
                defer { self.endClientOperation() }
                try? await timeline.markAsRead(receiptType: .read)
            }
        }
    }

    private func makeMessage(_ item: TimelineItem, roomId: String, acknowledgements: inout [String]) -> ChatMessage? {
        guard let event = item.asEvent() else { return nil }
        let body: String
        var attachment: ChatAttachment? = nil
        var reactions = [MessageReaction]()
        var replyToEventId: String?
        switch event.content {
        case let .msgLike(message):
            reactions = message.reactions.map { reaction in
                MessageReaction(key: reaction.key, count: reaction.senders.count,
                                sentByMe: reaction.senders.contains { $0.senderId == userId })
            }
            replyToEventId = message.inReplyTo?.eventId()
            switch message.kind {
            case let .message(content):
                if case let .other(msgtype, ackEventId) = content.msgType, msgtype == Self.deliveryAckMsgtype {
                    recordObservedDeliveryAcknowledgement(
                        roomId: roomId,
                        eventId: ackEventId,
                        senderId: event.sender,
                        sentByMe: event.isOwn,
                        isRemote: event.isRemote,
                        localSendState: event.localSendState,
                        sendHandle: event.lazyProvider.getSendHandle()
                    )
                    return nil
                }
                body = content.body
                attachment = Self.attachment(from: content.msgType)
            case .redacted: body = "Message removed"
            case .unableToDecrypt: body = "Unable to decrypt this message"
            default: return nil
            }
        case .callInvite: body = "Call invitation"
        case .failedToParseMessageLike: body = "Unsupported message"
        default: return nil
        }
        let sendState: String
        let canRetry: Bool
        switch event.localSendState {
        case .notSentYet: sendState = connection == "Connected" ? "Sending" : "Queued"; canRetry = false
        case let .sendingFailed(_, recoverable): sendState = recoverable ? "Retry needed" : "Not sent"; canRetry = recoverable
        case .sent: sendState = "Sent"; canRetry = false
        case nil: sendState = "Sent"; canRetry = false
        }
        let eventId: String?
        let identifier: String
        switch event.eventOrTransactionId {
        case let .eventId(value): eventId = value; identifier = value
        case let .transactionId(value): eventId = nil; identifier = value
        }
        if !event.isOwn, event.isRemote, let eventId {
            let wasPending = deliveryAckLedger.pendingByRoom[roomId]?.contains(eventId) == true
            recordPendingDeliveryAcknowledgement(roomId: roomId, eventId: eventId)
            if !wasPending && !deliveryAckLedger.sent.contains(eventId) {
                acknowledgements.append(eventId)
            }
        }
        if let attachment, let operationId = attachment.operationId,
           pendingAttachment?.operationId == operationId, event.isOwn {
            pendingAttachmentIsInSendQueue = true
        }
        var visibleSendState = sendState
        if event.isOwn, let eventId {
            visibleSendState = deliveryState(roomId: roomId, eventId: eventId, fallback: sendState)
        }
        return ChatMessage(id: identifier, eventId: eventId, isRemote: event.isRemote, sender: event.sender, body: body,
                           timestamp: event.timestamp, isOwn: event.isOwn, sendState: visibleSendState,
                           canRetry: canRetry, canReply: event.canBeRepliedTo && eventId != nil,
                           replyToEventId: replyToEventId, reactions: reactions,
                           hasBeenRead: event.isOwn && event.readReceipts.keys.contains { $0 != userId },
                           attachment: attachment)
    }

    private func reconcilePendingAttachment(in roomId: String) {
        guard let manifest = pendingAttachment, manifest.roomId == roomId else { return }
        guard let matching = messages.first(where: {
            $0.isOwn && ($0.attachment?.operationId == manifest.operationId ||
                         $0.id == manifest.sdkEventOrTransactionId)
        }) else {
            if isRoomTimelineReady { pendingAttachmentIsInSendQueue = false }
            return
        }
        if manifest.sdkEventOrTransactionId != matching.id {
            let identified = PendingAttachmentManifest(
                operationId: manifest.operationId,
                roomId: manifest.roomId,
                userId: manifest.userId,
                homeserverUrl: manifest.homeserverUrl,
                displayFileName: manifest.displayFileName,
                audioDuration: manifest.audioDuration,
                sdkEventOrTransactionId: matching.id
            )
            pendingAttachment = identified
            do { try MessengerAttachmentOutbox.savePending(identified) }
            catch { errorMessage = "The attachment send identity could not be saved for recovery." }
        }
        if matching.sendState == "Sent" || matching.sendState == "Delivered" {
            clearPendingAttachment(manifest)
        } else {
            pendingAttachmentIsInSendQueue = true
        }
    }

    private func clearPendingAttachment(_ manifest: PendingAttachmentManifest) {
        MessengerAttachmentOutbox.removePending(manifest)
        let uploadTemp = try? MessengerAttachmentOutbox.temporaryUploadURL(for: manifest)
        if let uploadTemp { MessengerMediaStorage.removeTemporaryMedia(at: uploadTemp) }
        pendingAttachment = nil
        pendingAttachmentURL = nil
        pendingAttachmentRoomId = nil
        pendingAttachmentAudioDuration = nil
        pendingAttachmentDisplayName = ""
        pendingAttachmentIsInSendQueue = false
        hasPendingAttachment = false
    }

    private func refreshDeliveryStates() {
        messages = messages.map { message in
            guard message.isOwn, let eventId = message.eventId, let roomId = currentRoomId else { return message }
            let sendState = deliveryState(roomId: roomId, eventId: eventId, fallback: message.sendState)
            guard sendState != message.sendState else { return message }
            return ChatMessage(id: message.id, eventId: message.eventId, isRemote: message.isRemote,
                               sender: message.sender, body: message.body, timestamp: message.timestamp,
                               isOwn: message.isOwn, sendState: sendState, canRetry: message.canRetry,
                               canReply: message.canReply, replyToEventId: message.replyToEventId,
                               reactions: message.reactions, hasBeenRead: message.hasBeenRead,
                               attachment: message.attachment)
        }
    }

    private func deliveryState(roomId: String, eventId: String, fallback: String = "Sent") -> String {
        let expectedMembers = deliveryAckLedger.expectedMembersByRoom[roomId]?[eventId]
            ?? expectedDeliveryMemberIdsByRoom[roomId]
            ?? []
        let acknowledgedMembers = deliveryAckLedger.acknowledgedMembersByRoom[roomId]?[eventId] ?? []
        return deliveryStatusLabel(
            expectedMemberIds: expectedMembers,
            acknowledgedMemberIds: acknowledgedMembers,
            fallback: fallback,
            legacyDelivered: deliveryAckLedger.received.contains(eventId),
        )
    }

    private func sendDeliveryAcknowledgement(roomId: String?, eventId: String) async throws {
        guard !isSigningOut, let roomId, let client,
              let room = client.rooms().first(where: { $0.id() == roomId }),
              room.encryptionState() == .encrypted else { throw MessengerError.messageUnavailable }
        let timeline = try await room.timeline()
        guard let content = timeline.createMessageContent(msgType: .other(msgtype: Self.deliveryAckMsgtype, body: eventId)) else {
            throw MessengerError.messageUnavailable
        }
        _ = try await timeline.send(msg: content)
    }

    private func prepareAccountStorage(userId: String, homeserverUrl: String) throws {
        let accountKey = "\(homeserverUrl.lowercased())|\(userId.lowercased())"
        let ledgerURL = try storageRoot().appendingPathComponent("delivery-acknowledgements.json")
        var mustPersistLedger = false
        if FileManager.default.fileExists(atPath: ledgerURL.path) {
            let storedData = try Data(contentsOf: ledgerURL)
            let saved: DeliveryAcknowledgementLedger
            if let plaintext = try? MessengerProtectedMetadata.open(storedData, purpose: "delivery-acknowledgement-ledger"),
               let decoded = try? JSONDecoder().decode(DeliveryAcknowledgementLedger.self, from: plaintext) {
                saved = decoded
            } else if let legacy = try? JSONDecoder().decode(DeliveryAcknowledgementLedger.self, from: storedData) {
                // Migrate the previous file-protected JSON format in place.
                saved = legacy
                mustPersistLedger = true
            } else {
                throw MessengerError.attachmentUnavailable
            }
            if saved.accountKey == accountKey {
                deliveryAckLedger = saved
            } else {
                deliveryAckLedger = DeliveryAcknowledgementLedger(accountKey: accountKey)
                mustPersistLedger = true
            }
        } else {
            deliveryAckLedger = DeliveryAcknowledgementLedger(accountKey: accountKey)
            mustPersistLedger = true
        }
        if mustPersistLedger {
            try persistDeliveryAcknowledgementLedger()
        }
        deliveryAckLedgerDirty = false

        if let saved = try MessengerAttachmentOutbox.loadPending() {
            guard saved.userId == userId, saved.homeserverUrl.lowercased() == homeserverUrl.lowercased() else {
                MessengerAttachmentOutbox.removeAll()
                pendingAttachment = nil
                pendingAttachmentURL = nil
                pendingAttachmentForCurrentRoom = false
                pendingAttachmentRoomId = nil
                pendingAttachmentAudioDuration = nil
                pendingAttachmentDisplayName = ""
                pendingAttachmentIsInSendQueue = false
                hasPendingAttachment = false
                return
            }
            pendingAttachment = saved
            pendingAttachmentRoomId = saved.roomId
            pendingAttachmentAudioDuration = saved.audioDuration
            hasPendingAttachment = true
            pendingAttachmentDisplayName = saved.displayFileName
            // Restore the SDK's original local upload path before sync starts
            // and its durable send queue is enabled.
            do {
                _ = try MessengerAttachmentOutbox.materializeForUpload(saved)
            } catch MessengerError.attachmentUnavailable {
                errorMessage = "A saved attachment file is unavailable. Open its conversation to discard it."
            }
        } else {
            pendingAttachment = nil
            pendingAttachmentRoomId = nil
            pendingAttachmentAudioDuration = nil
            pendingAttachmentURL = nil
            pendingAttachmentForCurrentRoom = false
            pendingAttachmentDisplayName = ""
            hasPendingAttachment = false
            pendingAttachmentIsInSendQueue = false
        }
    }

    private func restorePendingAttachmentForCurrentRoom() {
        pendingAttachmentURL = nil
        pendingAttachmentForCurrentRoom = false
        pendingAttachmentIsInSendQueue = false
        guard let manifest = pendingAttachment, manifest.roomId == currentRoomId else { return }
        pendingAttachmentForCurrentRoom = true
        do {
            pendingAttachmentURL = try MessengerAttachmentOutbox.fileURL(for: manifest)
            pendingAttachmentDisplayName = manifest.displayFileName
        } catch {
            errorMessage = "A saved attachment is unavailable. Discard it before choosing another file."
        }
    }

    private func persistDeliveryAcknowledgementLedger() throws {
        let url = try storageRoot().appendingPathComponent("delivery-acknowledgements.json")
        let data = try JSONEncoder().encode(deliveryAckLedger)
        let encrypted = try MessengerProtectedMetadata.seal(data, purpose: "delivery-acknowledgement-ledger")
        try encrypted.write(to: url, options: [.atomic])
        try MessengerMediaStorage.protectPrivateFile(at: url)
    }

    private func queuePendingDeliveryAcknowledgements(in roomId: String) {
        guard !isSigningOut, isRoomTimelineReady, currentRoomId == roomId else { return }
        if deliveryAckLedgerDirty {
            do {
                try persistDeliveryAcknowledgementLedger()
                deliveryAckLedgerDirty = false
            } catch {
                errorMessage = "Delivery status could not be saved on this device."
                return
            }
        }
        let pending = deliveryAckLedger.pendingByRoom[roomId] ?? []
        for eventId in pending where !deliveryAckLedger.sent.contains(eventId) &&
            deliveryAckLedger.queuedByRoom[roomId]?.contains(eventId) != true {
            let key = "\(roomId)|\(eventId)"
            guard !deliveryAckInFlight.contains(key), deliveryAckSendTasks[key] == nil,
                  deliveryAckRetryTasks[key] == nil else { continue }
            deliveryAckInFlight.insert(key)
            deliveryAckSendTasks[key] = Task { @MainActor [weak self] in
                guard let self else { return }
                defer {
                    self.deliveryAckInFlight.remove(key)
                    self.deliveryAckSendTasks[key] = nil
                }
                for attempt in 0..<5 {
                    guard !Task.isCancelled, !self.isSigningOut, self.currentRoomId == roomId,
                          self.deliveryAckLedger.pendingByRoom[roomId]?.contains(eventId) == true,
                          !self.deliveryAckLedger.sent.contains(eventId) else { return }
                    do {
                        try await self.sendDeliveryAcknowledgement(roomId: roomId, eventId: eventId)
                        var updated = self.deliveryAckLedger
                        if !updated.sent.contains(eventId),
                           updated.queuedByRoom[roomId]?.contains(eventId) != true {
                            // `Timeline.send` confirms that the SDK stored a local
                            // echo, not that the homeserver accepted it. Keep the
                            // target pending until the timeline reports `.sent`.
                            updated.pendingByRoom[roomId]?.remove(eventId)
                            updated.queuedByRoom[roomId, default: []].insert(eventId)
                        }
                        do {
                            self.deliveryAckLedger = updated
                            try self.persistDeliveryAcknowledgementLedger()
                            self.deliveryAckLedgerDirty = false
                        } catch {
                            self.deliveryAckLedgerDirty = true
                            // The SDK has already durably queued this exact ACK.
                            // Its local echo will repair the ledger after relaunch.
                            self.errorMessage = "Delivery status could not be saved on this device."
                        }
                        return
                    } catch {
                        guard attempt < 4, !self.isSigningOut else { break }
                        try? await Task.sleep(for: .seconds(Int64(1 << attempt)))
                    }
                }
                // Leave the ACK in the durable pending set. Reopening the room or
                // reconnecting schedules another bounded retry cycle.
            }
        }
    }

    private func recordPendingDeliveryAcknowledgement(roomId: String, eventId: String) {
        guard !deliveryAckLedger.sent.contains(eventId),
              !deliveryAckLedger.received.contains(eventId),
              deliveryAckLedger.queuedByRoom[roomId]?.contains(eventId) != true,
              deliveryAckLedger.pendingByRoom[roomId]?.contains(eventId) != true else { return }
        var updated = deliveryAckLedger
        updated.pendingByRoom[roomId, default: []].insert(eventId)
        if updated != deliveryAckLedger {
            deliveryAckLedger = updated
            deliveryAckLedgerDirty = true
        }
    }

    private func recordObservedDeliveryAcknowledgement(
        roomId: String,
        eventId: String,
        senderId: String,
        sentByMe: Bool,
        isRemote: Bool,
        localSendState: EventSendState?,
        sendHandle: SendHandle?
    ) {
        let expectedMembers = deliveryAckLedger.expectedMembersByRoom[roomId]?[eventId]
            ?? expectedDeliveryMemberIdsByRoom[roomId]
            ?? []
        let previousAcknowledgements = deliveryAckLedger.acknowledgedMembersByRoom[roomId]?[eventId] ?? []
        if !sentByMe {
            if !expectedMembers.contains(senderId) || previousAcknowledgements.contains(senderId) { return }
            if expectedMembers.count <= 1 && deliveryAckLedger.received.contains(eventId) { return }
        }
        var updated = deliveryAckLedger
        var retryState: (handle: SendHandle, recoverable: Bool)?
        if sentByMe {
            if isRemote {
                updated.sent.insert(eventId)
                updated.pendingByRoom[roomId]?.remove(eventId)
                updated.queuedByRoom[roomId]?.remove(eventId)
                let key = deliveryAckKey(roomId: roomId, eventId: eventId)
                deliveryAckSendHandles[key] = nil
                deliveryAckFailureRecoverability[key] = nil
            } else {
                switch localSendState {
                case .sent:
                    updated.sent.insert(eventId)
                    updated.pendingByRoom[roomId]?.remove(eventId)
                    updated.queuedByRoom[roomId]?.remove(eventId)
                    let key = deliveryAckKey(roomId: roomId, eventId: eventId)
                    deliveryAckSendHandles[key] = nil
                    deliveryAckFailureRecoverability[key] = nil
                case .sendingFailed(_, let recoverable):
                    // The local echo remains in the SDK queue. Keep it marked as
                    // queued so reconciliation never creates a second ACK event.
                    updated.sent.remove(eventId)
                    updated.pendingByRoom[roomId, default: []].insert(eventId)
                    updated.queuedByRoom[roomId, default: []].insert(eventId)
                    if let sendHandle {
                        let key = deliveryAckKey(roomId: roomId, eventId: eventId)
                        deliveryAckSendHandles[key] = sendHandle
                        deliveryAckFailureRecoverability[key] = recoverable
                        retryState = (sendHandle, recoverable)
                    }
                case .notSentYet:
                    updated.sent.remove(eventId)
                    updated.pendingByRoom[roomId]?.remove(eventId)
                    updated.queuedByRoom[roomId, default: []].insert(eventId)
                    let key = deliveryAckKey(roomId: roomId, eventId: eventId)
                    deliveryAckSendHandles[key] = nil
                    deliveryAckFailureRecoverability[key] = nil
                case nil:
                    updated.sent.remove(eventId)
                    updated.queuedByRoom[roomId, default: []].insert(eventId)
                    let key = deliveryAckKey(roomId: roomId, eventId: eventId)
                    deliveryAckSendHandles[key] = nil
                    deliveryAckFailureRecoverability[key] = nil
                }
            }
        } else {
            var expectedByEvent = updated.expectedMembersByRoom[roomId] ?? [:]
            expectedByEvent[eventId] = expectedMembers
            updated.expectedMembersByRoom[roomId] = expectedByEvent
            var acknowledgementsByEvent = updated.acknowledgedMembersByRoom[roomId] ?? [:]
            acknowledgementsByEvent[eventId, default: []].insert(senderId)
            updated.acknowledgedMembersByRoom[roomId] = acknowledgementsByEvent
            updated.received.insert(eventId)
        }
        if updated != deliveryAckLedger {
            deliveryAckLedger = updated
            deliveryAckLedgerDirty = true
        }
        if let retryState {
            scheduleRetryForFailedDeliveryAcknowledgement(
                roomId: roomId,
                eventId: eventId,
                sendHandle: retryState.handle,
                recoverable: retryState.recoverable
            )
        }
    }

    private func deliveryAckKey(roomId: String, eventId: String) -> String {
        "\(roomId)|\(eventId)"
    }

    private func scheduleRetryForFailedDeliveryAcknowledgement(
        roomId: String,
        eventId: String,
        sendHandle: SendHandle,
        recoverable: Bool
    ) {
        let key = deliveryAckKey(roomId: roomId, eventId: eventId)
        guard !isSigningOut, deliveryAckRetryTasks[key] == nil else { return }
        deliveryAckInFlight.insert(key)
        deliveryAckRetryTasks[key] = Task { @MainActor [weak self] in
            guard let self else { return }
            defer {
                self.deliveryAckRetryTasks[key] = nil
                self.deliveryAckInFlight.remove(key)
            }

            // Wait for the connection gate and the app-level queue transition
            // before retrying the already queued event.
            await self.sendQueueTransitionTask?.value
            guard !self.isSigningOut, self.sendQueuesEnabled, self.connection == "Connected",
                  !self.clientPausedForBackground, self.currentRoomId == roomId else { return }

            for attempt in 0..<5 {
                guard !Task.isCancelled,
                      self.deliveryAckLedger.pendingByRoom[roomId]?.contains(eventId) == true,
                      self.deliveryAckLedger.queuedByRoom[roomId]?.contains(eventId) == true,
                      !self.deliveryAckLedger.sent.contains(eventId),
                      !self.isSigningOut, self.sendQueuesEnabled, self.connection == "Connected",
                      !self.clientPausedForBackground, self.currentRoomId == roomId,
                      let room = self.client?.rooms().first(where: { $0.id() == roomId }) else { return }

                try? await Task.sleep(for: .seconds(Int64(1 << attempt)))
                guard !Task.isCancelled,
                      self.deliveryAckLedger.pendingByRoom[roomId]?.contains(eventId) == true,
                      self.deliveryAckLedger.queuedByRoom[roomId]?.contains(eventId) == true,
                      !self.isSigningOut, !self.deliveryAckLedger.sent.contains(eventId) else { return }

                if self.deliveryAckFailureRecoverability[key] ?? recoverable {
                    // A transient failure disables the room queue. Re-enable it
                    // so the SDK retries this same transaction ID.
                    room.enableSendQueue(enable: true)
                } else {
                    // Unrecoverable failures are parked by the SDK. Use the
                    // associated local-echo handle to unpark the same event.
                    try? await sendHandle.tryResend()
                }
            }
        }
    }

    private func retryFailedDeliveryAcknowledgements(in roomId: String) {
        guard !isSigningOut else { return }
        for (key, sendHandle) in deliveryAckSendHandles {
            guard key.hasPrefix("\(roomId)|") else { continue }
            let eventId = String(key.dropFirst(roomId.count + 1))
            guard deliveryAckLedger.pendingByRoom[roomId]?.contains(eventId) == true,
                  deliveryAckLedger.queuedByRoom[roomId]?.contains(eventId) == true,
                  !deliveryAckLedger.sent.contains(eventId) else { continue }
            let recoverable = deliveryAckFailureRecoverability[key] ?? true
            if connection == "Connected", !clientPausedForBackground,
               deliveryAckRetryTasks[deliveryAckKey(roomId: roomId, eventId: eventId)] == nil {
                scheduleRetryForFailedDeliveryAcknowledgement(
                    roomId: roomId,
                    eventId: eventId,
                    sendHandle: sendHandle,
                    recoverable: recoverable
                )
            }
        }
    }

    private func setTyping(_ isTyping: Bool, roomId: String?) async {
        guard beginClientOperation() else { return }
        defer { endClientOperation() }
        guard let roomId, let room = client?.rooms().first(where: { $0.id() == roomId }),
              room.encryptionState() == .encrypted else { return }
        try? await room.typingNotice(isTyping: isTyping)
    }

    private func storageRoot() throws -> URL {
        let appRoot = Self.applicationDataRoot()
        var root = appRoot.appendingPathComponent("Matrix", isDirectory: true)
        var cache = root.appendingPathComponent("Cache", isDirectory: true)
        try FileManager.default.createDirectory(at: appRoot, withIntermediateDirectories: true)
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        try FileManager.default.createDirectory(at: cache, withIntermediateDirectories: true)
        try FileManager.default.setAttributes([.protectionKey: FileProtectionType.complete], ofItemAtPath: appRoot.path)
        try FileManager.default.setAttributes([.protectionKey: FileProtectionType.complete], ofItemAtPath: root.path)
        try FileManager.default.setAttributes([.protectionKey: FileProtectionType.complete], ofItemAtPath: cache.path)
        var values = URLResourceValues()
        values.isExcludedFromBackup = true
        var protectedAppRoot = appRoot
        try protectedAppRoot.setResourceValues(values)
        try root.setResourceValues(values)
        try cache.setResourceValues(values)
        return root
    }

    static func applicationDataRoot() -> URL {
        FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("PrivateMessenger", isDirectory: true)
    }

    private static func attachment(from messageType: MessageType) -> ChatAttachment? {
        switch messageType {
        case let .image(content):
            let parsed = MessengerAttachmentOutbox.decodeUploadFileName(content.filename)
            let fileName = parsed?.displayFileName ?? content.filename
            return ChatAttachment(fileName: fileName, mimeType: content.info?.mimetype ?? inferredMimeType(fileName),
                                  sourceJson: content.source.toJson(), kind: .image, operationId: parsed?.operationId)
        case let .video(content):
            let parsed = MessengerAttachmentOutbox.decodeUploadFileName(content.filename)
            let fileName = parsed?.displayFileName ?? content.filename
            return ChatAttachment(fileName: fileName, mimeType: content.info?.mimetype ?? inferredMimeType(fileName),
                                  sourceJson: content.source.toJson(), kind: .video, operationId: parsed?.operationId)
        case let .audio(content):
            let parsed = MessengerAttachmentOutbox.decodeUploadFileName(content.filename)
            let fileName = parsed?.displayFileName ?? content.filename
            return ChatAttachment(fileName: fileName, mimeType: content.info?.mimetype ?? inferredMimeType(fileName),
                                  sourceJson: content.source.toJson(), kind: .audio, operationId: parsed?.operationId)
        case let .file(content):
            let parsed = MessengerAttachmentOutbox.decodeUploadFileName(content.filename)
            let fileName = parsed?.displayFileName ?? content.filename
            return ChatAttachment(fileName: fileName,
                                  mimeType: content.info?.mimetype ?? inferredMimeType(fileName),
                                  sourceJson: content.source.toJson(), kind: .file, operationId: parsed?.operationId)
        default:
            return nil
        }
    }

    private static func inferredMimeType(_ fileName: String) -> String {
        UTType(filenameExtension: URL(fileURLWithPath: fileName).pathExtension)?.preferredMIMEType
            ?? "application/octet-stream"
    }

    private static let deliveryAckMsgtype = "org.friendline.delivery"
}

private enum MessengerError: Error {
    case messageUnavailable, attachmentTooLarge, attachmentUnavailable, insecureHomeserver
    case encryptedRoomCreationFailed
}

enum VoiceNoteError: Error {
    case microphonePermissionDenied
    case recorderUnavailable
    case recordingTooShort
}

@MainActor
final class VoiceNoteRecorder: ObservableObject {
    @Published private(set) var isRecording = false
    @Published private(set) var isRequestingPermission = false
    @Published private(set) var recordedURL: URL?
    @Published private(set) var elapsed: TimeInterval = 0

    private var recorder: AVAudioRecorder?
    private var clockTask: Task<Void, Never>?
    private var permissionRequestId = UUID()

    func startRecording() async throws {
        guard !isRecording, recordedURL == nil, !isRequestingPermission else { return }
        isRequestingPermission = true
        let requestId = UUID()
        permissionRequestId = requestId
        let permitted = await withCheckedContinuation { continuation in
            AVAudioSession.sharedInstance().requestRecordPermission { granted in
                continuation.resume(returning: granted)
            }
        }
        guard permissionRequestId == requestId else { return }
        isRequestingPermission = false
        guard permitted else { throw VoiceNoteError.microphonePermissionDenied }

        let url = try MessengerMediaStorage.newRecordingURL()
        do {
            let session = AVAudioSession.sharedInstance()
            try session.setCategory(.playAndRecord, mode: .default, options: [.defaultToSpeaker])
            try session.setActive(true)
            let settings: [String: Any] = [
                AVFormatIDKey: kAudioFormatMPEG4AAC,
                AVSampleRateKey: 44_100,
                AVNumberOfChannelsKey: 1,
                AVEncoderBitRateKey: 64_000
            ]
            let newRecorder = try AVAudioRecorder(url: url, settings: settings)
            guard newRecorder.prepareToRecord(), newRecorder.record() else {
                throw VoiceNoteError.recorderUnavailable
            }
            recorder = newRecorder
            elapsed = 0
            isRecording = true
            clockTask?.cancel()
            clockTask = Task { [weak self] in
                while !Task.isCancelled {
                    try? await Task.sleep(for: .milliseconds(250))
                    guard let self, let recorder = self.recorder, recorder.isRecording else { return }
                    self.elapsed = recorder.currentTime
                }
            }
        } catch {
            try? FileManager.default.removeItem(at: url)
            try? AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation)
            throw error
        }
    }

    @discardableResult
    func finishRecording() throws -> URL {
        guard let recorder, isRecording else { throw VoiceNoteError.recorderUnavailable }
        let duration = recorder.currentTime
        recorder.stop()
        self.recorder = nil
        clockTask?.cancel()
        clockTask = nil
        isRecording = false
        elapsed = duration
        try? AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation)

        guard duration >= 0.35 else {
            try? FileManager.default.removeItem(at: recorder.url)
            elapsed = 0
            throw VoiceNoteError.recordingTooShort
        }
        do {
            try MessengerMediaStorage.protectPrivateFile(at: recorder.url)
        } catch {
            try? FileManager.default.removeItem(at: recorder.url)
            elapsed = 0
            throw error
        }
        recordedURL = recorder.url
        return recorder.url
    }

    func takeRecording() -> URL? {
        let url = recordedURL
        recordedURL = nil
        elapsed = 0
        return url
    }

    func cancel() {
        permissionRequestId = UUID()
        isRequestingPermission = false
        clockTask?.cancel()
        clockTask = nil
        if let recorder {
            recorder.stop()
            try? FileManager.default.removeItem(at: recorder.url)
        }
        recorder = nil
        if let recordedURL { try? FileManager.default.removeItem(at: recordedURL) }
        recordedURL = nil
        elapsed = 0
        isRecording = false
        try? AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation)
    }

    func handleAudioInterruption(_ notification: Notification) -> Bool {
        guard let rawValue = notification.userInfo?[AVAudioSessionInterruptionTypeKey] as? UInt,
              AVAudioSession.InterruptionType(rawValue: rawValue) == .began,
              isRecording || isRequestingPermission else { return false }
        cancel()
        return true
    }
}

@MainActor
final class VoiceNotePlaybackController: NSObject, ObservableObject, AVAudioPlayerDelegate {
    @Published private(set) var playingMessageId: String?

    private var player: AVAudioPlayer?
    private var playbackURL: URL?
    private var deleteWhenFinished = false

    func play(url: URL, messageId: String, deleteWhenFinished: Bool) throws {
        stop()
        do {
            let session = AVAudioSession.sharedInstance()
            try session.setCategory(.playback, mode: .spokenAudio)
            try session.setActive(true)
            let newPlayer = try AVAudioPlayer(contentsOf: url)
            newPlayer.delegate = self
            guard newPlayer.prepareToPlay(), newPlayer.play() else { throw VoiceNoteError.recorderUnavailable }
            player = newPlayer
            playbackURL = url
            self.deleteWhenFinished = deleteWhenFinished
            playingMessageId = messageId
        } catch {
            if deleteWhenFinished { MessengerMediaStorage.removeTemporaryMedia(at: url) }
            try? AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation)
            throw error
        }
    }

    func stop() {
        let url = playbackURL
        let shouldDelete = deleteWhenFinished
        player?.delegate = nil
        player?.stop()
        player = nil
        playbackURL = nil
        deleteWhenFinished = false
        playingMessageId = nil
        try? AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation)
        if shouldDelete, let url { MessengerMediaStorage.removeTemporaryMedia(at: url) }
    }

    func audioPlayerDidFinishPlaying(_ player: AVAudioPlayer, successfully flag: Bool) {
        guard self.player === player else { return }
        stop()
    }

    func audioPlayerDecodeErrorDidOccur(_ player: AVAudioPlayer, error: Error?) {
        guard self.player === player else { return }
        stop()
    }
}

private final class SyncObserver: SyncServiceStateObserver, @unchecked Sendable {
    private let handler: @Sendable (SyncServiceState) -> Void
    init(handler: @escaping @Sendable (SyncServiceState) -> Void) { self.handler = handler }
    func onUpdate(state: SyncServiceState) { handler(state) }
}

private final class SendQueueErrorObserver: SendQueueRoomErrorListener, @unchecked Sendable {
    func onError(roomId: String, error: ClientError) {
        // Keep error details and room identifiers out of logs; the SDK already
        // marks the room's queued event as failed for in-app retry UI.
    }
}

private final class TimelineObserver: TimelineListener, @unchecked Sendable {
    private let handler: @Sendable ([TimelineDiff]) -> Void
    init(handler: @escaping @Sendable ([TimelineDiff]) -> Void) { self.handler = handler }
    func onUpdate(diff: [TimelineDiff]) { handler(diff) }
}

private final class SearchResultsObserver: SearchServiceResultsListener, @unchecked Sendable {
    private let handler: @Sendable ([SearchServiceResultsUpdate]) -> Void
    init(handler: @escaping @Sendable ([SearchServiceResultsUpdate]) -> Void) { self.handler = handler }
    func onUpdate(updates: [SearchServiceResultsUpdate]) { handler(updates) }
}

private final class SearchPaginationObserver: SearchServicePaginationStateListener, @unchecked Sendable {
    private let handler: @Sendable (SearchServicePaginationState) -> Void
    init(handler: @escaping @Sendable (SearchServicePaginationState) -> Void) { self.handler = handler }
    func onUpdate(paginationState: SearchServicePaginationState) { handler(paginationState) }
}

private extension TimelineItemContent {
    var previewText: String {
        switch self {
        case let .msgLike(message):
            if case let .message(content) = message.kind { return content.body }
            if case .redacted = message.kind { return "Message removed" }
            if case .unableToDecrypt = message.kind { return "Unable to decrypt this message" }
            return "Encrypted conversation"
        case .failedToParseMessageLike: return "Unsupported message"
        default: return "Encrypted conversation"
        }
    }
}

private struct DeviceVault {
    private let service = "dev.friendline.messenger.ios.device-vault"
    private let sessionAccount = "matrix-session"
    private let storeKeyAccount = "matrix-store-key"
    private let readReceiptsAccount = "read-receipts-enabled"
    private let mediaOutboxKeyAccount = "media-outbox-key"
    private let localMetadataKeyAccount = "local-metadata-key"
    private let installGenerationAccount = "install-generation"
    static let installGenerationDefaultsKey = "dev.friendline.messenger.install-generation"

    func ensureInstallGeneration(localStateExists: Bool) throws -> Bool {
        let keychainGeneration = try get(account: installGenerationAccount).flatMap { String(data: $0, encoding: .utf8) }
        let defaultsGeneration = UserDefaults.standard.string(forKey: Self.installGenerationDefaultsKey)
        let containerMarker = try loadContainerGeneration()
        let hasSession = try get(account: sessionAccount) != nil
        let hasStoreKey = try get(account: storeKeyAccount) != nil
        let matrixDatabase = Self.applicationDataRoot().appendingPathComponent("Matrix", isDirectory: true)
            .appendingPathComponent("matrix.db")
        let sessionHasMatchingStore = !hasSession || (hasStoreKey && FileManager.default.fileExists(atPath: matrixDatabase.path))
        if let keychainGeneration, let defaultsGeneration, let containerMarker,
           keychainGeneration == defaultsGeneration, keychainGeneration == containerMarker,
           sessionHasMatchingStore {
            return false
        }

        let needsReset = localStateExists || keychainGeneration != nil || defaultsGeneration != nil || containerMarker != nil
        if needsReset {
            try clearAllIncludingInstallGeneration()
            try removeApplicationDataRootIfPresent()
        }
        let generation = UUID().uuidString.lowercased()
        try put(Data(generation.utf8), account: installGenerationAccount)
        UserDefaults.standard.set(generation, forKey: Self.installGenerationDefaultsKey)
        guard UserDefaults.standard.string(forKey: Self.installGenerationDefaultsKey) == generation else {
            throw VaultError.installGenerationUnavailable
        }
        try saveContainerGeneration(generation)
        return needsReset
    }

    func invalidateInstallGenerationDefault() {
        UserDefaults.standard.removeObject(forKey: Self.installGenerationDefaultsKey)
    }

    func save(session: SessionRecord) throws {
        let data = try JSONEncoder().encode(session)
        try put(data, account: sessionAccount)
    }

    func loadSession() throws -> SessionRecord? {
        guard let data = try get(account: sessionAccount) else { return nil }
        return try JSONDecoder().decode(SessionRecord.self, from: data)
    }

    func saveStoreKeyIfNeeded() throws {
        guard try get(account: storeKeyAccount) == nil else { return }
        var bytes = [UInt8](repeating: 0, count: 32)
        guard SecRandomCopyBytes(kSecRandomDefault, bytes.count, &bytes) == errSecSuccess else {
            throw VaultError.randomGenerationFailed
        }
        try put(Data(bytes), account: storeKeyAccount)
    }

    func saveMediaOutboxKeyIfNeeded() throws {
        guard try get(account: mediaOutboxKeyAccount) == nil else { return }
        var bytes = [UInt8](repeating: 0, count: 32)
        guard SecRandomCopyBytes(kSecRandomDefault, bytes.count, &bytes) == errSecSuccess else {
            throw VaultError.randomGenerationFailed
        }
        try put(Data(bytes), account: mediaOutboxKeyAccount)
    }

    func saveLocalMetadataKeyIfNeeded() throws {
        guard try get(account: localMetadataKeyAccount) == nil else { return }
        var bytes = [UInt8](repeating: 0, count: 32)
        guard SecRandomCopyBytes(kSecRandomDefault, bytes.count, &bytes) == errSecSuccess else {
            throw VaultError.randomGenerationFailed
        }
        try put(Data(bytes), account: localMetadataKeyAccount)
    }

    func loadLocalMetadataKey() throws -> Data {
        guard let key = try get(account: localMetadataKeyAccount), key.count == 32 else {
            throw VaultError.missingStoreKey
        }
        return key
    }

    func loadMediaOutboxKey() throws -> Data {
        guard let key = try get(account: mediaOutboxKeyAccount), key.count == 32 else {
            throw VaultError.missingStoreKey
        }
        return key
    }

    func loadStoreKey() throws -> Data {
        guard let key = try get(account: storeKeyAccount), key.count == 32 else {
            throw VaultError.missingStoreKey
        }
        return key
    }

    func loadReadReceiptsEnabled() throws -> Bool {
        guard let value = try get(account: readReceiptsAccount), value.count == 1 else { return false }
        return value[0] == 1
    }

    func saveReadReceiptsEnabled(_ enabled: Bool) throws {
        try put(Data([enabled ? 1 : 0]), account: readReceiptsAccount)
    }

    func clear() throws {
        let accounts = [storeKeyAccount, readReceiptsAccount, mediaOutboxKeyAccount,
                        localMetadataKeyAccount, sessionAccount]
        let snapshot = try accounts.map { account in (account, try get(account: account)) }
        var attempted = Set<String>()
        do {
            // Remove the session last so earlier failures leave it available
            // while restoration of any already-deleted entries is attempted.
            for account in accounts {
                attempted.insert(account)
                try delete(account: account)
            }
        } catch {
            let clearError = error
            do {
                try restore(snapshot.filter { attempted.contains($0.0) })
            } catch {
                throw VaultError.clearRollbackFailed
            }
            throw clearError
        }
    }

    private func restore(_ snapshot: [(String, Data?)]) throws {
        var firstError: Error?
        for (account, value) in snapshot {
            do {
                if let value { try put(value, account: account) }
                else { try delete(account: account) }
            } catch {
                if firstError == nil { firstError = error }
            }
        }
        if let firstError { throw firstError }
    }

    private func clearAllIncludingInstallGeneration() throws {
        try deleteAccounts([sessionAccount, storeKeyAccount, readReceiptsAccount,
                           mediaOutboxKeyAccount, localMetadataKeyAccount, installGenerationAccount])
        UserDefaults.standard.removeObject(forKey: Self.installGenerationDefaultsKey)
    }

    private func loadContainerGeneration() throws -> String? {
        let url = Self.containerGenerationURL()
        guard FileManager.default.fileExists(atPath: url.path) else { return nil }
        let data = try Data(contentsOf: url)
        guard let value = String(data: data, encoding: .utf8), !value.isEmpty else { return nil }
        return value
    }

    private func saveContainerGeneration(_ value: String) throws {
        let root = Self.applicationDataRoot()
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        try FileManager.default.setAttributes([.protectionKey: FileProtectionType.complete], ofItemAtPath: root.path)
        var values = URLResourceValues()
        values.isExcludedFromBackup = true
        var protectedRoot = root
        try protectedRoot.setResourceValues(values)
        let url = Self.containerGenerationURL()
        try Data(value.utf8).write(to: url, options: [.atomic])
        try FileManager.default.setAttributes([.protectionKey: FileProtectionType.complete], ofItemAtPath: url.path)
        var protectedURL = url
        try protectedURL.setResourceValues(values)
    }

    private func removeApplicationDataRootIfPresent() throws {
        do { try FileManager.default.removeItem(at: Self.applicationDataRoot()) }
        catch let error as CocoaError where error.code == .fileNoSuchFile { }
    }

    private static func applicationDataRoot() -> URL {
        FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("PrivateMessenger", isDirectory: true)
    }

    private static func containerGenerationURL() -> URL {
        applicationDataRoot().appendingPathComponent("install-generation", isDirectory: false)
    }

    private func deleteAccounts(_ accounts: [String]) throws {
        var firstError: Error?
        for account in accounts {
            do { try delete(account: account) }
            catch { if firstError == nil { firstError = error } }
        }
        if let firstError { throw firstError }
    }

    private func get(account: String) throws -> Data? {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
            kSecReturnData as String: true,
            kSecMatchLimit as String: kSecMatchLimitOne
        ]
        var result: CFTypeRef?
        let status = SecItemCopyMatching(query as CFDictionary, &result)
        if status == errSecItemNotFound { return nil }
        guard status == errSecSuccess, let data = result as? Data else { throw VaultError.keychain(status) }
        return data
    }

    private func put(_ data: Data, account: String) throws {
        try delete(account: account)
        let item: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
            kSecAttrAccessible as String: kSecAttrAccessibleWhenUnlockedThisDeviceOnly,
            kSecValueData as String: data
        ]
        let status = SecItemAdd(item as CFDictionary, nil)
        guard status == errSecSuccess else { throw VaultError.keychain(status) }
    }

    private func delete(account: String) throws {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account
        ]
        let status = SecItemDelete(query as CFDictionary)
        guard status == errSecSuccess || status == errSecItemNotFound else { throw VaultError.keychain(status) }
    }
}

private enum VaultError: Error {
    case keychain(OSStatus)
    case randomGenerationFailed
    case missingStoreKey
    case installGenerationUnavailable
    case clearRollbackFailed
}
