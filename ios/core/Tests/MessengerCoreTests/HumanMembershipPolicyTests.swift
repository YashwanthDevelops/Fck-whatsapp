import XCTest
@testable import MessengerCore

final class HumanMembershipPolicyTests: XCTestCase {
    func testJoinedHumanMembersAreDeliveryRecipientsAndActiveMembers() {
        let snapshot = snapshotHumanMembership(
            joinedMemberIds: ["@self:example.org", "@alice:example.org", "@bot:example.org"],
            invitedMemberIds: [],
            serviceMemberIds: ["@bot:example.org"],
            ownUserId: "@self:example.org"
        )

        XCTAssertEqual(snapshot.joinedRecipientIds, ["@alice:example.org"])
        XCTAssertEqual(snapshot.activeHumanMemberIds, ["@alice:example.org"])
    }

    func testInvitedHumanMembersAreActiveButCannotCountAsDeliveredRecipients() {
        let snapshot = snapshotHumanMembership(
            joinedMemberIds: ["@alice:example.org"],
            invitedMemberIds: ["@bob:example.org"],
            serviceMemberIds: [],
            ownUserId: "@self:example.org"
        )

        XCTAssertEqual(snapshot.joinedRecipientIds, ["@alice:example.org"])
        XCTAssertEqual(snapshot.activeHumanMemberIds, ["@alice:example.org", "@bob:example.org"])
    }

    func testSelfServiceAndBlankIdsAreExcludedFromBothSets() {
        let snapshot = snapshotHumanMembership(
            joinedMemberIds: ["@self:example.org", "@bot:example.org", "", " \n"],
            invitedMemberIds: ["@self:example.org", "@service:example.org", " "],
            serviceMemberIds: ["@bot:example.org", "@service:example.org"],
            ownUserId: "@self:example.org"
        )

        XCTAssertTrue(snapshot.joinedRecipientIds.isEmpty)
        XCTAssertTrue(snapshot.activeHumanMemberIds.isEmpty)
    }

    func testSameMemberInJoinedAndInvitedSetsAppearsOnlyOnce() {
        let snapshot = snapshotHumanMembership(
            joinedMemberIds: ["@alice:example.org"],
            invitedMemberIds: ["@alice:example.org"],
            serviceMemberIds: [],
            ownUserId: "@self:example.org"
        )

        XCTAssertEqual(snapshot.joinedRecipientIds, ["@alice:example.org"])
        XCTAssertEqual(snapshot.activeHumanMemberIds, ["@alice:example.org"])
    }
}
