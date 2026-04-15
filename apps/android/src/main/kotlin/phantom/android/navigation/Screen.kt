package phantom.android.navigation

sealed class Screen {
    object Onboarding : Screen()
    object ChatList : Screen()
    data class Chat(val conversationId: String, val theirUsername: String) : Screen()
}
