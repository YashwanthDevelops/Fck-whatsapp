import SwiftUI

@main
struct PrivateMessengerApp: App {
    @StateObject private var messenger = MessengerStore()
    @Environment(\.scenePhase) private var scenePhase

    var body: some Scene {
        WindowGroup {
            ContentView()
                .environmentObject(messenger)
                .task { await messenger.restoreSession() }
                .onChange(of: scenePhase) { phase in
                    messenger.handleScenePhase(isActive: phase == .active, isBackground: phase == .background)
                }
        }
    }
}
