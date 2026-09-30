import Foundation

public struct HumanMembershipSnapshot: Equatable {
    public let joinedRecipientIds: Set<String>
    public let activeHumanMemberIds: Set<String>

    public init(joinedRecipientIds: Set<String>, activeHumanMemberIds: Set<String>) {
        self.joinedRecipientIds = joinedRecipientIds
        self.activeHumanMemberIds = activeHumanMemberIds
    }
}

/**
 * Invited users are relevant to active-room workflows such as peer verification,
 * but cannot decrypt events until they join and must not count as delivered recipients.
 */
public func snapshotHumanMembership(
    joinedMemberIds: Set<String>,
    invitedMemberIds: Set<String>,
    serviceMemberIds: Set<String>,
    ownUserId: String
) -> HumanMembershipSnapshot {
    let excludedIds = serviceMemberIds.union([ownUserId])
    let isHuman = { (userId: String) in
        !userId.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty && !excludedIds.contains(userId)
    }
    let joinedHumans = Set(joinedMemberIds.filter(isHuman))
    let invitedHumans = Set(invitedMemberIds.filter(isHuman))
    return HumanMembershipSnapshot(
        joinedRecipientIds: joinedHumans,
        activeHumanMemberIds: joinedHumans.union(invitedHumans)
    )
}
