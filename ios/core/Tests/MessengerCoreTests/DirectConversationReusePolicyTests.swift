import XCTest
@testable import MessengerCore

final class DirectConversationReusePolicyTests: XCTestCase {
    private let ownUserId = "@alice:example.org"
    private let peerUserId = "@bob:example.org"

    func testReusesJoinedEncryptedRoomWithPeerInvitedOrJoined() {
        XCTAssertEqual(
            DirectConversationReusePolicy.reusableRoomId(
                candidates: [candidate()],
                ownUserId: ownUserId,
                peerUserId: peerUserId
            ),
            "!existing:example.org"
        )
    }

    func testDoesNotReuseGroupsUnencryptedRoomsInvitationsOrVerificationRooms() {
        let candidates = [
            candidate(roomId: "!group:example.org", members: [ownUserId, peerUserId, "@carol:example.org"]),
            candidate(roomId: "!plain:example.org", isEncrypted: false),
            candidate(roomId: "!incoming:example.org", membership: .invited),
            candidate(roomId: "!verify:example.org", isVerificationControlRoom: true)
        ]

        XCTAssertNil(DirectConversationReusePolicy.reusableRoomId(
            candidates: candidates,
            ownUserId: ownUserId,
            peerUserId: peerUserId
        ))
    }

    func testSelectionIsStableWhenOlderDuplicateRoomsAlreadyExist() {
        XCTAssertEqual(
            DirectConversationReusePolicy.reusableRoomId(
                candidates: [candidate(roomId: "!z-existing:example.org"), candidate(roomId: "!a-existing:example.org")],
                ownUserId: ownUserId,
                peerUserId: peerUserId
            ),
            "!a-existing:example.org"
        )
    }

    private func candidate(
        roomId: String = "!existing:example.org",
        isEncrypted: Bool = true,
        membership: DirectConversationMembership = .joined,
        isVerificationControlRoom: Bool = false,
        members: Set<String>? = nil
    ) -> DirectConversationCandidate {
        DirectConversationCandidate(
            roomId: roomId,
            isEncrypted: isEncrypted,
            ownMembership: membership,
            isVerificationControlRoom: isVerificationControlRoom,
            activeHumanMemberIds: members ?? [peerUserId]
        )
    }
}
