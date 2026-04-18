import SwiftUI

/// Per-conversation screen.
///
/// Alpha-0: local-only message array; no encryption, no networking.
/// Alpha-1 TODO: wire to KMP MessagingService via XCFramework bridge.
///              Messages will be encrypted via Double Ratchet before transport.
struct ChatView: View {

    // MARK: - Init

    let peerName: String

    // MARK: - State

    @State private var messageText: String = ""
    @State private var messages: [Message] = []
    @FocusState private var inputFocused: Bool

    // MARK: - Body

    var body: some View {
        ZStack {
            Color.bgDeep
                .ignoresSafeArea()

            VStack(spacing: 0) {
                messageArea
                inputBar
            }
        }
        .navigationTitle(peerName)
        .navigationBarTitleDisplayMode(.inline)
        .toolbarBackground(Color.surface, for: .navigationBar)
        .toolbarColorScheme(.dark, for: .navigationBar)
        .toolbar {
            ToolbarItem(placement: .navigationBarTrailing) {
                Image(systemName: "lock.fill")
                    .font(.system(size: 12))
                    .foregroundColor(Color.success.opacity(0.8))
            }
        }
    }

    // MARK: - Subviews

    private var messageArea: some View {
        ScrollViewReader { proxy in
            ScrollView {
                if messages.isEmpty {
                    emptyState
                } else {
                    LazyVStack(spacing: 8) {
                        ForEach(messages) { msg in
                            bubble(for: msg)
                                .id(msg.id)
                        }
                    }
                    .padding(.horizontal, 12)
                    .padding(.vertical, 8)
                }
            }
            .onChange(of: messages.count) {
                if let last = messages.last {
                    withAnimation { proxy.scrollTo(last.id, anchor: .bottom) }
                }
            }
        }
    }

    private var emptyState: some View {
        VStack(spacing: 12) {
            Spacer()
                .frame(height: 80)
            Image(systemName: "lock.shield")
                .font(.system(size: 36))
                .foregroundColor(Color.cyanAccent.opacity(0.3))
            Text("Messages are end-to-end encrypted.")
                .font(.system(size: 13))
                .foregroundColor(Color.textDim.opacity(0.6))
                .multilineTextAlignment(.center)
        }
        .padding(.horizontal, 48)
    }

    @ViewBuilder
    private func bubble(for message: Message) -> some View {
        HStack {
            if message.isOutgoing { Spacer(minLength: 48) }

            Text(message.text)
                .font(.system(size: 15))
                .foregroundColor(message.isOutgoing ? .bgDeep : .textPrimary)
                .padding(.horizontal, 12)
                .padding(.vertical, 8)
                .background(
                    message.isOutgoing
                        ? Color.cyanAccent
                        : Color.surface
                )
                .cornerRadius(14)

            if !message.isOutgoing { Spacer(minLength: 48) }
        }
    }

    private var inputBar: some View {
        HStack(spacing: 8) {
            TextField("Message", text: $messageText, axis: .vertical)
                .font(.system(size: 15))
                .foregroundColor(.textPrimary)
                .tint(.cyanAccent)
                .lineLimit(1...5)
                .padding(.horizontal, 12)
                .padding(.vertical, 10)
                .background(Color.surface)
                .cornerRadius(20)
                .focused($inputFocused)

            Button(action: sendMessage) {
                Image(systemName: "arrow.up.circle.fill")
                    .font(.system(size: 32))
                    .foregroundColor(
                        messageText.trimmingCharacters(in: .whitespaces).isEmpty
                            ? Color.textDim.opacity(0.3)
                            : Color.cyanAccent
                    )
            }
            .disabled(messageText.trimmingCharacters(in: .whitespaces).isEmpty)
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 8)
        .background(Color.surface.opacity(0.95))
    }

    // MARK: - Actions

    private func sendMessage() {
        let trimmed = messageText.trimmingCharacters(in: .whitespaces)
        guard !trimmed.isEmpty else { return }
        messages.append(Message(text: trimmed, isOutgoing: true))
        messageText = ""
        // Alpha-1: call MessagingService.sendMessage(encryptedPayload) here.
    }

    // MARK: - Message model (Alpha-0 local only)

    struct Message: Identifiable {
        let id = UUID()
        let text: String
        let isOutgoing: Bool
    }
}

// MARK: - Preview

#Preview {
    NavigationStack {
        ChatView(peerName: "alice")
    }
}
