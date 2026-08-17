import SwiftUI

@main
struct VoltarianELMCompanionApp: App {
    @StateObject private var session = ELMSession()

    var body: some Scene {
        WindowGroup {
            ContentView()
                .environmentObject(session)
        }
    }
}

