// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.android.screens.profile

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import phantom.android.di.AppContainer
import phantom.android.qr.QrCodeImage
import phantom.android.qr.generateQrBitmap
import phantom.android.ui.*
import phantom.android.ui.theme.*
import phantom.android.ui.theme.PhantomFontMono
import phantom.core.identity.IdentityRecord
import java.io.File

// ── Gradient presets ─────────────────────────────────────────────────────────

private val GRADIENT_PRESETS: List<Pair<Color, Color>> = listOf(
    Pair(Color(0xFF00D4FF), Color(0xFF0055CC)),
    Pair(Color(0xFF8B5CF6), Color(0xFFEC4899)),
    Pair(Color(0xFF2FBF71), Color(0xFF0099AA)),
    Pair(Color(0xFFF97316), Color(0xFFE85D75)),
    Pair(Color(0xFFF59E0B), Color(0xFFD97706)),
    Pair(Color(0xFF3B82F6), Color(0xFF1D4ED8)),
    Pair(Color(0xFFFB7185), Color(0xFFF43F5E)),
    Pair(Color(0xFF10B981), Color(0xFF065F46)),
)

private fun prefsOf(context: Context): SharedPreferences =
    context.getSharedPreferences("phantom_prefs", Context.MODE_PRIVATE)

// ── Screen ───────────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileScreen(
    container: AppContainer,
    onBack: () -> Unit,
    onLogout: () -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val prefs = remember { prefsOf(context) }

    val identity by container.identityState.collectAsState()
    var avatarBitmap by remember { mutableStateOf<ImageBitmap?>(null) }

    // Profile field state
    var firstName by remember { mutableStateOf("") }
    var lastName by remember { mutableStateOf("") }
    var dateOfBirth by remember { mutableStateOf("") }
    var city by remember { mutableStateOf("") }
    var country by remember { mutableStateOf("") }
    var bio by remember { mutableStateOf("") }

    // Gradient index: -1 = use name-based default
    var gradientIndex by remember { mutableIntStateOf(-1) }

    // Dialog state
    var showAvatarChoiceDialog by remember { mutableStateOf(false) }
    var showGradientPicker by remember { mutableStateOf(false) }
    var showShareDialog by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var editingField by remember { mutableStateOf<String?>(null) }
    var editingValue by remember { mutableStateOf("") }
    var copied by remember { mutableStateOf(false) }

    val avatarFile = remember { File(context.filesDir, "profile_avatar.jpg") }

    val photoPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch(Dispatchers.IO) {
            context.contentResolver.openInputStream(uri)?.use { input ->
                avatarFile.outputStream().use { output -> input.copyTo(output) }
            }
            val bmp = BitmapFactory.decodeFile(avatarFile.absolutePath)
            withContext(Dispatchers.Main) {
                avatarBitmap = bmp?.asImageBitmap()
            }
            // Notify other screens (top-bar avatar, etc.) that the file changed.
            container.refreshSelfAvatar()
        }
    }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            firstName = prefs.getString("profile_first_name", "") ?: ""
            lastName = prefs.getString("profile_last_name", "") ?: ""
            dateOfBirth = prefs.getString("profile_dob", "") ?: ""
            city = prefs.getString("profile_city", "") ?: ""
            country = prefs.getString("profile_country", "") ?: ""
            bio = prefs.getString("profile_bio", "") ?: ""
            gradientIndex = prefs.getInt("profile_gradient_index", -1)
            if (avatarFile.exists()) {
                val bmp = BitmapFactory.decodeFile(avatarFile.absolutePath)
                withContext(Dispatchers.Main) { avatarBitmap = bmp?.asImageBitmap() }
            }
        }
    }

    // Derived gradient brush for avatar
    val username = identity?.username ?: ""
    val avatarBrush: Brush = remember(gradientIndex, username) {
        if (gradientIndex in GRADIENT_PRESETS.indices) {
            val (c1, c2) = GRADIENT_PRESETS[gradientIndex]
            Brush.linearGradient(colors = listOf(c1, c2))
        } else {
            gradientBrushForName(username.ifEmpty { "?" })
        }
    }

    Scaffold(
        containerColor = BgDeep,
        contentWindowInsets = WindowInsets(0),
        topBar = {
            ProfileTopBar(onBack = onBack)
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                // Window insets disabled at the Scaffold (line 156:
                // `contentWindowInsets = WindowInsets(0)`); re-add the
                // navigation-bar inset on the scroll body so the
                // DeleteSection helper text below the link doesn't get
                // clipped by the gesture area at the bottom of the
                // screen. Surfaced in 2026-05-09 visual QA.
                .windowInsetsPadding(WindowInsets.navigationBars)
                .verticalScroll(rememberScrollState()),
        ) {
            // ── Profile card ─────────────────────────────────────────────────
            ProfileCard(
                username = username,
                avatarBitmap = avatarBitmap,
                avatarBrush = avatarBrush,
                firstName = firstName,
                lastName = lastName,
                dateOfBirth = dateOfBirth,
                city = city,
                country = country,
                bio = bio,
                onBadgeTap = { showAvatarChoiceDialog = true },
                onEditField = { label, currentValue ->
                    editingField = label
                    editingValue = currentValue
                },
            )

            // ── QR key card ──────────────────────────────────────────────────
            identity?.let { id ->
                val identityString = "${id.username}:${id.publicKeyHex}"
                QrKeyCard(
                    identityString = identityString,
                    copied = copied,
                    onShare = { showShareDialog = true },
                    onCopy = {
                        copyToClipboard(context, identityString)
                        copied = true
                        scope.launch { kotlinx.coroutines.delay(2000); copied = false }
                    },
                )
            }

            // ── Account card ─────────────────────────────────────────────────
            // Per FULL_COMPOSE Profile/Mobile 1: Username / Plan / Member
            // since. Replaces the prior "Connection" panel (relay URL,
            // pubkey hex preview) — that data is for diagnostics, not for
            // the user-facing identity surface, and the canonical mock
            // doesn't include it.
            identity?.let { id ->
                AccountCard(
                    handle = id.username,
                    createdAt = id.createdAt,
                    onUpgrade = { /* TODO: nav to Premium */ },
                )
            }

            // ── Delete section ───────────────────────────────────────────────
            DeleteSection(onDeleteTap = { showDeleteDialog = true })
        }
    }

    // ── Share choice dialog ──────────────────────────────────────────────────
    val shareIdentityString = identity?.let { "${it.username}:${it.publicKeyHex}" } ?: ""
    if (showShareDialog) {
        AlertDialog(
            onDismissRequest = { showShareDialog = false },
            containerColor = Surface,
            title = { Text("Share contact", color = TextPrimary, fontWeight = FontWeight.Medium) },
            text = {
                Column {
                    TextButton(
                        onClick = {
                            showShareDialog = false
                            scope.launch(Dispatchers.IO) {
                                val qrBitmap = generateQrBitmap(shareIdentityString, sizePx = 512)
                                val file = File(context.cacheDir, "phantom_qr.png")
                                file.outputStream().use { qrBitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                                val uri = FileProvider.getUriForFile(
                                    context,
                                    "${context.packageName}.fileprovider",
                                    file,
                                )
                                val intent = Intent(Intent.ACTION_SEND).apply {
                                    type = "image/png"
                                    putExtra(Intent.EXTRA_STREAM, uri)
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }
                                withContext(Dispatchers.Main) {
                                    context.startActivity(Intent.createChooser(intent, "Share QR via…"))
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Send QR code (image)", color = CyanAccent)
                    }
                    TextButton(
                        onClick = {
                            showShareDialog = false
                            val intent = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_TEXT, shareIdentityString)
                                putExtra(Intent.EXTRA_SUBJECT, "PHANTOM identity key")
                            }
                            context.startActivity(Intent.createChooser(intent, "Share key via…"))
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Send text key", color = CyanAccent)
                    }
                    TextButton(
                        onClick = {
                            showShareDialog = false
                            scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                                val id = container.identityRepo.loadIdentity()
                                if (id != null) {
                                    val payload = "${id.username}:${id.publicKeyHex}"
                                    val encoded = android.util.Base64.encodeToString(
                                        payload.toByteArray(Charsets.UTF_8),
                                        android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP,
                                    )
                                    val link = "phantom://invite/$encoded"
                                    val intent = Intent(Intent.ACTION_SEND).apply {
                                        type = "text/plain"
                                        putExtra(Intent.EXTRA_TEXT, link)
                                        putExtra(Intent.EXTRA_SUBJECT, "Join me on PHANTOM")
                                    }
                                    withContext(kotlinx.coroutines.Dispatchers.Main) {
                                        context.startActivity(Intent.createChooser(intent, "Share invite link via…"))
                                    }
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Share invite link", color = CyanAccent)
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showShareDialog = false }) {
                    Text("Cancel", color = TextDim)
                }
            },
        )
    }

    // ── Avatar choice dialog ─────────────────────────────────────────────────
    if (showAvatarChoiceDialog) {
        AlertDialog(
            onDismissRequest = { showAvatarChoiceDialog = false },
            containerColor = Surface,
            title = { Text("Change avatar", color = TextPrimary, fontWeight = FontWeight.Medium) },
            text = {
                Column {
                    TextButton(
                        onClick = {
                            showAvatarChoiceDialog = false
                            photoPicker.launch("image/*")
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Choose photo", color = CyanAccent)
                    }
                    TextButton(
                        onClick = {
                            showAvatarChoiceDialog = false
                            showGradientPicker = true
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Change gradient color", color = CyanAccent)
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showAvatarChoiceDialog = false }) {
                    Text("Cancel", color = TextDim)
                }
            },
        )
    }

    // ── Gradient picker dialog ───────────────────────────────────────────────
    if (showGradientPicker) {
        GradientPickerDialog(
            username = username,
            currentIndex = gradientIndex,
            onSelect = { index ->
                gradientIndex = index
                prefs.edit().putInt("profile_gradient_index", index).apply()
                showGradientPicker = false
            },
            onDismiss = { showGradientPicker = false },
        )
    }

    // ── Field edit dialog ────────────────────────────────────────────────────
    editingField?.let { label ->
        FieldEditDialog(
            label = label,
            initialValue = editingValue,
            onSave = { newValue ->
                val prefKey = when (label) {
                    "First name" -> "profile_first_name"
                    "Last name" -> "profile_last_name"
                    "Date of birth" -> "profile_dob"
                    "City" -> "profile_city"
                    "Country" -> "profile_country"
                    "About" -> "profile_bio"
                    else -> null
                }
                prefKey?.let { key ->
                    prefs.edit().putString(key, newValue).apply()
                    when (label) {
                        "First name" -> firstName = newValue
                        "Last name" -> lastName = newValue
                        "Date of birth" -> dateOfBirth = newValue
                        "City" -> city = newValue
                        "Country" -> country = newValue
                        "About" -> bio = newValue
                    }
                }
                editingField = null
            },
            onDismiss = { editingField = null },
        )
    }

    // ── Delete account dialog ────────────────────────────────────────────────
    if (showDeleteDialog) {
        DeleteAccountDialog(
            username = username,
            onConfirm = {
                scope.launch {
                    container.identityRepo.deleteIdentity()
                    onLogout()
                }
            },
            onDismiss = { showDeleteDialog = false },
        )
    }
}

// ── Top bar ──────────────────────────────────────────────────────────────────

@Composable
private fun ProfileTopBar(onBack: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.statusBars)
            .background(Surface),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Back button — flat icon (mockup spec, no Surface2 chip).
            IconButton(
                onClick = onBack,
                modifier = Modifier.size(32.dp),
            ) {
                PhIconBack(color = TextPrimary, size = 20.dp)
            }

            // Title — overline mono 11sp tracked uppercase.
            Text(
                text = "PROFILE",
                color = TextDim,
                fontSize = 11.sp,
                fontFamily = PhantomFontMono,
                fontWeight = FontWeight.Normal,
                letterSpacing = 0.88.sp,  // 0.08em × 11sp
                textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f),
            )

            // Spacer mirror of back button to keep title centered
            Spacer(modifier = Modifier.size(32.dp))
        }

        HorizontalDivider(color = BorderSubtle, thickness = 1.dp)
    }
}

// ── Profile card ─────────────────────────────────────────────────────────────

@Composable
private fun ProfileCard(
    username: String,
    avatarBitmap: ImageBitmap?,
    avatarBrush: Brush,
    firstName: String,
    lastName: String,
    dateOfBirth: String,
    city: String,
    country: String,
    bio: String,
    onBadgeTap: () -> Unit,
    onEditField: (label: String, currentValue: String) -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(14.dp),
        // FULL_COMPOSE §07: identity card sits on the deeper surface so it
        // separates from the page Surface. The earlier matched-Surface bg
        // collapsed the visual hierarchy of the identity zone.
        color = BgDeep,
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 24.dp, end = 24.dp, top = 24.dp, bottom = 18.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Avatar with badge — 80dp per FULL_COMPOSE §07 (Phase 2 React
            // ProfileScreen avatar is 80px). The earlier 96dp dominated the
            // identity block and broke the size-relationship to the name.
            Box(
                modifier = Modifier.size(80.dp),
                contentAlignment = Alignment.Center,
            ) {
                if (avatarBitmap != null) {
                    androidx.compose.foundation.Image(
                        bitmap = avatarBitmap,
                        contentDescription = "Profile photo",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .size(80.dp)
                            .clip(CircleShape),
                    )
                } else {
                    // Gradient avatar box
                    Box(
                        modifier = Modifier
                            .size(80.dp)
                            .clip(CircleShape)
                            .background(avatarBrush),
                        contentAlignment = Alignment.Center,
                    ) {
                        val initial = nameInitials(username).ifEmpty {
                            username.take(1).uppercase()
                        }
                        Text(
                            text = initial,
                            color = Color.White,
                            fontSize = 34.sp,
                            fontWeight = FontWeight.Light,
                        )
                    }
                }

                // Camera badge — bottom-right (border now matches the
                // BgDeep card it sits on so the cut-out reads cleanly)
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .size(28.dp)
                        .clip(CircleShape)
                        .background(CyanAccent)
                        .border(3.dp, BgDeep, CircleShape)
                        .clickable { onBadgeTap() },
                    contentAlignment = Alignment.Center,
                ) {
                    Canvas(modifier = Modifier.size(15.dp)) {
                        val cx = size.width / 2f
                        val cy = size.height / 2f
                        // camera body
                        drawRoundRect(
                            color = BgDeep,
                            size = androidx.compose.ui.geometry.Size(size.width, size.height * 0.72f),
                            topLeft = androidx.compose.ui.geometry.Offset(0f, cy * 0.4f),
                            cornerRadius = androidx.compose.ui.geometry.CornerRadius(2.dp.toPx()),
                        )
                        // lens ring
                        drawCircle(
                            color = BgDeep,
                            radius = size.width * 0.22f,
                            center = androidx.compose.ui.geometry.Offset(cx, cy + cy * 0.15f),
                            style = Stroke(1.5.dp.toPx()),
                        )
                        // viewfinder bump
                        drawRoundRect(
                            color = BgDeep,
                            size = androidx.compose.ui.geometry.Size(size.width * 0.3f, cy * 0.35f),
                            topLeft = androidx.compose.ui.geometry.Offset(cx - size.width * 0.15f, cy * 0.1f),
                            cornerRadius = androidx.compose.ui.geometry.CornerRadius(1.dp.toPx()),
                        )
                    }
                }
            }

            Spacer(Modifier.height(14.dp))

            // PHANTOM_FULL_COMPOSE §07 Zone A — display name in Geist 24px
            // textPrimary, then @handle in Mono 12px textTertiary opacity 0.55.
            // Combine first + last name when both are filled so the header
            // matches the React mock ("Maya Hertzog", not just "Maya").
            // Falls through: full name → first name only → @username → "Loading…".
            val displayName = listOf(firstName, lastName)
                .filter { it.isNotBlank() }
                .joinToString(" ")
                .ifEmpty { username.ifEmpty { "Loading…" } }
            Text(
                text = displayName,
                color = TextPrimary,
                fontSize = 24.sp,
                fontWeight = FontWeight.Medium,
                letterSpacing = (-0.24).sp,
            )

            Spacer(Modifier.height(6.dp))

            Text(
                text = if (username.isNotEmpty()) "@$username" else "—",
                color = PhantomTokens.Colors.TextTertiary.copy(alpha = 0.55f),
                fontSize = 12.sp,
                fontFamily = PhantomFontMono,
                letterSpacing = 0.4.sp,
            )

            Spacer(Modifier.height(10.dp))

            // Tier badge — mono 9px, rounded 4dp. FREE while billing isn't wired.
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .background(Color.White.copy(alpha = 0.04f))
                    .border(
                        1.dp,
                        Color.White.copy(alpha = 0.08f),
                        RoundedCornerShape(4.dp),
                    )
                    .padding(horizontal = 7.dp, vertical = 3.dp),
            ) {
                Text(
                    text = "FREE",
                    color = TextDim,
                    fontSize = 9.sp,
                    fontFamily = PhantomFontMono,
                    fontWeight = FontWeight.Medium,
                    letterSpacing = 1.5.sp,
                )
            }

            Spacer(Modifier.height(24.dp))

            // Phase 2 mockup AccountRow pattern: single-column card on
            // SurfaceElevated, each row 50dp tall, label on the left
            // (Inter 14sp tertiary, fixed 112dp width) and value on the
            // right (Inter 14sp primary, flex). Rows separated by 1dp
            // BorderSubtle hairlines, no hairline after the last.
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(PhantomTokens.Radius.md))
                    .background(PhantomTokens.Colors.SurfaceElevated)
                    .border(
                        1.dp,
                        PhantomTokens.Colors.BorderSubtle,
                        RoundedCornerShape(PhantomTokens.Radius.md),
                    ),
            ) {
                ProfileEditField(
                    label = "First name",
                    value = firstName,
                    onTap = { onEditField("First name", firstName) },
                )
                ProfileFieldDivider()
                ProfileEditField(
                    label = "Last name",
                    value = lastName,
                    onTap = { onEditField("Last name", lastName) },
                )
                ProfileFieldDivider()
                ProfileEditField(
                    label = "Date of birth",
                    value = dateOfBirth,
                    onTap = { onEditField("Date of birth", dateOfBirth) },
                )
                ProfileFieldDivider()
                ProfileEditField(
                    label = "City",
                    value = city,
                    onTap = { onEditField("City", city) },
                )
                ProfileFieldDivider()
                ProfileEditField(
                    label = "Country",
                    value = country,
                    onTap = { onEditField("Country", country) },
                )
                ProfileFieldDivider()
                // Bio row last per FULL_COMPOSE §07 — sits at the bottom
                // of the editable list so the structured fields stay
                // grouped above. Multi-line value preview wraps to 2
                // lines with ellipsis to keep the row height predictable.
                ProfileEditField(
                    label = "About",
                    value = bio,
                    placeholder = "Add a short bio…",
                    onTap = { onEditField("About", bio) },
                    isLast = true,
                    valueMaxLines = 2,
                )
            }
        }
    }
}

@Composable
private fun ProfileEditField(
    label: String,
    value: String,
    onTap: () -> Unit,
    @Suppress("UNUSED_PARAMETER") isLast: Boolean = false,
    placeholder: String = "—",
    valueMaxLines: Int = 1,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 50.dp)
            .clickable(onClick = onTap)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            color = PhantomTokens.Colors.TextTertiary,
            fontSize = 14.sp,
            modifier = Modifier.width(112.dp),
        )
        Text(
            text = value.ifEmpty { placeholder },
            color = if (value.isEmpty()) PhantomTokens.Colors.TextDisabled else PhantomTokens.Colors.TextPrimary,
            fontSize = 14.sp,
            lineHeight = 19.sp,
            modifier = Modifier.weight(1f),
            maxLines = valueMaxLines,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun ProfileFieldDivider() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp)
            .height(1.dp)
            .background(PhantomTokens.Colors.BorderSubtle),
    )
}

// ── QR key card ───────────────────────────────────────────────────────────────

@Composable
private fun QrKeyCard(
    identityString: String,
    copied: Boolean,
    onShare: () -> Unit,
    onCopy: () -> Unit,
) {
    // Phase 2 mockup IdentityKeyBlock — SurfaceDeep card with BorderSubtle
    // outline, 12dp radius. Header row: Shield icon (cyan 70%) + "IDENTITY KEY"
    // mono overline + right "ED25519" mono 10sp tertiary. Below: QR + key text.
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(PhantomTokens.Radius.md))
            .background(PhantomTokens.Colors.SurfaceDeep)
            .border(1.dp, PhantomTokens.Colors.BorderSubtle, RoundedCornerShape(PhantomTokens.Radius.md)),
    ) {
        // Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Shield glyph — Canvas-drawn cyan @ 70% alpha.
            Canvas(modifier = Modifier.size(13.dp)) {
                val w = size.width
                val h = size.height
                val sw = 1.5.dp.toPx()
                val color = PhantomTokens.Colors.Cyan.copy(alpha = 0.7f)
                val path = androidx.compose.ui.graphics.Path().apply {
                    moveTo(w * 0.5f, h * 0.05f)
                    lineTo(w * 0.92f, h * 0.22f)
                    cubicTo(
                        w * 0.92f, h * 0.6f,
                        w * 0.7f, h * 0.92f,
                        w * 0.5f, h * 0.95f,
                    )
                    cubicTo(
                        w * 0.3f, h * 0.92f,
                        w * 0.08f, h * 0.6f,
                        w * 0.08f, h * 0.22f,
                    )
                    close()
                }
                drawPath(path, color = color, style = Stroke(sw))
            }
            Spacer(Modifier.width(7.dp))
            Text(
                text = "IDENTITY KEY",
                color = PhantomTokens.Colors.TextSecondary,
                fontSize = 10.sp,
                fontFamily = PhantomFontMono,
                letterSpacing = 0.6.sp,
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = "ED25519",
                color = PhantomTokens.Colors.TextTertiary.copy(alpha = 0.55f),
                fontSize = 10.sp,
                fontFamily = PhantomFontMono,
            )
        }
        HorizontalDivider(color = PhantomTokens.Colors.BorderSubtle, thickness = 1.dp)

        // QR + body
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            QrCodeImage(content = identityString, size = 172.dp)

            Spacer(Modifier.height(14.dp))

            // First 32 hex chars of the public key, formatted as 8 groups
            // of 4. FULL_COMPOSE Profile/Mobile 1 uses this hex as the
            // verify-by-eye channel; we keep the QR above for QR-scan and
            // add the hex below so both modes are reachable from one card.
            val hexFingerprint = remember(identityString) {
                val pubHex = identityString.substringAfter(":", "")
                if (pubHex.length >= 32)
                    pubHex.substring(0, 32).uppercase().chunked(4).joinToString("  ")
                else "—"
            }
            Text(
                text = hexFingerprint,
                color = TextPrimary,
                fontSize = 13.sp,
                fontFamily = PhantomFontMono,
                letterSpacing = 0.65.sp,
                lineHeight = 22.sp,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "Your public identity fingerprint",
                color = TextDim.copy(alpha = 0.65f),
                fontSize = 12.sp,
                textAlign = TextAlign.Center,
            )

            Spacer(Modifier.height(16.dp))

            // Share button — pill-shape primary cyan with restrained glow.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(44.dp)
                    .clip(RoundedCornerShape(9999.dp))
                    .background(CyanAccent)
                    .clickable { onShare() },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "Share my key",
                    color = BgDeep,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                )
            }

            Spacer(Modifier.height(8.dp))

            // Copy — ghost pill with cyan outline.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(40.dp)
                    .clip(RoundedCornerShape(9999.dp))
                    .border(
                        1.dp,
                        CyanAccent.copy(alpha = if (copied) 1f else 0.4f),
                        RoundedCornerShape(9999.dp),
                    )
                    .clickable { onCopy() },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = if (copied) "Copied" else "Copy key",
                    color = CyanAccent,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                )
            }
        }
    }
}

// ── Account card (Username / Plan / Member since) ────────────────────────────
// FULL_COMPOSE Profile/Mobile 1: three rows on a SurfaceElevated card with
// 12dp radius and BorderSubtle hairlines between rows. The Plan row carries
// an inline "UPGRADE" cyan pill that routes to Premium when the upsell flow
// lands. Member-since is rendered from the keystore install date — we don't
// log identity creation timestamp yet, so the row reads "—" until it does.

@Composable
private fun AccountCard(handle: String, createdAt: Long, onUpgrade: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(PhantomTokens.Colors.SurfaceElevated)
            .border(1.dp, BorderSubtle, RoundedCornerShape(12.dp)),
    ) {
        // Section overline
        Text(
            text = "ACCOUNT",
            color = TextDim,
            fontSize = 10.sp,
            fontFamily = PhantomFontMono,
            letterSpacing = 1.8.sp,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
        )
        HorizontalDivider(color = BorderSubtle, thickness = 1.dp)

        AccountRow(
            label = "Username",
            value = "@${handle.ifEmpty { "—" }}",
        )
        HorizontalDivider(color = BorderSubtle, thickness = 1.dp)

        // Plan row with inline UPGRADE pill (FREE tier).
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Plan",
                color = TextDim,
                fontSize = 14.sp,
                modifier = Modifier.width(120.dp),
            )
            Text(
                text = "Free",
                color = TextPrimary,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f),
            )
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(CyanAccent)
                    .clickable(onClick = onUpgrade)
                    .padding(horizontal = 10.dp, vertical = 4.dp),
            ) {
                Text(
                    text = "UPGRADE",
                    color = BgDeep,
                    fontSize = 9.sp,
                    fontFamily = PhantomFontMono,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 1.5.sp,
                )
            }
        }
        HorizontalDivider(color = BorderSubtle, thickness = 1.dp)

        AccountRow(
            label = "Member since",
            // FULL_COMPOSE §07 AccountCard: month-year string from the
            // identity creation timestamp. Earlier hardcoded "—" was a
            // placeholder waiting for the real value to be wired through.
            value = formatMemberSince(createdAt),
        )
    }
    Spacer(Modifier.height(12.dp))
}

private fun formatMemberSince(createdAtMs: Long): String {
    if (createdAtMs <= 0L) return "—"
    // App copy is English-only — Locale.getDefault() picked up the
    // device locale and rendered Russian month names (e.g. "мая 2026")
    // even though every other label in the screen is English.
    // Force Locale.ENGLISH so Member-since stays consistent with the
    // surrounding UI until full localisation lands.
    val fmt = java.text.SimpleDateFormat("MMMM yyyy", java.util.Locale.ENGLISH)
    return fmt.format(java.util.Date(createdAtMs))
}

@Composable
private fun AccountRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            color = TextDim,
            fontSize = 14.sp,
            modifier = Modifier.width(120.dp),
        )
        Text(
            text = value,
            color = TextPrimary,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}

// ── Connection card (legacy — kept for diagnostics, not rendered) ───────────

@Composable
private fun ConnectionCard(identity: IdentityRecord) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp),
        color = Surface,
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            // Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 12.dp),
            ) {
                Text(
                    text = "CONNECTION DATA",
                    color = TextDim,
                    fontSize = 10.sp,
                    fontFamily = PhantomFontMono,
                    letterSpacing = 2.5.sp,
                )
            }
            HorizontalDivider(color = BorderSubtle, thickness = 1.dp)

            ConnectionRow(label = "Algorithm", value = "Ed25519 / X25519")
            HorizontalDivider(
                modifier = Modifier.padding(horizontal = 14.dp),
                color = Color.White.copy(alpha = 0.05f),
            )
            ConnectionRow(label = "Key ID", value = identity.publicKeyHex.take(12) + "…")
            HorizontalDivider(
                modifier = Modifier.padding(horizontal = 14.dp),
                color = Color.White.copy(alpha = 0.05f),
            )
            ConnectionRow(label = "Created", value = formatTimestamp(identity.createdAt))
        }
    }
}

@Composable
private fun ConnectionRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = TextDim, fontSize = 12.sp)
        Text(value, color = TextPrimary, fontSize = 12.sp, fontFamily = PhantomFontMono)
    }
}

// ── Delete section ────────────────────────────────────────────────────────────

@Composable
private fun DeleteSection(onDeleteTap: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 14.dp, end = 14.dp, top = 14.dp, bottom = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // FULL_COMPOSE §07: delete is a de-emphasized text link, not a
        // bordered destructive button. The earlier outlined-Danger box read
        // as a primary destructive CTA — violates the "architecture of
        // restraint" doctrine. Hairline divider + low-opacity danger label
        // keeps the option findable without visually shouting.
        HorizontalDivider(
            modifier = Modifier.fillMaxWidth(),
            color = BorderSubtle,
            thickness = 1.dp,
        )
        Spacer(Modifier.height(18.dp))
        Text(
            text = "Delete account",
            color = Danger.copy(alpha = 0.55f),
            fontSize = 13.sp,
            fontWeight = FontWeight.Normal,
            modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .clickable { onDeleteTap() }
                .padding(horizontal = 12.dp, vertical = 8.dp),
        )

        Spacer(Modifier.height(8.dp))

        Text(
            text = "Destructive. Keys deleted forever. Contacts see “Account deleted by user”.",
            color = TextDim.copy(alpha = 0.7f),
            fontSize = 11.sp,
            textAlign = TextAlign.Center,
            lineHeight = 16.sp,
        )
    }
}

// ── Gradient picker dialog ────────────────────────────────────────────────────

@Composable
private fun GradientPickerDialog(
    username: String,
    currentIndex: Int,
    onSelect: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val defaultBrush = gradientBrushForName(username.ifEmpty { "?" })

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Surface,
        title = { Text("Choose gradient", color = TextPrimary, fontWeight = FontWeight.Medium) },
        text = {
            // -1 swatch = default + 8 presets = 9 items, show in 4-column grid
            val allBrushes: List<Brush> = listOf(defaultBrush) + GRADIENT_PRESETS.map { (c1, c2) ->
                Brush.linearGradient(listOf(c1, c2))
            }
            // index in allBrushes: 0 = default (-1), 1..8 = preset 0..7
            val selectedInGrid = if (currentIndex == -1) 0 else currentIndex + 1

            LazyVerticalGrid(
                columns = GridCells.Fixed(4),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.height(160.dp),
            ) {
                itemsIndexed(allBrushes) { gridIndex, brush ->
                    val isSelected = gridIndex == selectedInGrid
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .clip(CircleShape)
                            .background(brush)
                            .then(
                                if (isSelected)
                                    Modifier.border(2.dp, CyanAccent, CircleShape)
                                else Modifier
                            )
                            .clickable {
                                // gridIndex 0 -> preset index -1 (default), 1..8 -> 0..7
                                onSelect(if (gridIndex == 0) -1 else gridIndex - 1)
                            },
                    )
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = TextDim)
            }
        },
    )
}

// ── Field edit dialog ─────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FieldEditDialog(
    label: String,
    initialValue: String,
    onSave: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val isDateField = label == "Date of birth"

    // For the date field we store digits only; the formatted "mm.dd.yyyy" view
    // is produced by DateMmDdYyyyVisualTransformation, and dots are added back
    // on save via formatDob().
    var text by remember {
        mutableStateOf(if (isDateField) initialValue.filter { it.isDigit() }.take(8) else initialValue)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Surface,
        title = { Text(label, color = TextPrimary, fontWeight = FontWeight.Medium) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { newValue ->
                    text = if (isDateField) newValue.filter { it.isDigit() }.take(8) else newValue
                },
                singleLine = true,
                keyboardOptions = if (isDateField) {
                    KeyboardOptions(keyboardType = KeyboardType.Number)
                } else {
                    KeyboardOptions.Default
                },
                visualTransformation = if (isDateField) {
                    DateMmDdYyyyVisualTransformation
                } else {
                    VisualTransformation.None
                },
                placeholder = if (isDateField) {
                    { Text("MM.DD.YYYY", color = TextDim.copy(alpha = 0.5f)) }
                } else null,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = TextPrimary,
                    unfocusedTextColor = TextPrimary,
                    focusedBorderColor = CyanAccent,
                    unfocusedBorderColor = TextDim.copy(alpha = 0.3f),
                    focusedContainerColor = Surface2,
                    unfocusedContainerColor = Surface2,
                ),
            )
        },
        confirmButton = {
            TextButton(onClick = {
                val saved = if (isDateField) formatDob(text) else text.trim()
                onSave(saved)
            }) {
                Text("Save", color = CyanAccent)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = TextDim)
            }
        },
    )
}

// Display digits as "mm.dd.yyyy" (or partial: "mm", "mm.dd", "mm.dd.yy") while
// the user types only digits. Caret offsets account for the inserted dots.
private val DateMmDdYyyyVisualTransformation = VisualTransformation { input ->
    val digits = input.text.filter { it.isDigit() }.take(8)
    val formatted = buildString {
        digits.forEachIndexed { i, c ->
            if (i == 2 || i == 4) append('.')
            append(c)
        }
    }
    val mapping = object : OffsetMapping {
        override fun originalToTransformed(offset: Int): Int {
            val dotsBefore = (if (offset > 2) 1 else 0) + (if (offset > 4) 1 else 0)
            return (offset + dotsBefore).coerceIn(0, formatted.length)
        }
        override fun transformedToOriginal(offset: Int): Int {
            val dotsBefore = (if (offset > 2) 1 else 0) + (if (offset > 5) 1 else 0)
            return (offset - dotsBefore).coerceIn(0, digits.length)
        }
    }
    TransformedText(AnnotatedString(formatted), mapping)
}

// Persisted form: digits get dots inserted. Short inputs are saved as-is so the
// user can type just "1225" → "12.25" (mm.dd) or "122526" → "12.25.26" (mm.dd.yy).
private fun formatDob(rawDigits: String): String {
    val d = rawDigits.filter { it.isDigit() }.take(8)
    return when (d.length) {
        in 0..2 -> d
        in 3..4 -> d.substring(0, 2) + "." + d.substring(2)
        else    -> d.substring(0, 2) + "." + d.substring(2, 4) + "." + d.substring(4)
    }
}

// ── Delete account dialog ─────────────────────────────────────────────────────

@Composable
private fun DeleteAccountDialog(
    username: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    var confirmText by remember { mutableStateOf("") }
    val confirmed = confirmText.trim().lowercase() == username.lowercase()

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Surface,
        title = { Text("Delete Account?", color = Danger, fontWeight = FontWeight.Medium) },
        text = {
            Column {
                Text(
                    "This is not a logout.",
                    color = TextPrimary,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "Your encryption keys will be permanently deleted from this device. " +
                        "There is no recovery — no phone number, no seed phrase, no backup.",
                    color = TextDim,
                    fontSize = 13.sp,
                    lineHeight = 19.sp,
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    "Your contacts will no longer be able to reach you at this identity.",
                    color = TextDim,
                    fontSize = 13.sp,
                    lineHeight = 19.sp,
                )
                Spacer(Modifier.height(16.dp))
                Text("Type your username to confirm:", color = TextDim, fontSize = 12.sp)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = confirmText,
                    onValueChange = { confirmText = it },
                    placeholder = { Text("@$username", color = TextDim.copy(alpha = 0.5f)) },
                    singleLine = true,
                    isError = confirmText.isNotEmpty() && !confirmed,
                    supportingText = {
                        when {
                            confirmText.isNotEmpty() && !confirmed ->
                                Text("Username doesn't match", color = Danger, fontSize = 11.sp)
                            confirmed ->
                                Text("Confirmed", color = Success, fontSize = 11.sp)
                        }
                    },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary,
                        focusedBorderColor = when {
                            confirmed -> Success
                            confirmText.isNotEmpty() -> Danger
                            else -> TextDim.copy(alpha = 0.4f)
                        },
                        unfocusedBorderColor = when {
                            confirmed -> Success.copy(alpha = 0.6f)
                            confirmText.isNotEmpty() -> Danger.copy(alpha = 0.6f)
                            else -> TextDim.copy(alpha = 0.3f)
                        },
                        errorBorderColor = Danger,
                    ),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { if (confirmed) onConfirm() }, enabled = confirmed) {
                Text("Delete", color = if (confirmed) Danger else TextDim, fontWeight = FontWeight.Medium)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = TextDim)
            }
        },
    )
}

// ── Utilities ─────────────────────────────────────────────────────────────────

private fun copyToClipboard(context: Context, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("Phantom Public Key", text))
}

private fun formatTimestamp(millis: Long): String {
    val date = java.util.Date(millis)
    val fmt = java.text.SimpleDateFormat("dd MMM yyyy", java.util.Locale.US)
    return fmt.format(date)
}
