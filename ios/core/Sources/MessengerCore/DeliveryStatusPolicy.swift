public func deliveryStatusLabel(
    expectedMemberIds: Set<String>,
    acknowledgedMemberIds: Set<String>,
    fallback: String = "Sent",
    legacyDelivered: Bool = false
) -> String {
    let acknowledged = acknowledgedMemberIds.intersection(expectedMemberIds)
    if expectedMemberIds.count <= 1 {
        return legacyDelivered || (!expectedMemberIds.isEmpty && acknowledged.isSuperset(of: expectedMemberIds))
            ? "Delivered"
            : fallback
    }
    guard !acknowledged.isEmpty else { return fallback }
    return acknowledged.isSuperset(of: expectedMemberIds)
        ? "Delivered to all \(expectedMemberIds.count)"
        : "Delivered to \(acknowledged.count) of \(expectedMemberIds.count)"
}

public func addDeliveryAcknowledgement(
    expectedMemberIds: Set<String>,
    acknowledgedMemberIds: Set<String>,
    acknowledgingMemberId: String
) -> Set<String>? {
    guard expectedMemberIds.contains(acknowledgingMemberId) else { return nil }
    return acknowledgedMemberIds.intersection(expectedMemberIds).union([acknowledgingMemberId])
}

public enum DeliveryAcknowledgementMessage {
    private static let textPrefix = "\u{2063}org.friendline.delivery-ack.v1:"
    private static let maximumEventIdUTF16Length = 1024

    public static func body(for eventId: String) -> String? {
        guard isValidEventId(eventId) else { return nil }
        return textPrefix + eventId
    }

    public static func targetEventId(in body: String) -> String? {
        guard body.hasPrefix(textPrefix) else { return nil }
        let eventId = String(body.dropFirst(textPrefix.count))
        return isValidEventId(eventId) ? eventId : nil
    }

    private static func isValidEventId(_ eventId: String) -> Bool {
        !eventId.isEmpty &&
            eventId.utf16.count <= maximumEventIdUTF16Length &&
            eventId.rangeOfCharacter(from: .whitespacesAndNewlines) == nil
    }
}
