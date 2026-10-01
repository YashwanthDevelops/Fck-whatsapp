public enum SyncRecoveryRetryPolicy {
    public static func delaySeconds(forAttempt attempt: Int) -> Int {
        let exponent = min(max(attempt, 0), 5)
        return min(1 << exponent, 30)
    }
}
