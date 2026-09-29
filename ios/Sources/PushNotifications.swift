import Foundation
import UIKit
import UserNotifications

enum PushRegistrationResult: Equatable {
    case notEnabled
    case disabled
    case registering
    case removing
    case removalPending
    case permissionRequired
    case tokenUnavailable
    case registered
    case removed
    case failed

    var displayText: String {
        switch self {
        case .notEnabled, .removed:
            return "Message notifications are off."
        case .disabled:
            return "Notifications are unavailable in this build."
        case .registering:
            return "Setting up private alerts…"
        case .removing:
            return "Removing this device from message alerts…"
        case .removalPending:
            return "This device still has a pending alert removal. Keep this account signed in and retry."
        case .permissionRequired:
            return "Allow notifications in device settings to receive alerts."
        case .tokenUnavailable:
            return "Waiting for Apple to provide a device token."
        case .registered:
            return "Enabled. Alerts use generic text and never show message content."
        case .failed:
            return "Couldn't update notification registration. Try again."
        }
    }
}

@MainActor
final class APNSTokenStore {
    static let shared = APNSTokenStore()

    private(set) var currentToken: String?
    var onTokenChange: ((String?) -> Void)?

    private init() {}

    func update(deviceToken: Data) {
        let token = deviceToken.base64EncodedString()
        guard !token.isEmpty else { return }
        currentToken = token
        onTokenChange?(token)
    }

    func clear() {
        currentToken = nil
        onTokenChange?(nil)
    }
}

/**
 * APNs permission/token and Matrix pusher boundary for MessengerStore integration.
 * Pusher identity is persisted in the device-only Keychain; access tokens are used only in live HTTPS requests.
 */
@MainActor
enum NativePushNotifications {
    static var appID: String { PushBuildSettings.appID }
    private static let appDisplayName = "Private Messenger (iOS)"
    private static let gatewayPath = "/_matrix/push/v1/notify"
    private static let pusherPath = "/_matrix/client/v3/pushers/set"
    private static let genericAPNsDefaultPayload: [String: Any] = [
        "aps": [
            "alert": [
                "title": "Private Messenger",
                "body": "New message"
            ],
            "sound": "default"
        ]
    ]

    static var isConfigured: Bool {
        PushBuildSettings.enabled && gatewayURL != nil
    }

    static var currentToken: String? {
        APNSTokenStore.shared.currentToken
    }

    static func clearDeliveredNotifications() async {
        UNUserNotificationCenter.current().removeAllDeliveredNotifications()
    }

    /** Call after a user action. Permission is never requested automatically at launch. */
    static func requestPermissionAndRegister() async throws -> Bool {
        guard isConfigured else { return false }
        let center = UNUserNotificationCenter.current()
        let current = await center.notificationSettings()
        let authorized: Bool
        switch current.authorizationStatus {
        case .authorized, .provisional, .ephemeral:
            authorized = true
        case .notDetermined:
            authorized = try await center.requestAuthorization(options: [.alert, .sound])
        case .denied:
            authorized = false
        @unknown default:
            authorized = false
        }
        if authorized {
            UIApplication.shared.registerForRemoteNotifications()
        }
        return authorized
    }

    /** Re-registers with APNs on launch after authorization, allowing Apple to rotate tokens. */
    static func refreshAPNsRegistrationIfAuthorized() async {
        guard isConfigured else { return }
        let settings = await UNUserNotificationCenter.current().notificationSettings()
        switch settings.authorizationStatus {
        case .authorized, .provisional, .ephemeral:
            UIApplication.shared.registerForRemoteNotifications()
        case .notDetermined, .denied:
            break
        @unknown default:
            break
        }
    }

    /** Register after sign-in/session restore and after each APNs token rotation. */
    static func register(
        homeserverURL: String,
        accessToken: String,
        pushToken: String,
        deviceDisplayName: String = UIDevice.current.userInterfaceIdiom == .pad ? "iPad" : "iPhone"
    ) async -> PushRegistrationResult {
        guard isConfigured else { return .disabled }
        guard !pushToken.isEmpty else { return .tokenUnavailable }
        let settings = await UNUserNotificationCenter.current().notificationSettings()
        guard settings.authorizationStatus == .authorized ||
                settings.authorizationStatus == .provisional ||
                settings.authorizationStatus == .ephemeral else {
            return .permissionRequired
        }
        return await postPusher(
            homeserverURL: homeserverURL,
            accessToken: accessToken,
            pushToken: pushToken,
            deviceDisplayName: deviceDisplayName,
            appID: appID,
            remove: false
        )
    }

    /** Call before sign-out while the Matrix access token is still valid. */
    static func unregister(
        homeserverURL: String,
        accessToken: String,
        pushToken: String,
        appID: String
    ) async -> PushRegistrationResult {
        guard !pushToken.isEmpty, !appID.isEmpty else { return .failed }
        return await postPusher(
            homeserverURL: homeserverURL,
            accessToken: accessToken,
            pushToken: pushToken,
            deviceDisplayName: "",
            appID: appID,
            remove: true
        )
    }

    private static func postPusher(
        homeserverURL: String,
        accessToken: String,
        pushToken: String,
        deviceDisplayName: String,
        appID: String,
        remove: Bool
    ) async -> PushRegistrationResult {
        guard !accessToken.isEmpty,
              let endpoint = pusherEndpoint(homeserverURL: homeserverURL) else {
            return .failed
        }

        var body: [String: Any] = ["app_id": appID, "pushkey": pushToken]
        if remove {
            body["kind"] = NSNull()
        } else {
            guard let gatewayURL else { return .disabled }
            body["kind"] = "http"
            body["app_display_name"] = appDisplayName
            body["device_display_name"] = deviceDisplayName
            body["lang"] = Locale.current.language.languageCode?.identifier ?? "en"
            body["data"] = [
                "format": "event_id_only",
                "url": gatewayURL.absoluteString,
                "default_payload": genericAPNsDefaultPayload
            ]
        }

        guard JSONSerialization.isValidJSONObject(body),
              let bodyData = try? JSONSerialization.data(withJSONObject: body) else {
            return .failed
        }

        var request = URLRequest(url: endpoint, timeoutInterval: 15)
        request.httpMethod = "POST"
        request.httpBody = bodyData
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        request.setValue("Bearer \(accessToken)", forHTTPHeaderField: "Authorization")

        do {
            let (_, response) = try await PushHTTP.session.data(for: request)
            guard let httpResponse = response as? HTTPURLResponse,
                  (200..<300).contains(httpResponse.statusCode) else {
                return .failed
            }
            return remove ? .removed : .registered
        } catch {
            return .failed
        }
    }

    private static var gatewayURL: URL? {
        let domain = PushBuildSettings.matrixDomain
        guard !domain.isEmpty,
              !domain.contains("/") && !domain.contains("@") && !domain.contains("?") && !domain.contains("#") else {
            return nil
        }
        var components = URLComponents()
        components.scheme = "https"
        components.host = domain
        components.path = gatewayPath
        guard let url = components.url, components.host != nil else { return nil }
        return url
    }

    private static func pusherEndpoint(homeserverURL: String) -> URL? {
        guard var components = URLComponents(string: homeserverURL.trimmingCharacters(in: .whitespacesAndNewlines)),
              components.scheme?.lowercased() == "https",
              let host = components.host, !host.isEmpty,
              components.user == nil, components.password == nil,
              components.query == nil, components.fragment == nil else {
            return nil
        }
        let basePath = components.percentEncodedPath.trimmingCharacters(in: CharacterSet(charactersIn: "/"))
        components.percentEncodedPath = "/" + [basePath, String(pusherPath.dropFirst())]
            .filter { !$0.isEmpty }
            .joined(separator: "/")
        return components.url
    }
}

@MainActor
private enum PushBuildSettings {
    static var appID: String {
        let configured = (Bundle.main.object(forInfoDictionaryKey: "MatrixPushAppID") as? String ?? "")
            .trimmingCharacters(in: .whitespacesAndNewlines)
        return configured.isEmpty ? "dev.friendline.messenger.ios" : configured
    }

    static var enabled: Bool {
        let raw = Bundle.main.object(forInfoDictionaryKey: "MatrixPushEnabled")
        if let value = raw as? Bool { return value }
        if let value = raw as? String {
            return ["yes", "true", "1"].contains(value.trimmingCharacters(in: .whitespacesAndNewlines).lowercased())
        }
        return false
    }

    static var matrixDomain: String {
        (Bundle.main.object(forInfoDictionaryKey: "MatrixPushDomain") as? String ?? "")
            .trimmingCharacters(in: .whitespacesAndNewlines)
    }
}

private final class PushRedirectBlocker: NSObject, URLSessionTaskDelegate {
    func urlSession(
        _ session: URLSession,
        task: URLSessionTask,
        willPerformHTTPRedirection response: HTTPURLResponse,
        newRequest request: URLRequest,
        completionHandler: @escaping (URLRequest?) -> Void
    ) {
        // Never forward the Matrix bearer token across a redirect.
        completionHandler(nil)
    }
}

private enum PushHTTP {
    static let session: URLSession = {
        let configuration = URLSessionConfiguration.ephemeral
        configuration.urlCache = nil
        configuration.httpCookieStorage = nil
        configuration.httpShouldSetCookies = false
        return URLSession(configuration: configuration, delegate: PushRedirectBlocker(), delegateQueue: nil)
    }()
}

@MainActor
final class PrivateMessengerAppDelegate: NSObject, UIApplicationDelegate, UNUserNotificationCenterDelegate {
    func application(
        _ application: UIApplication,
        didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]? = nil
    ) -> Bool {
        UNUserNotificationCenter.current().delegate = self
        return true
    }

    func application(_ application: UIApplication, didRegisterForRemoteNotificationsWithDeviceToken deviceToken: Data) {
        APNSTokenStore.shared.update(deviceToken: deviceToken)
    }

    func application(_ application: UIApplication, didFailToRegisterForRemoteNotificationsWithError error: Error) {
        APNSTokenStore.shared.clear()
        // Do not log the error or token-related details.
    }

    func userNotificationCenter(
        _ center: UNUserNotificationCenter,
        willPresent notification: UNNotification,
        withCompletionHandler completionHandler: @escaping (UNNotificationPresentationOptions) -> Void
    ) {
        // The registered event_id_only pusher supplies this same fixed generic alert.
        completionHandler([.banner, .sound])
    }

    func userNotificationCenter(
        _ center: UNUserNotificationCenter,
        didReceive response: UNNotificationResponse,
        withCompletionHandler completionHandler: @escaping () -> Void
    ) {
        // Ignore push payload fields; opening the app triggers normal Matrix synchronization.
        completionHandler()
    }
}
