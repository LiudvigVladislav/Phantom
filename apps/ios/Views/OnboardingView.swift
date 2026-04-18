import SwiftUI

/// First-launch screen shown when no identity exists.
///
/// Alpha-0: creates a UUID-backed placeholder identity via IdentityStore.
/// Alpha-1 TODO: call KMP IdentityManager.createOrLoad(username) through the
///              XCFramework bridge and store the resulting cryptographic key pair.
struct OnboardingView: View {

    // MARK: - Init

    let onComplete: () -> Void

    // MARK: - State

    @State private var isCreating = false

    // MARK: - Body

    var body: some View {
        ZStack {
            Color.bgDeep
                .ignoresSafeArea()

            VStack(spacing: 0) {
                Spacer()
                    .frame(height: 60)

                // Ghost icon placeholder
                ghostIcon

                Spacer()
                    .frame(height: 40)

                // App title
                Text("PHANTOM")
                    .font(.system(size: 11, weight: .regular, design: .monospaced))
                    .kerning(4)
                    .foregroundColor(.cyanAccent)

                Spacer()
                    .frame(height: 24)

                // Tagline — mirrors Android OnboardingScreen copy
                Text("Your presence,\nknown to no one.")
                    .font(.system(size: 28, weight: .light))
                    .multilineTextAlignment(.center)
                    .lineSpacing(6)
                    .foregroundColor(.textPrimary)

                Spacer()

                // Primary CTA
                Button(action: createIdentity) {
                    if isCreating {
                        ProgressView()
                            .progressViewStyle(CircularProgressViewStyle(tint: .bgDeep))
                    } else {
                        Text("CREATE IDENTITY")
                            .font(.system(size: 11, weight: .medium, design: .monospaced))
                            .kerning(3)
                            .foregroundColor(.bgDeep)
                    }
                }
                .frame(maxWidth: .infinity)
                .frame(height: 52)
                .background(isCreating ? Color.cyanAccent.opacity(0.4) : Color.cyanAccent)
                .cornerRadius(2)
                .disabled(isCreating)
                .padding(.horizontal, 32)

                Spacer()
                    .frame(height: 32)
            }
            .padding(.horizontal, 32)
        }
        .navigationBarHidden(true)
    }

    // MARK: - Subviews

    private var ghostIcon: some View {
        ZStack {
            Circle()
                .strokeBorder(Color.cyanAccent.opacity(0.25), lineWidth: 1)
                .frame(width: 88, height: 88)

            Image(systemName: "person.crop.circle.badge.questionmark")
                .font(.system(size: 40))
                .foregroundColor(Color.cyanAccent.opacity(0.7))
        }
    }

    // MARK: - Actions

    private func createIdentity() {
        isCreating = true
        // Simulate brief async work so the spinner is visible to the investor demo.
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.6) {
            IdentityStore.createIdentity()
            isCreating = false
            onComplete()
        }
    }
}

// MARK: - Preview

#Preview {
    OnboardingView(onComplete: {})
}
