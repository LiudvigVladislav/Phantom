// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.android.screens.nearby

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import phantom.android.R
import phantom.android.navigation.Screen
import phantom.android.ui.BottomNavPill
import phantom.android.ui.NavTab
import phantom.android.ui.theme.BorderSubtle
import phantom.android.ui.theme.PhantomTokens
import phantom.android.ui.theme.PhantomFontGeist
import phantom.android.ui.theme.PhantomFontInter
import phantom.android.ui.theme.Surface
import phantom.android.ui.theme.TextPrimary

@Composable
fun NearbyScreen(onNavigate: (Screen) -> Unit) {
    Scaffold(
        containerColor = PhantomTokens.Colors.SurfaceDeep,
        topBar = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Surface)
                    .windowInsetsPadding(WindowInsets.statusBars),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(64.dp)
                        .padding(horizontal = PhantomTokens.Spacing.comfortable),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(R.string.nearby_title),
                        color = TextPrimary,
                        style = TextStyle(
                            fontFamily = PhantomFontGeist,
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Medium,
                        ),
                    )
                }
                HorizontalDivider(color = BorderSubtle, thickness = 1.dp)
            }
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.Center)
                    .padding(horizontal = PhantomTokens.Spacing.comfortable),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = stringResource(R.string.nearby_unavailable_title),
                    color = TextPrimary,
                    style = TextStyle(
                        fontFamily = PhantomFontGeist,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Medium,
                    ),
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    text = stringResource(R.string.nearby_unavailable_body),
                    color = PhantomTokens.Colors.TextSecondary,
                    style = TextStyle(
                        fontFamily = PhantomFontInter,
                        fontSize = 13.sp,
                        lineHeight = 19.sp,
                    ),
                    textAlign = TextAlign.Center,
                )
            }

            BottomNavPill(
                activeTab = NavTab.NEARBY,
                onTabSelected = { tab ->
                    when (tab) {
                        NavTab.CALLS -> onNavigate(Screen.Calls)
                        NavTab.CHATS -> onNavigate(Screen.ChatList)
                        NavTab.SETTINGS -> onNavigate(Screen.Settings)
                        NavTab.NEARBY -> {}
                    }
                },
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
    }
}
