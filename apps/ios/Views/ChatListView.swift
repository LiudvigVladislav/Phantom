import SwiftUI

/// Main screen showing the list of conversations.
///
/// Alpha-0: always renders the empty state.
/// Alpha-1 TODO: load conversations from KMP SqlDelightConversationRepository
///              via the XCFramework bridge and populate the list.
struct ChatListView: View {

    // MARK: - Placeholder data model (Alpha-0)

    struct ConversationPlaceholder: Identifiable {
        let id: String
        let peerName: String
        let lastMessage: String
        let timestamp: String
    }

    // Empty for Alpha-0. Replace with @StateObject ViewModel in Alpha-1.
    private let conversations: [ConversationPlaceholder] = []

    // MARK: - Body

    var body: some View {
        ZStack {
            Color.bgDeep
                .ignoresSafeArea()

            if conversations.isEmpty {
                emptyState
            } else {
                conversationList
            }
        }
        .navigationTitle("Chats")
        .navigationBarTitleDisplayMode(.large)
        .toolbarBackground(Color.bgDeep, for: .navigationBar)
        .toolbarColorScheme(.dark, for: .navigationBar)
        .toolbar {
            ToolbarItem(placement: .navigationBarTrailing) {
                Button(action: {
                    // Alpha-1: open new-conversation flow
                }) {
                    Image(systemName: "square.and.pencil")
                        .foregroundColor(.cyanAccent)
                }
            }
        }
    }

    // MARK: - Subviews

    private var emptyState: some View {
        VStack(spacing: 16) {
            Image(systemName: "bubble.left.and.bubble.right")
                .font(.system(size: 48))
                .foregroundColor(Color.textDim.opacity(0.4))

            Text("No conversations yet")
                .font(.system(size: 16, weight: .light))
                .foregroundColor(.textDim)

            Text("Tap the compose button to start one.")
                .font(.system(size: 13))
                .foregroundColor(Color.textDim.opacity(0.6))
                .multilineTextAlignment(.center)
        }
        .padding(.horizontal, 48)
    }

    private var conversationList: some View {
        List(conversations) { item in
            NavigationLink(destination: ChatView(peerName: item.peerName)) {
                HStack(spacing: 12) {
                    Circle()
                        .fill(Color.surface2)
                        .frame(width: 48, height: 48)
                        .overlay(
                            Text(item.peerName.prefix(1).uppercased())
                                .font(.system(size: 20, weight: .light))
                                .foregroundColor(.textDim)
                        )

                    VStack(alignment: .leading, spacing: 4) {
                        HStack {
                            Text(item.peerName)
                                .font(.system(size: 15, weight: .medium))
                                .foregroundColor(.textPrimary)
                            Spacer()
                            Text(item.timestamp)
                                .font(.system(size: 12))
                                .foregroundColor(.textDim)
                        }
                        Text(item.lastMessage)
                            .font(.system(size: 13))
                            .foregroundColor(.textDim)
                            .lineLimit(1)
                    }
                }
                .padding(.vertical, 4)
            }
            .listRowBackground(Color.surface)
        }
        .listStyle(.plain)
        .scrollContentBackground(.hidden)
    }
}

// MARK: - Preview

#Preview {
    NavigationStack {
        ChatListView()
    }
}
