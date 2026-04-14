package phantom.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * PHANTOM Android entry point.
 *
 * This is a thin shell — all business logic lives in shared/core and shared/features.
 * Replace the placeholder composable with the real navigation graph once
 * shared/features/onboarding and shared/features/chatlist are implemented.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            PhantomApp()
        }
    }
}

@Composable
private fun PhantomApp() {
    // Placeholder — will be replaced by shared navigation graph (Alpha-0 task #18)
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0B0D12)),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = "PHANTOM",
            color = Color(0xFF6C5CE7),
            fontSize = 28.sp,
            fontWeight = FontWeight.Light,
            letterSpacing = 8.sp
        )
    }
}
