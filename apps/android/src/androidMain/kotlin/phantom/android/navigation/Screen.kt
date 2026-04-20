package phantom.android.navigation

sealed class Screen {
    object Onboarding : Screen()
    object ChatList : Screen()
    object Calls : Screen()
    object Settings : Screen()
    object MessageRequests : Screen()
    object Profile : Screen()
    object QrScan : Screen()
    object SavedMessages : Screen()
    data class Chat(val conversationId: String, val theirUsername: String) : Screen()
    data class ContactProfile(val conversationId: String, val theirUsername: String) : Screen()
}
