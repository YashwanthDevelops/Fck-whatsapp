import Foundation

public enum PeerVerificationFlowState: Sendable {
    case idle
    case incomingRequest
    case waitingForPeer
    case comparingSas
    case confirming
    case verified
    case failed
    case cancelled
}

public enum PeerVerificationActionPolicy {
    public static func shouldShowVerifyAction(
        isEncrypted: Bool,
        isOneToOne: Bool,
        currentPeerUserId: String?,
        peerIsVerified: Bool,
        flowState: PeerVerificationFlowState,
        flowPeerUserId: String?
    ) -> Bool {
        guard isEncrypted, isOneToOne, let currentPeerUserId, !currentPeerUserId.isEmpty,
              !peerIsVerified else { return false }

        switch flowState {
        case .incomingRequest, .waitingForPeer, .comparingSas, .confirming:
            return false
        case .verified where flowPeerUserId == currentPeerUserId:
            return false
        case .idle, .verified, .failed, .cancelled:
            return true
        }
    }
}
