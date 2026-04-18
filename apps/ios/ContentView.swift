import SwiftUI

/// Root view — decides which flow the user sees.
///
/// Identity persistence is handled by UserDefaults for Alpha-0.
/// Alpha-1 will replace this with the KMP IdentityManager once the
/// XCFramework is linked.
///
/// State machine:
///   .onboarding  — no identity stored → show OnboardingView
///   .chatList    — identity present   → show ChatListView
struct ContentView: View {

    // MARK: - State

    @State private var hasIdentity: Bool = IdentityStore.hasIdentity

    // MARK: - Body

    var body: some View {
        NavigationStack {
            if hasIdentity {
                ChatListView()
            } else {
                OnboardingView(onComplete: {
                    hasIdentity = true
                })
            }
        }
        .tint(.cyanAccent)
    }
}

// MARK: - IdentityStore (Alpha-0 stub)

/// Minimal key-value store backed by UserDefaults.
/// Replaced by KMP IdentityManager in Alpha-1.
enum IdentityStore {

    private static let key = "phantom.identity.id"

    static var hasIdentity: Bool {
        UserDefaults.standard.string(forKey: key) != nil
    }

    /// Persists a randomly-generated identifier as the local identity.
    /// Alpha-1 will replace this with a real cryptographic key pair via KMP.
    static func createIdentity() {
        let id = UUID().uuidString
        UserDefaults.standard.set(id, forKey: key)
    }
}
