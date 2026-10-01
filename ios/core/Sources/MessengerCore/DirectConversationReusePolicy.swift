import Foundation

public enum DirectConversationMembership: String, Sendable {
    case joined
    case invited
    case left
}

public struct DirectConversationCandidate: Sendable {
    public let roomId: String
    public let isEncrypted: Bool
    public let ownMembership: DirectConversationMembership
    public let isVerificationControlRoom: Bool
    public let activeHumanMemberIds: Set<String>

    public init(
        roomId: String,
        isEncrypted: Bool,
        ownMembership: DirectConversationMembership,
        isVerificationControlRoom: Bool,
        activeHumanMemberIds: Set<String>
    ) {
        self.roomId = roomId
        self.isEncrypted = isEncrypted
        self.ownMembership = ownMembership
        self.isVerificationControlRoom = isVerificationControlRoom
        self.activeHumanMemberIds = activeHumanMemberIds
    }
}

public enum DirectConversationReusePolicy {
    public static func reusableRoomId(
        candidates: [DirectConversationCandidate],
        ownUserId: String,
        peerUserId: String
    ) -> String? {
        guard ownUserId != peerUserId else { return nil }
        // The app's human-membership snapshot excludes the local account; own membership is
        // checked independently by the caller and candidate field above.
        let expectedMembers: Set<String> = [peerUserId]
        return candidates
            .filter {
                $0.isEncrypted &&
                    $0.ownMembership == .joined &&
                    !$0.isVerificationControlRoom &&
                    $0.activeHumanMemberIds == expectedMembers
            }
            .map(\.roomId)
            .min()
    }
}
