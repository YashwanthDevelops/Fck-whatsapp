import Foundation

public enum MatrixUserIdPolicy {
    public static func normalize(_ value: String) -> String? {
        let normalized = value.trimmingCharacters(in: .whitespacesAndNewlines)
        guard normalized.range(of: "^@[^:\\s]+:[^\\s]+$", options: .regularExpression) != nil else {
            return nil
        }
        return normalized
    }
}
