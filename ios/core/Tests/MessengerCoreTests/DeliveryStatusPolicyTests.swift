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

    func testDeliveryAcknowledgementMessageRoundTripsAndRejectsMalformedTargets() throws {
        let eventId = "$event:example.org"
        let body = try XCTUnwrap(DeliveryAcknowledgementMessage.body(for: eventId))

        XCTAssertEqual(DeliveryAcknowledgementMessage.targetEventId(in: body), eventId)
        XCTAssertNil(DeliveryAcknowledgementMessage.targetEventId(in: "A normal message"))
        XCTAssertNil(DeliveryAcknowledgementMessage.targetEventId(in: "\u{2063}org.friendline.delivery-ack.v1:"))
        XCTAssertNil(DeliveryAcknowledgementMessage.targetEventId(in: body + "\n"))
    }

    func testDeliveryAcknowledgementMessageEnforcesTheEventIdLengthBound() throws {
        let maximumEventId = String(repeating: "a", count: 1024)
        let oversizedEventId = maximumEventId + "a"

        XCTAssertEqual(
            DeliveryAcknowledgementMessage.targetEventId(
                in: try XCTUnwrap(DeliveryAcknowledgementMessage.body(for: maximumEventId))
            ),
            maximumEventId
        )
        XCTAssertNil(DeliveryAcknowledgementMessage.body(for: oversizedEventId))
        XCTAssertNil(DeliveryAcknowledgementMessage.targetEventId(
            in: "\u{2063}org.friendline.delivery-ack.v1:" + oversizedEventId
        ))
    }
}
