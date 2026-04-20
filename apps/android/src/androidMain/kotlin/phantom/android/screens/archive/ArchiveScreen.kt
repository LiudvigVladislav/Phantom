package phantom.android.screens.archive

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import phantom.android.ui.theme.*

@Composable
fun ArchiveScreen(onBack: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BgDeep),
    ) {
        // Top bar
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(Surface)
                .windowInsetsPadding(WindowInsets.statusBars),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(RoundedCornerShape(18.dp))
                        .background(Surface2)
                        .clickable(onClick = onBack),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                        tint = TextPrimary,
                        modifier = Modifier.size(18.dp),
                    )
                }
                Text(
                    text = "Archive",
                    modifier = Modifier.weight(1f),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    color = TextDim,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    letterSpacing = 3.sp,
                )
                Spacer(Modifier.size(36.dp))
            }
            HorizontalDivider(color = Color.White.copy(alpha = 0.05f))
        }

        // Empty state
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Canvas(modifier = Modifier.size(56.dp)) {
                    val sw = 2.dp.toPx()
                    val st = Stroke(sw, cap = StrokeCap.Round)
                    val path = androidx.compose.ui.graphics.Path().apply {
                        moveTo(size.width * 0.125f, size.height * 0.292f)
                        lineTo(size.width * 0.125f, size.height * 0.875f)
                        lineTo(size.width * 0.875f, size.height * 0.875f)
                        lineTo(size.width * 0.875f, size.height * 0.375f)
                        lineTo(size.width * 0.5f, size.height * 0.375f)
                        lineTo(size.width * 0.375f, size.height * 0.25f)
                        lineTo(size.width * 0.125f, size.height * 0.25f)
                        close()
                    }
                    drawPath(path, TextDim.copy(alpha = 0.4f), style = st)
                }
                Spacer(Modifier.height(16.dp))
                Text("Archive is empty", color = TextDim, fontSize = 15.sp)
                Spacer(Modifier.height(6.dp))
                Text(
                    "Archived chats will appear here",
                    color = TextDim.copy(alpha = 0.5f),
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                )
            }
        }
    }
}
