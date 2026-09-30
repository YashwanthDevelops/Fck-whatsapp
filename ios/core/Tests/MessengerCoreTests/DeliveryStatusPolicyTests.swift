import XCTest
@testable import MessengerCore

final class DeliveryStatusPolicyTests: XCTestCase {
    func testOneToOneDeliveryRequiresThePeerAcknowledgement() {
        let expected = Set(["peer"])
        XCTAssertEqual(deliveryStatusLabel(expectedMemberIds: expected, acknowledgedMemberIds: []), "Sent")

        let acknowledged = addDeliveryAcknowledgement(
            expectedMemberIds: expected,
            acknowledgedMemberIds: [],
            acknowledgingMemberId: "peer"
        )
        XCTAssertEqual(deliveryStatusLabel(expectedMemberIds: expected, acknowledgedMemberIds: acknowledged ?? []), "Delivered")
    }

    func testGroupDeliveryStaysPartialUntilAllFrozenRecipientsAcknowledge() {
        let expected = Set(["member-a", "member-b"])
        let partial = addDeliveryAcknowledgement(
            expectedMemberIds: expected,
            acknowledgedMemberIds: [],
            acknowledgingMemberId: "member-a"
        )
        XCTAssertEqual(deliveryStatusLabel(expectedMemberIds: expected, acknowledgedMemberIds: partial ?? []), "Delivered to 1 of 2")

        let complete = addDeliveryAcknowledgement(
            expectedMemberIds: expected,
            acknowledgedMemberIds: partial ?? [],
            acknowledgingMemberId: "member-b"
        )
        XCTAssertEqual(deliveryStatusLabel(expectedMemberIds: expected, acknowledgedMemberIds: complete ?? []), "Delivered to all 2")
    }

    func testUnexpectedAndDuplicateAcknowledgementsCannotChangeTheSnapshot() {
        let expected = Set(["member-a", "member-b"])
        XCTAssertNil(addDeliveryAcknowledgement(
            expectedMemberIds: expected,
            acknowledgedMemberIds: [],
            acknowledgingMemberId: "outside-member"
        ))

        let once = addDeliveryAcknowledgement(
            expectedMemberIds: expected,
            acknowledgedMemberIds: [],
            acknowledgingMemberId: "member-a"
        )
        let twice = addDeliveryAcknowledgement(
            expectedMemberIds: expected,
            acknowledgedMemberIds: once ?? [],
            acknowledgingMemberId: "member-a"
        )
        XCTAssertEqual(twice, ["member-a"])
        XCTAssertEqual(deliveryStatusLabel(
            expectedMemberIds: expected,
            acknowledgedMemberIds: ["member-a", "outside-member"]
        ), "Delivered to 1 of 2")
    }

    func testLegacyDeliveryReceiptOnlyAppliesToDirectMessages() {
        XCTAssertEqual(deliveryStatusLabel(
            expectedMemberIds: ["peer"],
            acknowledgedMemberIds: [],
            legacyDelivered: true
        ), "Delivered")
        XCTAssertEqual(deliveryStatusLabel(
            expectedMemberIds: ["member-a", "member-b"],
            acknowledgedMemberIds: [],
            legacyDelivered: true
        ), "Sent")
    }
}
