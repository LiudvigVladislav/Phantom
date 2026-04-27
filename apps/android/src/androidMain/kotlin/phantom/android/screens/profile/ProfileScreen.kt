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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
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

    var identity by remember { mutableStateOf<IdentityRecord?>(null) }
    var avatarBitmap by remember { mutableStateOf<ImageBitmap?>(null) }

    // Profile field state
    var firstName by remember { mutableStateOf("") }
    var lastName by remember { mutableStateOf("") }
    var dateOfBirth by remember { mutableStateOf("") }
    var city by remember { mutableStateOf("") }
    var country by remember { mutableStateOf("") }

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
        }
    }

    LaunchedEffect(Unit) {
        identity = container.identityRepo.loadIdentity()
        withContext(Dispatchers.IO) {
            firstName = prefs.getString("profile_first_name", "") ?: ""
            lastName = prefs.getString("profile_last_name", "") ?: ""
            dateOfBirth = prefs.getString("profile_dob", "") ?: ""
            city = prefs.getString("profile_city", "") ?: ""
            country = prefs.getString("profile_country", "") ?: ""
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

            // ── Connection card ──────────────────────────────────────────────
            identity?.let { id ->
                ConnectionCard(identity = id)
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
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Back button
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(Surface2)
                    .clickable { onBack() },
                contentAlignment = Alignment.Center,
            ) {
                PhIconBack(color = TextPrimary, size = 18.dp)
            }

            // Title
            Text(
                text = "PROFILE",
                color = TextDim,
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Normal,
                letterSpacing = 3.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f),
            )

            // Spacer mirror of back button to keep title centered
            Spacer(modifier = Modifier.size(36.dp))
        }

        HorizontalDivider(color = Color.White.copy(alpha = 0.05f))
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
    onBadgeTap: () -> Unit,
    onEditField: (label: String, currentValue: String) -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(14.dp),
        color = Surface,
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 24.dp, end = 24.dp, top = 24.dp, bottom = 18.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Avatar with badge
            Box(
                modifier = Modifier.size(96.dp),
                contentAlignment = Alignment.Center,
            ) {
                if (avatarBitmap != null) {
                    androidx.compose.foundation.Image(
                        bitmap = avatarBitmap,
                        contentDescription = "Profile photo",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .size(96.dp)
                            .clip(CircleShape),
                    )
                } else {
                    // Gradient avatar box
                    Box(
                        modifier = Modifier
                            .size(96.dp)
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
                            fontSize = 40.sp,
                            fontWeight = FontWeight.Light,
                        )
                    }
                }

                // Camera badge — bottom-right
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .size(30.dp)
                        .clip(CircleShape)
                        .background(CyanAccent)
                        .border(3.dp, Surface, CircleShape)
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

            // @username
            Text(
                text = if (username.isNotEmpty()) "@$username" else "Loading…",
                color = TextPrimary,
                fontSize = 22.sp,
                fontWeight = FontWeight.Medium,
            )

            Spacer(Modifier.height(6.dp))

            // Status row
            Row(verticalAlignment = Alignment.CenterVertically) {
                Canvas(modifier = Modifier.size(8.dp)) {
                    drawCircle(color = Success, radius = size.minDimension / 2f)
                }
                Spacer(Modifier.width(6.dp))
                Text(
                    text = "DEVICE TRUSTED · ONLINE",
                    color = TextDim,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    letterSpacing = 2.sp,
                )
            }

            Spacer(Modifier.height(16.dp))

            // Edit fields grid
            Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                // Row 1: First name + Last name
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    ProfileEditField(
                        label = "First name",
                        value = firstName,
                        modifier = Modifier.weight(1f),
                        onTap = { onEditField("First name", firstName) },
                    )
                    ProfileEditField(
                        label = "Last name",
                        value = lastName,
                        modifier = Modifier.weight(1f),
                        onTap = { onEditField("Last name", lastName) },
                    )
                }

                // Row 2: Date of birth + City
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    ProfileEditField(
                        label = "Date of birth",
                        value = dateOfBirth,
                        modifier = Modifier.weight(1f),
                        onTap = { onEditField("Date of birth", dateOfBirth) },
                    )
                    ProfileEditField(
                        label = "City",
                        value = city,
                        modifier = Modifier.weight(1f),
                        onTap = { onEditField("City", city) },
                    )
                }

                // Row 3: Country (full width)
                ProfileEditField(
                    label = "Country",
                    value = country,
                    modifier = Modifier.fillMaxWidth(),
                    onTap = { onEditField("Country", country) },
                )
            }
        }
    }
}

@Composable
private fun ProfileEditField(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    onTap: () -> Unit,
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(Surface2)
            .border(0.5.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(10.dp))
            .clickable { onTap() }
            .padding(horizontal = 12.dp, vertical = 9.dp),
    ) {
        Column {
            Text(
                text = label.uppercase(),
                color = TextDim,
                fontSize = 9.sp,
                fontFamily = FontFamily.Monospace,
                letterSpacing = 2.2.sp,
            )
            Spacer(Modifier.height(3.dp))
            Text(
                text = value.ifEmpty { "—" },
                color = TextPrimary,
                fontSize = 13.sp,
            )
        }
    }
}

// ── QR key card ───────────────────────────────────────────────────────────────

@Composable
private fun QrKeyCard(
    identityString: String,
    copied: Boolean,
    onShare: () -> Unit,
    onCopy: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(14.dp),
        color = Surface,
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Header
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "QR CODE & KEY",
                    color = TextDim,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    letterSpacing = 2.5.sp,
                )
                Spacer(Modifier.width(8.dp))
                // Lock icon using Canvas
                Canvas(modifier = Modifier.size(12.dp)) {
                    val w = size.width
                    val h = size.height
                    // shackle
                    drawArc(
                        color = Success,
                        startAngle = 180f,
                        sweepAngle = 180f,
                        useCenter = false,
                        topLeft = androidx.compose.ui.geometry.Offset(w * 0.2f, 0f),
                        size = androidx.compose.ui.geometry.Size(w * 0.6f, h * 0.55f),
                        style = Stroke(1.5.dp.toPx()),
                    )
                    // body
                    drawRoundRect(
                        color = Success,
                        topLeft = androidx.compose.ui.geometry.Offset(0f, h * 0.45f),
                        size = androidx.compose.ui.geometry.Size(w, h * 0.55f),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(1.5.dp.toPx()),
                    )
                }
            }

            Spacer(Modifier.height(16.dp))

            QrCodeImage(content = identityString, size = 172.dp)

            Spacer(Modifier.height(12.dp))

            Text(
                text = "QR code and key are visible only to you.\nShare only with people you trust.",
                color = TextDim,
                fontSize = 12.sp,
                textAlign = TextAlign.Center,
                lineHeight = 18.sp,
            )

            Spacer(Modifier.height(16.dp))

            // Share button (filled)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(46.dp)
                    .clip(RoundedCornerShape(10.dp))
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

            // Copy button (outline)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(44.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .border(1.dp, CyanAccent.copy(alpha = if (copied) 1f else 0.5f), RoundedCornerShape(10.dp))
                    .clickable { onCopy() },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = if (copied) "Copied" else "Copy key",
                    color = CyanAccent,
                    fontSize = 14.sp,
                )
            }
        }
    }
}

// ── Connection card ───────────────────────────────────────────────────────────

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
                    fontFamily = FontFamily.Monospace,
                    letterSpacing = 2.5.sp,
                )
            }
            HorizontalDivider(color = Color.White.copy(alpha = 0.06f))

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
        Text(value, color = TextPrimary, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
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
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
                .clip(RoundedCornerShape(12.dp))
                .border(1.dp, Danger.copy(alpha = 0.5f), RoundedCornerShape(12.dp))
                .clickable { onDeleteTap() },
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "Delete Account",
                color = Danger,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
            )
        }

        Spacer(Modifier.height(10.dp))

        Text(
            text = "Destructive action. Keys deleted forever.\nYour contacts will see \"Account deleted by user\".",
            color = TextDim,
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
    var text by remember { mutableStateOf(initialValue) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Surface,
        title = { Text(label, color = TextPrimary, fontWeight = FontWeight.Medium) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
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
            TextButton(onClick = { onSave(text.trim()) }) {
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
