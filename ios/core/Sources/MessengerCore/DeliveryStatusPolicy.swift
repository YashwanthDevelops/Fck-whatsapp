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
