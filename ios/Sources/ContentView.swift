import SwiftUI
import UIKit
import AVFoundation
import CoreTransferable
import PhotosUI
import QuickLook
import UniformTypeIdentifiers

private struct ImportedMediaFile: Transferable {
    let url: URL

    static var transferRepresentation: some TransferRepresentation {
        FileRepresentation(importedContentType: .item) { received in
            ImportedMediaFile(url: try MessengerAttachmentOutbox.stagePickedFile(from: received.file))
        }
    }
}

private struct PreviewDocument: Identifiable {
    let id = UUID()
    let url: URL
}

private struct MessageFramePreferenceKey: PreferenceKey {
    static var defaultValue: [String: CGRect] = [:]

    static func reduce(value: inout [String: CGRect], nextValue: () -> [String: CGRect]) {
        value.merge(nextValue(), uniquingKeysWith: { _, next in next })
    }
}

struct ContentView: View {
    @EnvironmentObject private var messenger: MessengerStore
    @State private var showingNewConversation = false
    @State private var showingPrivacySettings = false
    @State private var searchText = ""

    private var filteredConversations: [Conversation] {
        guard !searchText.isEmpty else { return messenger.conversations }
        return messenger.conversations.filter {
            $0.title.localizedCaseInsensitiveContains(searchText) || $0.preview.localizedCaseInsensitiveContains(searchText)
        }
    }

    var body: some View {
        Group {
            if messenger.userId == nil {
                SignInScreen()
            } else if messenger.currentRoomId != nil {
                ChatScreen(showingPrivacySettings: $showingPrivacySettings)
            } else {
                conversationList
            }
        }
        .tint(Color(red: 0.12, green: 0.39, blue: 0.37))
        .alert("Couldn't complete that action", isPresented: Binding(
            get: { messenger.errorMessage != nil },
            set: { if !$0 { messenger.errorMessage = nil } }
        )) {
            Button("OK", role: .cancel) { messenger.errorMessage = nil }
        } message: {
            Text(messenger.errorMessage ?? "Please try again.")
        }
        .sheet(isPresented: $showingNewConversation) {
            NewConversationScreen()
                .modifier(PrivateScenePrivacyShield())
                .presentationDetents([.medium, .large])
                .presentationDragIndicator(.visible)
        }
        .sheet(isPresented: $showingPrivacySettings) {
            PrivacySettingsScreen(canVerifyConversationPeer: messenger.currentPeerUserId != nil)
                .modifier(PrivateScenePrivacyShield())
                .presentationDetents([.medium, .large])
                .presentationDragIndicator(.visible)
        }
        .onChange(of: messenger.verificationStep) { step in
            if step == .incomingRequest { showingPrivacySettings = true }
        }
        .modifier(PrivateScenePrivacyShield())
    }

    private var conversationList: some View {
        NavigationStack {
            VStack(spacing: 0) {
                HStack(spacing: 8) {
                    Circle().fill(messenger.connection == "Connected" ? Color.green : Color.orange).frame(width: 7, height: 7)
                    Text(messenger.connection.uppercased()).font(.caption2.weight(.semibold)).tracking(1.1)
                    Spacer()
                    Text(messenger.userId ?? "").font(.caption).foregroundStyle(.secondary).lineLimit(1)
                        .privacySensitive()
                }
                .padding(.horizontal, 20).padding(.vertical, 11)
                .background(Color(uiColor: .secondarySystemBackground))

                if messenger.conversations.isEmpty {
                    EmptyState(title: "No conversations", detail: "Invite someone by their Matrix ID to open a private line.", symbol: "waveform.path") {
                        Button("Start a conversation") { showingNewConversation = true }
                            .buttonStyle(.borderedProminent)
                    }
                    .frame(maxHeight: .infinity)
                } else {
                    List(filteredConversations) { conversation in
                        if conversation.isVerificationControl {
                            VStack(alignment: .leading, spacing: 12) {
                                Label("Device verification invitation", systemImage: "checkmark.shield")
                                    .font(.headline)
                                if let peerUserId = conversation.verificationPeerUserId {
                                    Text("From \(peerUserId)")
                                        .font(.caption)
                                        .foregroundStyle(.secondary)
                                        .textSelection(.enabled)
                                }
                                Text("This channel is not end-to-end encrypted. Your homeserver can see who joined, when, and the verification events. It is for the SAS handshake only. Compare the code in person or through another trusted channel; never confirm a mismatch.")
                                    .font(.footnote)
                                    .foregroundStyle(.secondary)
                                if messenger.invitationActionsInProgress.contains(conversation.id) {
                                    ProgressView("Updating invitation…")
                                        .font(.caption)
                                } else {
                                    HStack {
                                        Button("Decline", role: .destructive) {
                                            Task { await messenger.declineRoomInvitation(conversation.id) }
                                        }
                                        .disabled(messenger.isBusy)
                                        Spacer()
                                        Button("Join channel") {
                                            Task { await messenger.joinVerificationControlRoom(conversation.id) }
                                        }
                                        .buttonStyle(.borderedProminent)
                                        .disabled(messenger.isBusy)
                                    }
                                }
                            }
                            .padding(.vertical, 4)
                        } else if conversation.isInvitation {
                            VStack(alignment: .leading, spacing: 10) {
                                ConversationRow(conversation: conversation)
                                if messenger.invitationActionsInProgress.contains(conversation.id) {
                                    ProgressView("Updating invitation…")
                                        .font(.caption)
                                } else {
                                    HStack {
                                        Button("Decline", role: .destructive) {
                                            Task { await messenger.declineRoomInvitation(conversation.id) }
                                        }
                                        .disabled(messenger.isBusy)
                                        Spacer()
                                        Button("Accept") {
                                            Task { await messenger.acceptRoomInvitation(conversation.id) }
                                        }
                                        .buttonStyle(.borderedProminent)
                                        .disabled(messenger.isBusy || !conversation.isEncrypted)
                                    }
                                }
                            }
                        } else {
                            Button {
                                Task { await messenger.openConversation(conversation.id) }
                            } label: {
                                ConversationRow(conversation: conversation)
                            }
                            .buttonStyle(.plain)
                            .disabled(messenger.isBusy)
                        }
                        .listRowInsets(EdgeInsets(top: 12, leading: 20, bottom: 12, trailing: 20))
                    }
                    .listStyle(.plain)
                    .searchable(text: $searchText, prompt: "Search conversations")
                }
            }
            .navigationTitle("Private Messenger")
            .toolbar {
                ToolbarItem(placement: .navigationBarTrailing) {
                    Button { showingNewConversation = true } label: { Image(systemName: "square.and.pencil") }
                        .accessibilityLabel("New conversation")
                }
                ToolbarItem(placement: .navigationBarTrailing) {
                    Button { showingPrivacySettings = true } label: { Image(systemName: "hand.raised") }
                        .accessibilityLabel("Privacy settings")
                }
                ToolbarItem(placement: .navigationBarLeading) {
                    Menu {
                        Button("Sign out", role: .destructive) { Task { await messenger.logout() } }
                    } label: { Image(systemName: "ellipsis.circle") }
                }
            }
        }
    }
}

private struct PrivacySettingsScreen: View {
    @EnvironmentObject private var messenger: MessengerStore
    @Environment(\.dismiss) private var dismiss
    var canVerifyConversationPeer = false

    var body: some View {
        NavigationStack {
            Form {
                Section("DEVICE & MESSAGE HISTORY") {
                    Label("Your encryption keys are kept on this device.", systemImage: "key.fill")
                    Text("If you lose this device or its keys, older encrypted messages may be unrecoverable. The homeserver cannot restore message plaintext. Verify a replacement device with your contacts before trusting it.")
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                }

                Section("MESSAGE NOTIFICATIONS") {
                    Toggle(
                        "Enable private alerts",
                        isOn: Binding(
                            get: { messenger.pushNotificationsEnabled },
                            set: { enabled in
                                Task { await messenger.setPushNotificationsEnabled(enabled) }
                            }
                        )
                    )
                    .disabled(!NativePushNotifications.isConfigured && !messenger.pushNotificationsEnabled)

                    Text(
                        NativePushNotifications.isConfigured || messenger.pushRegistrationStatus == .removalPending
                            ? messenger.pushRegistrationStatus.displayText
                            : PushRegistrationResult.disabled.displayText
                    )
                    .font(.footnote)
                    .foregroundStyle(.secondary)

                    if messenger.pushRegistrationStatus == .removalPending {
                        Button("Retry alert removal") {
                            Task { await messenger.retryPendingPushRemoval() }
                        }
                    }

                    if NativePushNotifications.isConfigured && messenger.pushRegistrationStatus == .permissionRequired {
                        Button("Open notification settings") {
                            guard let url = URL(string: UIApplication.openSettingsURLString) else { return }
                            UIApplication.shared.open(url)
                        }
                    }
                }

                Section {
                    Toggle(
                        "Send read receipts",
                        isOn: Binding(
                            get: { messenger.readReceiptsEnabled },
                            set: { messenger.setReadReceiptsEnabled($0) }
                        )
                    )
                    Text("When enabled, people in a conversation can see when you have read their messages. This setting is saved securely on this device.")
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                }
                if canVerifyConversationPeer {
                    Section("CONVERSATION VERIFICATION") {
                        Text("Peer verification uses a private Matrix room that is not end-to-end encrypted. Your homeserver can see its members, timing, and verification events. Compare the SAS code in person or through another trusted channel before confirming.")
                            .font(.footnote)
                            .foregroundStyle(.secondary)
                        switch messenger.currentPeerTrust {
                        case .verified:
                            Label("This person's identity is verified.", systemImage: "checkmark.shield.fill")
                                .foregroundStyle(.green)
                        case .changed:
                            Label("This person's identity changed. Verify again before trusting this conversation.", systemImage: "exclamationmark.shield.fill")
                                .foregroundStyle(.red)
                        case .unverified:
                            Label("This person's identity has not been verified.", systemImage: "checkmark.shield")
                                .foregroundStyle(.orange)
                        case .unknown:
                            Label("Verification state is unavailable.", systemImage: "questionmark.shield")
                                .foregroundStyle(.secondary)
                        }
                        Button {
                            Task { await messenger.requestPeerVerification() }
                        } label: {
                            Label("Verify this person", systemImage: "person.crop.circle.badge.checkmark")
                        }
                        .disabled(messenger.verificationIsBusy || !(messenger.verificationStep == .idle || messenger.verificationStep == .verified || messenger.verificationStep == .failed || messenger.verificationStep == .cancelled))
                    }
                }
                Section("DEVICE VERIFICATION") {
                    Text("Compare a short code with another device on your account. A device is marked verified only after both sides confirm the same code.")
                        .font(.footnote).foregroundStyle(.secondary)

                    switch messenger.verificationStep {
                    case .idle:
                        Button {
                            Task { await messenger.requestDeviceVerification() }
                        } label: {
                            Label("Verify another device", systemImage: "checkmark.shield")
                        }
                        .disabled(messenger.verificationIsBusy)
                    case .incomingRequest:
                        VStack(alignment: .leading, spacing: 5) {
                            Text("Verification requested").font(.subheadline.weight(.semibold))
                            Text("\(messenger.verificationPeer) · device \(messenger.verificationDeviceId)")
                                .font(.caption).foregroundStyle(.secondary).textSelection(.enabled)
                            Text("Accept only if you started this request from a device you recognize.")
                                .font(.caption).foregroundStyle(.secondary)
                        }
                        HStack {
                            Button("Decline", role: .destructive) {
                                Task { await messenger.respondToVerificationRequest(accept: false) }
                            }
                            .disabled(messenger.verificationIsBusy)
                            Spacer()
                            Button("Accept") {
                                Task { await messenger.respondToVerificationRequest(accept: true) }
                            }
                            .buttonStyle(.borderedProminent)
                            .disabled(messenger.verificationIsBusy)
                        }
                    case .waitingForPeer:
                        Text(messenger.isWaitingForVerificationChannelPeer
                             ? "Waiting for the other person to join the private verification channel. No SAS request is sent until they join."
                             : "Waiting for the other device to accept and show a code.")
                            .font(.footnote).foregroundStyle(.secondary)
                        Button("Cancel verification", role: .cancel) {
                            Task { await messenger.cancelVerification() }
                        }
                        .disabled(messenger.verificationIsBusy)
                    case .comparingSas, .confirming:
                        VStack(alignment: .leading, spacing: 12) {
                            Text("Compare on both devices").font(.subheadline.weight(.semibold))
                            Text(messenger.verificationPeer).font(.caption).foregroundStyle(.secondary)
                            if !messenger.verificationEmojis.isEmpty {
                                LazyVGrid(columns: [GridItem(.flexible()), GridItem(.flexible())], alignment: .leading, spacing: 10) {
                                    ForEach(messenger.verificationEmojis) { emoji in
                                        HStack(spacing: 7) {
                                            Text(emoji.symbol).font(.title2)
                                            Text(emoji.description).font(.caption)
                                        }
                                    }
                                }
                                .padding(.vertical, 4)
                            } else if !messenger.verificationDecimals.isEmpty {
                                Text(messenger.verificationDecimals.map { String($0) }.joined(separator: "   "))
                                    .font(.title2.monospacedDigit().weight(.semibold))
                                    .padding(.vertical, 6)
                            } else {
                                ProgressView("Preparing comparison…")
                            }
                            Text("Read the complete code on both devices. Confirm only if every symbol or number matches.")
                                .font(.caption).foregroundStyle(.secondary)
                            Button("The codes match — verify") {
                                Task { await messenger.confirmVerificationMatches() }
                            }
                            .buttonStyle(.borderedProminent)
                            .disabled(messenger.verificationIsBusy || (messenger.verificationEmojis.isEmpty && messenger.verificationDecimals.isEmpty))
                            Button("They don't match", role: .destructive) {
                                Task { await messenger.rejectVerificationMismatch() }
                            }
                            .disabled(messenger.verificationIsBusy || (messenger.verificationEmojis.isEmpty && messenger.verificationDecimals.isEmpty))
                        }
                    case .verified:
                        Label("Device verified", systemImage: "checkmark.shield.fill")
                            .foregroundStyle(.green)
                        Button("Verify another device") {
                            Task { await messenger.requestDeviceVerification() }
                        }
                    case .failed:
                        Text("Verification failed. The device remains unverified.")
                            .font(.footnote).foregroundStyle(.orange)
                        Button("Try again") { Task { await messenger.requestDeviceVerification() } }
                    case .cancelled:
                        Text("Verification was cancelled. The device remains unverified.")
                            .font(.footnote).foregroundStyle(.secondary)
                        Button("Try again") { Task { await messenger.requestDeviceVerification() } }
                    }
                }
            }
            .navigationTitle("Privacy")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("Done") { dismiss() }
                }
            }
        }
    }
}

private struct ConversationRow: View {
    let conversation: Conversation

    var body: some View {
        HStack(alignment: .top, spacing: 14) {
            Text(conversation.isInvitation ? "INVITE" : (conversation.isGroup ? "GROUP" : "LINE"))
                .font(.system(size: 9, weight: .bold, design: .rounded))
                .tracking(0.8)
                .foregroundStyle(Color.accentColor)
                .frame(width: 42, height: 42)
                .background(Color.accentColor.opacity(0.09), in: RoundedRectangle(cornerRadius: 12))
            VStack(alignment: .leading, spacing: 5) {
                HStack(alignment: .firstTextBaseline) {
                    Text(conversation.title).font(.headline).foregroundStyle(.primary).lineLimit(1)
                    Spacer(minLength: 8)
                    Text(shortTime(conversation.timestamp)).font(.caption.monospacedDigit()).foregroundStyle(.secondary)
                }
                Text(conversation.isInvitation ? "Invitation received" : conversation.preview)
                    .font(.subheadline).foregroundStyle(.secondary).lineLimit(1)
                HStack(spacing: 6) {
                    Image(systemName: conversation.isEncrypted ? "lock.fill" : "exclamationmark.lock.fill")
                    Text(conversation.isInvitation
                         ? (conversation.isEncrypted ? "ENCRYPTED INVITATION" : "NOT ENCRYPTED · CANNOT JOIN")
                         : (conversation.isEncrypted ? "ENCRYPTED" : "PAUSED · NOT ENCRYPTED"))
                        .tracking(0.8)
                    if conversation.unreadCount > 0 { Spacer(); Text("\(conversation.unreadCount) NEW").tracking(0.6) }
                }
                .font(.system(size: 9, weight: .semibold, design: .rounded))
                .foregroundStyle(conversation.isEncrypted ? Color.accentColor : Color.orange)
                .padding(.top, 2)
            }
        }
        .contentShape(Rectangle())
        .privacySensitive()
    }

    private func shortTime(_ millis: UInt64) -> String {
        guard millis > 0 else { return "" }
        return Date(timeIntervalSince1970: TimeInterval(millis) / 1_000).formatted(date: .omitted, time: .shortened)
    }
}

private struct EmptyState<Action: View>: View {
    let title: String
    let detail: String
    let symbol: String
    @ViewBuilder let action: () -> Action

    var body: some View {
        VStack(spacing: 12) {
            Image(systemName: symbol).font(.system(size: 34)).foregroundStyle(Color.accentColor)
            Text(title).font(.headline)
            Text(detail).font(.subheadline).foregroundStyle(.secondary).multilineTextAlignment(.center)
            action()
        }
        .padding(28)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }
}

private struct SignInScreen: View {
    @EnvironmentObject private var messenger: MessengerStore

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    VStack(alignment: .leading, spacing: 10) {
                        Label("PRIVATE LINE", systemImage: "waveform.path")
                            .font(.caption.weight(.bold)).tracking(1.2).foregroundStyle(Color.accentColor)
                        Text("Your conversations stay yours.")
                            .font(.title2.weight(.semibold))
                        Text("Messages are encrypted on this device. The homeserver carries ciphertext and cannot read your chats.")
                            .font(.subheadline).foregroundStyle(.secondary)
                        Text("Keep this device and its encryption keys safe. If they are lost, older messages may not be recoverable.")
                            .font(.footnote).foregroundStyle(.secondary)
                    }
                    .padding(.vertical, 8)
                    .listRowBackground(Color.clear)
                }
                Section("PRIVATE HOMESERVER") {
                    TextField("https://chat.example.org", text: $messenger.homeserver)
                        .textInputAutocapitalization(.never).keyboardType(.URL).autocorrectionDisabled()
                    TextField("@you:example.org", text: $messenger.username)
                        .textInputAutocapitalization(.never).autocorrectionDisabled().privacySensitive()
                    SecureField("Password", text: $messenger.password)
                }
                Section {
                    Button {
                        Task { await messenger.signIn() }
                    } label: {
                        HStack {
                            Spacer()
                            if messenger.isBusy { ProgressView().padding(.trailing, 5) }
                            Text("Connect securely").fontWeight(.semibold)
                            Spacer()
                        }
                    }
                    .disabled(messenger.isBusy)
                } footer: {
                    Text("Accounts are provided by your private homeserver. No public directory or phone contact upload is used.")
                }
            }
            .navigationTitle("Private Messenger")
        }
    }
}

private struct NewConversationScreen: View {
    @EnvironmentObject private var messenger: MessengerStore
    @Environment(\.dismiss) private var dismiss
    @State private var matrixIds = ""
    @State private var name = ""
    @State private var isGroup = false

    var body: some View {
        NavigationStack {
            Form {
                Picker("Conversation type", selection: $isGroup) {
                    Text("One-to-one").tag(false)
                    Text("Group chat").tag(true)
                }
                .pickerStyle(.segmented)
                Section {
                    TextField(
                        isGroup ? "Matrix IDs, separated by commas or new lines" : "@friend:your-server.org",
                        text: $matrixIds,
                        axis: isGroup ? .vertical : .horizontal
                    )
                        .lineLimit(isGroup ? 2...4 : 1...1)
                        .textInputAutocapitalization(.never).autocorrectionDisabled()
                    TextField(isGroup ? "Optional group name" : "Optional conversation name", text: $name)
                } header: {
                    Text(isGroup ? "START AN ENCRYPTED GROUP" : "START A ONE-TO-ONE CONVERSATION")
                } footer: {
                    Text(isGroup
                         ? "Invite at least two people. The room is private and invite-only. Messages are end-to-end encrypted; the homeserver can see room membership and any room name."
                         : "This private conversation is created with encryption enabled. The homeserver can see room membership and any room name.")
                }
                Section {
                    Button {
                        Task {
                            await messenger.createConversation(matrixIds: matrixIds, name: name, isGroup: isGroup)
                            if messenger.currentRoomId != nil { dismiss() }
                        }
                    } label: {
                            HStack {
                                Spacer()
                                Text(isGroup ? "Create encrypted group" : "Create encrypted line").fontWeight(.semibold)
                                Spacer()
                            }
                    }
                    .disabled(messenger.isBusy)
                }
            }
            .navigationTitle("New conversation")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .navigationBarLeading) { Button("Cancel") { dismiss() } } }
        }
    }
}

private struct ChatScreen: View {
    @EnvironmentObject private var messenger: MessengerStore
    @Environment(\.scenePhase) private var scenePhase
    @Binding var showingPrivacySettings: Bool
    @StateObject private var voiceRecorder = VoiceNoteRecorder()
    @StateObject private var voicePlayback = VoiceNotePlaybackController()
    @State private var messageText = ""
    @State private var isSearchingMessages = false
    @State private var showingPhotoPicker = false
    @State private var showingFileImporter = false
    @State private var selectedPhotoVideo: PhotosPickerItem?
    @State private var attachmentTargetRoomId: String?
    @State private var isImportingAttachment = false
    @State private var openedAttachment: PreviewDocument?
    @State private var attachmentPreviewURLToDelete: URL?
    @State private var openingAttachmentID: String?
    @State private var loadingAudioMessageID: String?
    @State private var editingMessage: ChatMessage?
    @State private var messagePendingRedaction: ChatMessage?
    @FocusState private var composerFocused: Bool

    private var visibleMessages: [ChatMessage] {
        messenger.messages
    }

    var body: some View {
        NavigationStack {
            VStack(spacing: 0) {
                HStack(spacing: 7) {
                    Circle().fill(messenger.connection == "Connected" ? Color.green : Color.orange).frame(width: 7, height: 7)
                    Text(messenger.connection.uppercased()).tracking(1)
                    if messenger.currentRoomIsGroup {
                        Label("GROUP", systemImage: "person.3.fill")
                            .foregroundStyle(Color.accentColor)
                    }
                    Spacer()
                    Label(messenger.currentRoomEncrypted ? "ENCRYPTED" : "PAUSED", systemImage: "lock.fill")
                        .foregroundStyle(messenger.currentRoomEncrypted ? Color.accentColor : Color.orange)
                        .tracking(0.8)
                    if messenger.currentPeerUserId != nil {
                        switch messenger.currentPeerTrust {
                        case .verified:
                            Label("VERIFIED", systemImage: "checkmark.shield.fill").foregroundStyle(.green)
                        case .changed:
                            Label("IDENTITY CHANGED", systemImage: "exclamationmark.shield.fill").foregroundStyle(.red)
                        case .unverified:
                            Label("NOT VERIFIED", systemImage: "checkmark.shield").foregroundStyle(.orange)
                        case .unknown:
                            EmptyView()
                        }
                    }
                }
                .font(.system(size: 9, weight: .bold, design: .rounded))
                .padding(.horizontal, 16).padding(.vertical, 9)
                .background(Color(uiColor: .secondarySystemBackground))

                if !messenger.typingUsers.isEmpty {
                    HStack {
                        Text("\(messenger.typingUsers.prefix(2).joined(separator: ", ")) \(messenger.typingUsers.count == 1 ? "is" : "are") typing…")
                            .font(.caption.weight(.medium)).foregroundStyle(Color.accentColor)
                        Spacer()
                    }
                    .padding(.horizontal, 18).padding(.vertical, 5)
                }

                if isSearchingMessages {
                    HStack(spacing: 10) {
                        Image(systemName: "magnifyingglass").foregroundStyle(.secondary)
                        TextField("Search encrypted messages", text: $messenger.messageSearchQuery)
                            .textInputAutocapitalization(.never).autocorrectionDisabled()
                        Button { messenger.messageSearchQuery = "" } label: { Image(systemName: "xmark.circle.fill") }
                            .disabled(messenger.messageSearchQuery.isEmpty)
                            .accessibilityLabel("Clear message search")
                    }
                    .padding(.horizontal, 14).padding(.vertical, 9)
                    .background(Color(uiColor: .secondarySystemBackground), in: RoundedRectangle(cornerRadius: 13))
                    .padding(.horizontal, 14).padding(.vertical, 7)
                }

                if isSearchingMessages {
                    if messenger.searchResults.isEmpty {
                        VStack(spacing: 10) {
                            if messenger.messageSearchQuery.isEmpty {
                                Text("Search encrypted messages across your conversations on this device.")
                                    .foregroundStyle(.secondary)
                                    .multilineTextAlignment(.center)
                            } else if messenger.searchLoading {
                                ProgressView("Searching your encrypted index…")
                            } else {
                                Text("No matching messages in your local index.")
                                    .foregroundStyle(.secondary)
                                if messenger.searchHasMore {
                                    Button("Search older results", action: messenger.paginateMessageSearch)
                                }
                            }
                        }
                        .padding(28).frame(maxWidth: .infinity, maxHeight: .infinity)
                    }
                    else {
                        ScrollView {
                            LazyVStack(spacing: 8) {
                                ForEach(messenger.searchResults) { hit in
                                    Button {
                                        isSearchingMessages = false
                                        composerFocused = false
                                        Task { await messenger.openSearchHit(hit) }
                                    } label: {
                                        SearchResultRow(hit: hit)
                                    }
                                    .buttonStyle(.plain)
                                    Divider()
                                }
                                if messenger.searchHasMore {
                                    Button(messenger.searchLoading ? "Loading…" : "Load more results", action: messenger.paginateMessageSearch)
                                        .disabled(messenger.searchLoading)
                                        .padding(.vertical, 12)
                                }
                            }
                            .padding(.horizontal, 16).padding(.vertical, 12)
                        }
                    }
                } else {
                    GeometryReader { viewport in
                        ScrollViewReader { proxy in
                            ScrollView {
                                LazyVStack(spacing: 14) {
                                    ForEach(visibleMessages) { message in
                                        MessageRow(
                                            message: message,
                                            isGroup: messenger.currentRoomIsGroup,
                                            onRetry: messenger.retryFailedMessages,
                                            onReply: { messenger.setReplyTarget($0) },
                                            onReact: { message, emoji in Task { await messenger.toggleReaction(message, key: emoji) } },
                                            onEdit: { editingMessage = $0 },
                                            onRedact: { messagePendingRedaction = $0 },
                                            onOpenAttachment: openAttachment,
                                            isAudioLoading: loadingAudioMessageID == message.id,
                                            isAudioPlaying: voicePlayback.playingMessageId == message.id
                                        )
                                        .background {
                                            GeometryReader { frame in
                                                Color.clear.preference(
                                                    key: MessageFramePreferenceKey.self,
                                                    value: [message.id: frame.frame(in: .named("messageViewport"))]
                                                )
                                            }
                                        }
                                        .id(message.id)
                                    }
                                }
                                .padding(.horizontal, 16).padding(.vertical, 18)
                            }
                            .coordinateSpace(name: "messageViewport")
                            .onPreferenceChange(MessageFramePreferenceKey.self) { frames in
                                let bounds = CGRect(origin: .zero, size: viewport.size)
                                let visibleIds = Set(visibleMessages.compactMap { message -> String? in
                                    guard let frame = frames[message.id] else { return nil }
                                    let intersection = frame.intersection(bounds)
                                    guard !intersection.isNull,
                                          intersection.width >= min(frame.width, 48),
                                          intersection.height >= min(frame.height, 48) else { return nil }
                                    return message.id
                                })
                                messenger.markVisibleIncomingMessagesRead(visibleIds)
                            }
                            .onChange(of: visibleMessages.count) { _ in
                                if let last = visibleMessages.last { withAnimation(.easeOut(duration: 0.18)) { proxy.scrollTo(last.id, anchor: .bottom) } }
                            }
                            .overlay {
                                if visibleMessages.isEmpty {
                                    EmptyState(title: "Open line", detail: "Messages in this conversation are end-to-end encrypted.", symbol: "waveform.path") { EmptyView() }
                                }
                            }
                        }
                    }
                }

                if !isSearchingMessages, let target = messenger.replyTarget {
                    HStack(spacing: 10) {
                        VStack(alignment: .leading, spacing: 3) {
                            Text("Replying to \(target.isOwn ? "yourself" : target.sender)")
                                .font(.caption.weight(.semibold)).foregroundStyle(Color.accentColor)
                            Text(target.body).font(.caption).foregroundStyle(.secondary).lineLimit(1)
                        }
                        Spacer(minLength: 6)
                        Button { messenger.setReplyTarget(nil) } label: { Image(systemName: "xmark.circle.fill") }
                            .accessibilityLabel("Cancel reply")
                    }
                    .padding(.horizontal, 18).padding(.vertical, 7)
                    .background(Color(uiColor: .secondarySystemBackground))
                }

                if !isSearchingMessages {
                    if messenger.pendingAttachmentForCurrentRoom {
                        HStack(spacing: 10) {
                            Image(systemName: "arrow.up.doc.fill").foregroundStyle(Color.accentColor)
                            Text(messenger.pendingAttachmentDisplayName).lineLimit(1).font(.caption.weight(.medium))
                            Spacer(minLength: 4)
                            Button("Retry", action: messenger.retryPendingAttachment)
                                .font(.caption.weight(.semibold))
                                .disabled(messenger.isSendingAttachment || !messenger.isRoomTimelineReady || messenger.pendingAttachmentURL == nil)
                            Button { messenger.discardPendingAttachment() } label: {
                                Image(systemName: "xmark.circle.fill").foregroundStyle(.secondary)
                            }
                            .disabled(messenger.isSendingAttachment || !messenger.canDiscardPendingAttachment)
                            .accessibilityLabel("Discard pending attachment")
                        }
                        .padding(.horizontal, 16).padding(.vertical, 8)
                        .background(Color(uiColor: .secondarySystemBackground))
                    } else if messenger.hasPendingAttachment {
                        HStack(spacing: 8) {
                            Image(systemName: "clock.arrow.circlepath").foregroundStyle(.secondary)
                            Text("An attachment is saved for another conversation. Open that conversation to retry or discard it.")
                                .font(.caption).foregroundStyle(.secondary)
                            Spacer(minLength: 0)
                        }
                        .padding(.horizontal, 16).padding(.vertical, 8)
                    } else if messenger.isSendingAttachment {
                        HStack(spacing: 8) {
                            ProgressView()
                            Text("Encrypting and sending attachment…").font(.caption).foregroundStyle(.secondary)
                            Spacer()
                        }
                        .padding(.horizontal, 16).padding(.vertical, 8)
                    }

                    if voiceRecorder.isRecording {
                        HStack(spacing: 12) {
                            Button {
                                voiceRecorder.cancel()
                                UIImpactFeedbackGenerator(style: .light).impactOccurred()
                            } label: {
                                Image(systemName: "trash.circle.fill").font(.system(size: 30)).foregroundStyle(.secondary)
                            }
                            .accessibilityLabel("Cancel voice recording")
                            .accessibilityHint("Deletes this recording from this device")

                            Circle().fill(.red).frame(width: 8, height: 8)
                                .accessibilityHidden(true)
                            Text("Recording · \(formatAudioTime(voiceRecorder.elapsed))")
                                .font(.subheadline.monospacedDigit().weight(.medium))
                                .accessibilityElement(children: .ignore)
                                .accessibilityLabel("Recording time")
                                .accessibilityValue(formatAudioTime(voiceRecorder.elapsed))
                            Spacer(minLength: 4)
                            Button {
                                do {
                                    _ = try voiceRecorder.finishRecording()
                                    UIImpactFeedbackGenerator(style: .medium).impactOccurred()
                                } catch VoiceNoteError.recordingTooShort {
                                    messenger.errorMessage = "Record a little longer before sending a voice note."
                                } catch {
                                    messenger.errorMessage = "The recording could not be saved. Please try again."
                                }
                            } label: {
                                Image(systemName: "stop.circle.fill").font(.system(size: 34)).foregroundStyle(.red)
                            }
                            .disabled(!messenger.currentRoomEncrypted || messenger.isSendingAttachment)
                            .accessibilityLabel("Finish voice recording")
                        }
                        .padding(.horizontal, 16).padding(.vertical, 10)
                        .background(.bar)
                    } else if let recordedURL = voiceRecorder.recordedURL {
                        HStack(spacing: 10) {
                            Button {
                                voicePlayback.stop()
                                voiceRecorder.cancel()
                            } label: {
                                Image(systemName: "trash.circle.fill").font(.system(size: 29)).foregroundStyle(.secondary)
                            }
                            .accessibilityLabel("Discard voice note")
                            .accessibilityHint("Deletes this recording from this device")

                            Button {
                                if voicePlayback.playingMessageId == "voice-note-preview" {
                                    voicePlayback.stop()
                                } else {
                                    do {
                                        try voicePlayback.play(url: recordedURL, messageId: "voice-note-preview", deleteWhenFinished: false)
                                    } catch {
                                        messenger.errorMessage = "This voice note could not be played on this device."
                                    }
                                }
                            } label: {
                                Image(systemName: voicePlayback.playingMessageId == "voice-note-preview" ? "stop.fill" : "play.fill")
                                    .font(.system(size: 18, weight: .semibold))
                                    .frame(width: 38, height: 38)
                                    .background(Color.accentColor.opacity(0.12), in: Circle())
                            }
                            .accessibilityLabel(voicePlayback.playingMessageId == "voice-note-preview" ? "Stop voice note preview" : "Play voice note preview")

                            VStack(alignment: .leading, spacing: 2) {
                                Text("Voice note ready").font(.subheadline.weight(.semibold))
                                Text(formatAudioTime(voiceRecorder.elapsed)).font(.caption.monospacedDigit()).foregroundStyle(.secondary)
                            }
                            Spacer(minLength: 4)
                            Button {
                                guard let roomId = messenger.currentRoomId else { return }
                                let duration = voiceRecorder.elapsed
                                guard let url = voiceRecorder.takeRecording() else { return }
                                voicePlayback.stop()
                                Task {
                                    await messenger.sendAttachment(fileURL: url, forRoomId: roomId,
                                                                    audioDuration: duration)
                                }
                                UIImpactFeedbackGenerator(style: .medium).impactOccurred()
                            } label: {
                                Image(systemName: "arrow.up.circle.fill").font(.system(size: 34))
                            }
                            .disabled(!messenger.currentRoomEncrypted || messenger.isSendingAttachment || messenger.hasPendingAttachment)
                            .accessibilityLabel("Send encrypted voice note")
                        }
                        .padding(.horizontal, 14).padding(.vertical, 10)
                        .background(.bar)
                    } else {
                    HStack(alignment: .bottom, spacing: 10) {
                        Menu {
                            Button {
                                attachmentTargetRoomId = messenger.currentRoomId
                                showingPhotoPicker = true
                            } label: {
                                Label("Photo or video", systemImage: "photo")
                            }
                            Button {
                                attachmentTargetRoomId = messenger.currentRoomId
                                showingFileImporter = true
                            } label: {
                                Label("File or audio", systemImage: "doc")
                            }
                        } label: {
                            Image(systemName: "plus.circle.fill").font(.system(size: 29))
                        }
                        .disabled(!messenger.currentRoomEncrypted || messenger.isSendingAttachment || isImportingAttachment || messenger.hasPendingAttachment)
                        .accessibilityLabel("Attach photo, video, or file")

                        TextField("Write a message", text: $messageText, axis: .vertical)
                            .lineLimit(1...5).focused($composerFocused)
                            .disabled(messenger.isBusy)
                            .padding(.horizontal, 13).padding(.vertical, 10)
                            .background(Color(uiColor: .secondarySystemBackground), in: RoundedRectangle(cornerRadius: 20))
                            .privacySensitive()
                        if messageText.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                            Button {
                                composerFocused = false
                                voicePlayback.stop()
                                Task {
                                    do {
                                        try await voiceRecorder.startRecording()
                                    } catch VoiceNoteError.microphonePermissionDenied {
                                        messenger.errorMessage = "Microphone access is off. Allow it in Settings to record a voice note."
                                    } catch {
                                        messenger.errorMessage = "Couldn't start voice recording. Check microphone access and try again."
                                    }
                                }
                            } label: {
                                if voiceRecorder.isRequestingPermission {
                                    ProgressView().frame(width: 34, height: 34)
                                } else {
                                    Image(systemName: "mic.circle.fill").font(.system(size: 34))
                                }
                            }
                            .disabled(!messenger.currentRoomEncrypted || messenger.isSendingAttachment ||
                                      messenger.hasPendingAttachment || isImportingAttachment || voiceRecorder.isRequestingPermission)
                            .accessibilityLabel(voiceRecorder.isRequestingPermission ? "Requesting microphone access" : "Record voice note")
                            .accessibilityHint("Records a private audio message that will be encrypted before sending")
                        } else {
                            Button {
                                let outgoing = messageText
                                messageText = ""
                                messenger.updateDraft("")
                                Task { await messenger.sendMessage(outgoing) }
                            } label: {
                                Image(systemName: "arrow.up.circle.fill").font(.system(size: 34))
                            }
                            .disabled(!messenger.currentRoomEncrypted || messenger.isSendingAttachment)
                            .accessibilityLabel("Send encrypted message")
                        }
                    }
                    .padding(.horizontal, 14).padding(.vertical, 10)
                    .background(.bar)
                    }
                }
            }
            .navigationTitle(messenger.currentRoomTitle)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .navigationBarLeading) {
                    Button { messenger.closeConversation() } label: { Image(systemName: "chevron.left") }
                        .accessibilityLabel("Back to conversations")
                }
                ToolbarItem(placement: .navigationBarTrailing) {
                    Button {
                        isSearchingMessages.toggle()
                        messenger.messageSearchQuery = ""
                    } label: {
                        Image(systemName: isSearchingMessages ? "xmark" : "magnifyingglass")
                    }
                    .accessibilityLabel(isSearchingMessages ? "Close message search" : "Search messages")
                }
                ToolbarItem(placement: .navigationBarTrailing) {
                    Button { showingPrivacySettings = true } label: { Image(systemName: "checkmark.shield") }
                        .disabled(!messenger.currentRoomEncrypted)
                        .accessibilityLabel("Verify this conversation")
                }
            }
            .sheet(item: $editingMessage) { message in
                MessageEditSheet(message: message) { updatedBody in
                    editingMessage = nil
                    Task { await messenger.editMessage(message, newBody: updatedBody) }
                }
                .modifier(PrivateScenePrivacyShield())
                .presentationDetents([.medium, .large])
                .presentationDragIndicator(.visible)
            }
            .confirmationDialog(
                "Remove this message?",
                isPresented: Binding(
                    get: { messagePendingRedaction != nil },
                    set: { if !$0 { messagePendingRedaction = nil } }
                ),
                titleVisibility: .visible
            ) {
                Button("Remove message", role: .destructive) {
                    guard let message = messagePendingRedaction else { return }
                    messagePendingRedaction = nil
                    Task { await messenger.redactMessage(message) }
                }
                Button("Cancel", role: .cancel) { messagePendingRedaction = nil }
            } message: {
                Text("This removes the message for everyone in the conversation.")
            }
            .onAppear { messageText = messenger.draft }
            .onChange(of: messageText) { text in messenger.updateDraft(text) }
            .onChange(of: messenger.messageSearchQuery) { query in messenger.searchMessagesDebounced(query) }
            .onChange(of: messenger.draft) { text in
                if messageText != text { messageText = text }
            }
            .onChange(of: scenePhase) { phase in
                guard phase == .background else { return }
                voicePlayback.stop()
                if voiceRecorder.isRecording {
                    voiceRecorder.cancel()
                    messenger.errorMessage = "Recording stopped when the app went to the background. Start another voice note when ready."
                }
            }
            .onReceive(NotificationCenter.default.publisher(for: AVAudioSession.interruptionNotification)) { notification in
                let stoppedRecording = voiceRecorder.handleAudioInterruption(notification)
                voicePlayback.stop()
                if stoppedRecording {
                    messenger.errorMessage = "Recording stopped because audio was interrupted. Start another voice note when ready."
                }
            }
            .photosPicker(
                isPresented: $showingPhotoPicker,
                selection: $selectedPhotoVideo,
                matching: .any(of: [.images, .videos])
            )
            .onChange(of: selectedPhotoVideo) { item in
                guard let item else { return }
                isImportingAttachment = true
                let targetRoomId = attachmentTargetRoomId
                Task {
                    var stagedURL: URL?
                    do {
                        guard let imported = try await item.loadTransferable(type: ImportedMediaFile.self) else {
                            throw MessengerMediaImportError.unavailable
                        }
                        stagedURL = imported.url
                        guard let targetRoomId else { throw MessengerMediaImportError.unavailable }
                        await messenger.sendAttachment(fileURL: imported.url, forRoomId: targetRoomId)
                    } catch {
                        if let stagedURL { MessengerAttachmentOutbox.removeStagedFile(at: stagedURL) }
                        messenger.errorMessage = "Couldn't import that photo or video. Choose it again."
                    }
                    selectedPhotoVideo = nil
                    attachmentTargetRoomId = nil
                    isImportingAttachment = false
                }
            }
            .fileImporter(
                isPresented: $showingFileImporter,
                allowedContentTypes: [.item],
                allowsMultipleSelection: false
            ) { result in
                isImportingAttachment = true
                var stagedURL: URL?
                do {
                    let urls = try result.get()
                    guard let source = urls.first else { throw MessengerMediaImportError.unavailable }
                    guard let targetRoomId = attachmentTargetRoomId else { throw MessengerMediaImportError.unavailable }
                    let didStartAccess = source.startAccessingSecurityScopedResource()
                    defer { if didStartAccess { source.stopAccessingSecurityScopedResource() } }
                    let staged = try MessengerAttachmentOutbox.stagePickedFile(from: source)
                    stagedURL = staged
                    Task {
                        await messenger.sendAttachment(fileURL: staged, forRoomId: targetRoomId)
                        attachmentTargetRoomId = nil
                        isImportingAttachment = false
                    }
                } catch {
                    if let stagedURL { MessengerAttachmentOutbox.removeStagedFile(at: stagedURL) }
                    messenger.errorMessage = "Couldn't import that file. Choose it again."
                    attachmentTargetRoomId = nil
                    isImportingAttachment = false
                }
            }
            .sheet(item: $openedAttachment, onDismiss: {
                // SwiftUI can clear the item binding before calling onDismiss;
                // retain the protected temporary file URL separately so every
                // decrypted preview is deleted when its sheet closes.
                if let url = attachmentPreviewURLToDelete { messenger.removeTemporaryMedia(at: url) }
                attachmentPreviewURLToDelete = nil
                openedAttachment = nil
                openingAttachmentID = nil
            }) { document in
                QuickLookFilePreview(url: document.url)
                    .modifier(PrivateScenePrivacyShield())
                    .ignoresSafeArea()
            }
            .onDisappear {
                voicePlayback.stop()
                voiceRecorder.cancel()
            }
        }
    }

    private func openAttachment(_ message: ChatMessage) {
        guard openingAttachmentID == nil, let attachment = message.attachment else { return }
        if attachment.kind == .audio {
            if voicePlayback.playingMessageId == message.id {
                voicePlayback.stop()
                return
            }
            guard loadingAudioMessageID == nil else { return }
            loadingAudioMessageID = message.id
            Task {
                guard let url = await messenger.loadAttachmentForViewing(attachment) else {
                    loadingAudioMessageID = nil
                    return
                }
                do {
                    try voicePlayback.play(url: url, messageId: message.id, deleteWhenFinished: true)
                } catch {
                    messenger.removeTemporaryMedia(at: url)
                    messenger.errorMessage = "Couldn't play this encrypted audio message. Check that this device is trusted."
                }
                loadingAudioMessageID = nil
            }
            return
        }
        openingAttachmentID = message.id
        Task {
            if let url = await messenger.loadAttachmentForViewing(attachment) {
                attachmentPreviewURLToDelete = url
                openedAttachment = PreviewDocument(url: url)
            }
            openingAttachmentID = nil
        }
    }

    private func formatAudioTime(_ seconds: TimeInterval) -> String {
        let total = max(0, Int(seconds.rounded(.down)))
        return "\(total / 60):\(String(format: "%02d", total % 60))"
    }
}

private enum MessengerMediaImportError: Error { case unavailable }

private struct PrivateScenePrivacyShield: ViewModifier {
    @Environment(\.scenePhase) private var scenePhase

    func body(content: Content) -> some View {
        content.overlay {
            if scenePhase != .active {
                VStack(spacing: 10) {
                    Image(systemName: "lock.fill")
                        .font(.system(size: 30, weight: .medium))
                        .foregroundStyle(Color.accentColor)
                    Text("Private Messenger")
                        .font(.headline)
                    Text("Content hidden while the app is inactive.")
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                }
                .frame(maxWidth: .infinity, maxHeight: .infinity)
                .background(Color(uiColor: .systemBackground).ignoresSafeArea())
                .contentShape(Rectangle())
                .accessibilityElement(children: .combine)
            }
        }
    }
}

private struct MessageRow: View {
    let message: ChatMessage
    let isGroup: Bool
    let onRetry: () -> Void
    let onReply: (ChatMessage) -> Void
    let onReact: (ChatMessage, String) -> Void
    let onEdit: (ChatMessage) -> Void
    let onRedact: (ChatMessage) -> Void
    let onOpenAttachment: (ChatMessage) -> Void
    var isAudioLoading = false
    var isAudioPlaying = false

    var body: some View {
        HStack {
            if message.isOwn { Spacer(minLength: 48) }
            VStack(alignment: .leading, spacing: 5) {
                if !message.isOwn { Text(message.sender).font(.caption.weight(.semibold)).foregroundStyle(Color.accentColor) }
                if message.replyToEventId != nil { Text("↪ Reply").font(.caption2.weight(.semibold)).foregroundStyle(Color.accentColor) }
                if let attachment = message.attachment {
                    Button { onOpenAttachment(message) } label: {
                        HStack(spacing: 11) {
                            Image(systemName: attachment.kind == .audio
                                    ? (isAudioPlaying ? "stop.fill" : isAudioLoading ? "hourglass" : "play.fill")
                                    : attachmentSymbol(attachment.kind))
                                .font(.title3).foregroundStyle(Color.accentColor)
                                .frame(width: 34, height: 34)
                                .background(Color.accentColor.opacity(0.1), in: RoundedRectangle(cornerRadius: 10))
                            VStack(alignment: .leading, spacing: 3) {
                                Text(attachment.kind == .audio ? "Audio message" : attachment.fileName)
                                    .font(.subheadline.weight(.semibold)).lineLimit(1)
                                Text(attachment.kind == .audio
                                     ? (isAudioPlaying ? "Stop playback" : isAudioLoading ? "Decrypting audio…" : "Play encrypted audio")
                                     : "Tap to decrypt and open")
                                    .font(.caption2).foregroundStyle(.secondary)
                            }
                            Spacer(minLength: 0)
                            Image(systemName: "lock.open.fill").font(.caption).foregroundStyle(.secondary)
                        }
                        .padding(9)
                        .frame(minWidth: 195, maxWidth: 270, alignment: .leading)
                        .background(Color(uiColor: .tertiarySystemBackground), in: RoundedRectangle(cornerRadius: 12))
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel(attachment.kind == .audio
                                        ? (isAudioPlaying ? "Stop audio message from \(message.sender)" : "Play encrypted audio message from \(message.sender)")
                                        : "Decrypt and open \(attachment.fileName)")
                } else {
                    Text(message.body).font(.body).textSelection(.enabled)
                }
                HStack(spacing: 5) {
                    Text(Date(timeIntervalSince1970: TimeInterval(message.timestamp) / 1_000).formatted(date: .omitted, time: .shortened))
                    if message.isOwn {
                        let readLabel = isGroup ? "Seen" : "Read"
                        let deliveryLabel = message.sendState.hasPrefix("Delivered to ")
                            ? message.sendState
                            : (message.hasBeenRead ? readLabel : message.sendState)
                        Text("· \(deliveryLabel.uppercased())")
                            .accessibilityLabel(deliveryLabel)
                    }
                }
                .font(.system(size: 9, weight: .medium, design: .rounded)).tracking(0.5).foregroundStyle(.secondary)
                if message.isOwn && message.canRetry {
                    Button("Retry encrypted send", action: onRetry)
                        .font(.caption.weight(.semibold))
                }
                if !message.reactions.isEmpty {
                    HStack(spacing: 5) {
                        ForEach(message.reactions) { reaction in
                            Button { onReact(message, reaction.key) } label: {
                                Text("\(reaction.key) \(reaction.count)")
                                    .font(.caption2.weight(.semibold))
                                    .padding(.horizontal, 9).padding(.vertical, 5)
                                    .background(reaction.sentByMe ? Color.accentColor.opacity(0.18) : Color(uiColor: .tertiarySystemBackground), in: Capsule())
                            }
                            .buttonStyle(.plain)
                        }
                    }
                }
            }
            .padding(.horizontal, 13).padding(.vertical, 9)
            .background(message.isOwn ? Color.accentColor.opacity(0.12) : Color(uiColor: .secondarySystemBackground), in: RoundedRectangle(cornerRadius: 15))
            if !message.isOwn { Spacer(minLength: 48) }
        }
        .contextMenu {
            if message.canReply { Button("Reply", systemImage: "arrowshape.turn.up.left") { onReply(message) } }
            if message.canEdit { Button("Edit", systemImage: "pencil") { onEdit(message) } }
            if message.canRedact { Button("Remove message", systemImage: "trash", role: .destructive) { onRedact(message) } }
            ForEach(["👍", "❤️", "😂", "😮"], id: \.self) { emoji in
                Button("React \(emoji)", systemImage: "face.smiling") { onReact(message, emoji) }
            }
        }
        .privacySensitive()
    }

    private func attachmentSymbol(_ kind: AttachmentKind) -> String {
        switch kind {
        case .image: return "photo"
        case .video: return "video"
        case .audio: return "waveform"
        case .file: return "doc"
        }
    }
}

private struct MessageEditSheet: View {
    let message: ChatMessage
    let onSave: (String) -> Void
    @Environment(\.dismiss) private var dismiss
    @State private var bodyText: String
    @FocusState private var editorFocused: Bool

    init(message: ChatMessage, onSave: @escaping (String) -> Void) {
        self.message = message
        self.onSave = onSave
        _bodyText = State(initialValue: message.body)
    }

    var body: some View {
        NavigationStack {
            VStack(alignment: .leading, spacing: 12) {
                Text("Your edit is sent as an encrypted message update.")
                    .font(.footnote).foregroundStyle(.secondary)
                TextEditor(text: $bodyText)
                    .focused($editorFocused)
                    .scrollContentBackground(.hidden)
                    .padding(8)
                    .background(Color(uiColor: .secondarySystemBackground), in: RoundedRectangle(cornerRadius: 12))
                    .privacySensitive()
            }
            .padding()
            .navigationTitle("Edit message")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Save") { onSave(bodyText) }
                        .disabled(bodyText.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty || bodyText == message.body)
                }
            }
        }
    }
}

private struct SearchResultRow: View {
    let hit: MessageSearchHit

    var body: some View {
        VStack(alignment: .leading, spacing: 5) {
            HStack {
                Text(hit.roomTitle).font(.subheadline.weight(.semibold)).lineLimit(1)
                Spacer(minLength: 8)
                Text(Date(timeIntervalSince1970: TimeInterval(hit.timestamp) / 1_000).formatted(date: .abbreviated, time: .shortened))
                    .font(.caption2).foregroundStyle(.secondary)
            }
            Text("\(hit.sender): \(hit.body)")
                .font(.subheadline).foregroundStyle(.primary).lineLimit(3)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(.vertical, 10)
        .contentShape(Rectangle())
        .privacySensitive()
    }
}

private struct QuickLookFilePreview: UIViewControllerRepresentable {
    let url: URL

    func makeUIViewController(context: Context) -> QLPreviewController {
        let controller = QLPreviewController()
        controller.dataSource = context.coordinator
        return controller
    }

    func updateUIViewController(_ controller: QLPreviewController, context: Context) { }

    func makeCoordinator() -> Coordinator { Coordinator(url: url) }

    final class Coordinator: NSObject, QLPreviewControllerDataSource {
        private let url: URL
        init(url: URL) { self.url = url }
        func numberOfPreviewItems(in controller: QLPreviewController) -> Int { 1 }
        func previewController(_ controller: QLPreviewController, previewItemAt index: Int) -> QLPreviewItem {
            url as NSURL
        }
    }
}
