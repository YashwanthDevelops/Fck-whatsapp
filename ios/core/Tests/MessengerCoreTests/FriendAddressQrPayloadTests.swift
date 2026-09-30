import XCTest
@testable import MessengerCore

final class FriendAddressQrPayloadTests: XCTestCase {
    func testAndroidCompatibleAddressRoundTrips() throws {
        let encoded = try FriendAddressQrPayload.encode(
            matrixId: "@alice:matrix.example",
            homeserverUrl: "https://matrix.example/"
        )
        XCTAssertEqual(
            encoded,
            "friendline://friend?v=1&matrix_id=%40alice%3Amatrix.example&homeserver=https%3A%2F%2Fmatrix.example"
        )

        let parsed = try FriendAddressQrPayload.parse(encoded)
        XCTAssertEqual(parsed.matrixId, "@alice:matrix.example")
        XCTAssertEqual(parsed.homeserverUrl, "https://matrix.example")
        XCTAssertEqual(try parsed.resolve(forHomeserver: "https://MATRIX.example/"), "@alice:matrix.example")
    }

    func testRejectsMalformedOrVersionUnknownPayloads() {
        for rawValue in [
            "https://matrix.to/#/@alice:matrix.example",
            "friendline://other?v=1&matrix_id=%40alice%3Amatrix.example&homeserver=https%3A%2F%2Fmatrix.example",
            "friendline://friend?v=2&matrix_id=%40alice%3Amatrix.example&homeserver=https%3A%2F%2Fmatrix.example",
            "friendline://friend?v=1&v=1&matrix_id=%40alice%3Amatrix.example&homeserver=https%3A%2F%2Fmatrix.example",
        ] {
            XCTAssertThrowsError(try FriendAddressQrPayload.parse(rawValue), rawValue)
        }
    }

    func testRejectsMalformedIdsAndUntrustedHomeservers() {
        XCTAssertThrowsError(try FriendAddressQrPayload.encode(matrixId: "alice:matrix.example", homeserverUrl: "https://matrix.example"))
        XCTAssertThrowsError(try FriendAddressQrPayload.encode(matrixId: "@alice:matrix.example", homeserverUrl: "http://matrix.example"))
        XCTAssertThrowsError(try FriendAddressQrPayload.encode(matrixId: "@alice:matrix.example", homeserverUrl: "https://user:secret@matrix.example"))
    }

    func testDevelopmentHTTPIsLimitedToPrivateDevelopmentHosts() throws {
        let payload = try FriendAddressQrPayload.encode(
            matrixId: "@alice:localhost",
            homeserverUrl: "http://127.0.0.1:8008/",
            allowDevelopmentHTTP: true
        )
        XCTAssertEqual(
            try FriendAddressQrPayload.parse(payload, allowDevelopmentHTTP: true).homeserverUrl,
            "http://127.0.0.1:8008"
        )
        XCTAssertThrowsError(try FriendAddressQrPayload.parse(payload))
        XCTAssertThrowsError(try FriendAddressQrPayload.encode(
            matrixId: "@alice:example.com",
            homeserverUrl: "http://example.com",
            allowDevelopmentHTTP: true
        ))
    }

    func testRejectsContactFromDifferentHomeserver() throws {
        let payload = try FriendAddressQrPayload.parse(
            "friendline://friend?v=1&matrix_id=%40alice%3Aother.example&homeserver=https%3A%2F%2Fother.example"
        )
        XCTAssertThrowsError(try payload.resolve(forHomeserver: "https://matrix.example")) { error in
            XCTAssertEqual(error as? FriendAddressQrError, .homeserverMismatch)
        }
    }
}
