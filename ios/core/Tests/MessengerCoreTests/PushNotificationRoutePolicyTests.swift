import XCTest
@testable import MessengerCore

final class PushNotificationRoutePolicyTests: XCTestCase {
    func testParsesOpaqueRoomAndEventIdentifiers() {
        XCTAssertEqual(
            PushNotificationRoutePolicy.parse(roomId: "!room-123:example.org", eventId: "$event-456"),
            PushNotificationRoute(roomId: "!room-123:example.org", eventId: "$event-456")
        )
    }

    func testInvalidEventIsDroppedButSafeRoomRouteSurvives() {
        XCTAssertEqual(
            PushNotificationRoutePolicy.parse(roomId: "!room-123:example.org", eventId: "$event\nprivate text"),
            PushNotificationRoute(roomId: "!room-123:example.org", eventId: nil)
        )
    }

    func testRejectsInvalidRoomIdentifiers() {
        XCTAssertNil(PushNotificationRoutePolicy.parse(roomId: "room-123", eventId: "$event-456"))
        XCTAssertNil(PushNotificationRoutePolicy.parse(roomId: "!room-123:example.org/path", eventId: nil))
    }
}
