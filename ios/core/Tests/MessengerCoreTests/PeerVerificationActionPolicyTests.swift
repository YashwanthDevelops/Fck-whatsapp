import XCTest
@testable import MessengerCore

final class PeerVerificationActionPolicyTests: XCTestCase {
    private let peer = "@bob:example.org"

    func testVerifiedTrustHidesVerifyActionAfterRefresh() {
        XCTAssertFalse(shouldShow(peerIsVerified: true))
    }

    func testSuccessfulFlowHidesActionForThatPeerBeforeTrustRefreshFinishes() {
        XCTAssertFalse(shouldShow(flowState: .verified, flowPeerUserId: peer))
        XCTAssertTrue(shouldShow(flowState: .verified, flowPeerUserId: "@carol:example.org"))
    }

    func testActiveFlowHidesActionButFailedAndCancelledFlowCanRetry() {
        XCTAssertFalse(shouldShow(flowState: .comparingSas))
        XCTAssertTrue(shouldShow(flowState: .failed))
        XCTAssertTrue(shouldShow(flowState: .cancelled))
    }

    func testActionRequiresAnEncryptedOneToOneConversation() {
        XCTAssertTrue(shouldShow())
        XCTAssertFalse(shouldShow(isEncrypted: false))
        XCTAssertFalse(shouldShow(isOneToOne: false))
        XCTAssertFalse(shouldShow(currentPeerUserId: nil))
    }

    private func shouldShow(
        isEncrypted: Bool = true,
        isOneToOne: Bool = true,
        currentPeerUserId: String? = "@bob:example.org",
        peerIsVerified: Bool = false,
        flowState: PeerVerificationFlowState = .idle,
        flowPeerUserId: String? = nil
    ) -> Bool {
        PeerVerificationActionPolicy.shouldShowVerifyAction(
            isEncrypted: isEncrypted,
            isOneToOne: isOneToOne,
            currentPeerUserId: currentPeerUserId,
            peerIsVerified: peerIsVerified,
            flowState: flowState,
            flowPeerUserId: flowPeerUserId
        )
    }
}
