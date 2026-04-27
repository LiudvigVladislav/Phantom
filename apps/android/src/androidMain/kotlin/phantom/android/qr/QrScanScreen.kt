// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.android.qr

import android.Manifest
import android.content.pm.PackageManager
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import phantom.android.ui.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import phantom.android.ui.theme.*
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

private const val TAG = "PhantomQR"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QrScanScreen(
    onScanned: (String) -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        )
    }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        hasCameraPermission = granted
    }

    LaunchedEffect(Unit) {
        if (!hasCameraPermission) launcher.launch(Manifest.permission.CAMERA)
    }

    Scaffold(
        containerColor = BgDeep,
        topBar = {
            TopAppBar(
                title = { Text("Scan QR Code", color = TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.Normal) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        PhIconBack(color = TextDim, size = 22.dp)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Surface),
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentAlignment = Alignment.Center,
        ) {
            if (hasCameraPermission) {
                CameraPreview(onScanned = onScanned)

                // Viewfinder overlay
                Box(
                    modifier = Modifier
                        .size(260.dp)
                        .background(Color.Transparent),
                ) {
                    CornerBrackets()
                }

                Text(
                    text = "Point at the Phantom QR code",
                    color = TextDim,
                    fontSize = 13.sp,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 48.dp)
                        .background(BgDeep.copy(alpha = 0.6f), RoundedCornerShape(8.dp))
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                )
            } else {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Camera permission required", color = TextPrimary, fontSize = 15.sp)
                    Spacer(Modifier.height(12.dp))
                    Button(
                        onClick = { launcher.launch(Manifest.permission.CAMERA) },
                        colors = ButtonDefaults.buttonColors(containerColor = CyanAccent),
                    ) {
                        Text("Grant Permission", color = BgDeep)
                    }
                }
            }
        }
    }
}

@Composable
private fun CameraPreview(onScanned: (String) -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    // A scanned-flag outside the analyzer closure — captured by remember so the
    // same instance survives recomposition. Prevents the rare race where two
    // barcode frames resolve simultaneously and both try to navigate away.
    val scannedState = remember { mutableStateOf(false) }
    // Hold the executor in state so onDispose can shut it down cleanly. Without
    // this, the analyzer thread stays alive after the composable is gone and
    // any in-flight ML Kit callback lands on a dead UI with a null context,
    // which matches the NPE seen at jb2.run on the physical phone.
    val executorState = remember { mutableStateOf<ExecutorService?>(null) }
    val cameraProviderState = remember { mutableStateOf<ProcessCameraProvider?>(null) }

    DisposableEffect(Unit) {
        onDispose {
            Log.i(TAG, "Disposing QR preview — unbinding camera + shutting down executor")
            runCatching { cameraProviderState.value?.unbindAll() }
                .onFailure { Log.w(TAG, "unbindAll threw: ${it.message}") }
            runCatching { executorState.value?.shutdown() }
                .onFailure { Log.w(TAG, "executor shutdown threw: ${it.message}") }
            cameraProviderState.value = null
            executorState.value = null
        }
    }

    AndroidView(
        factory = { ctx ->
            val previewView = PreviewView(ctx)
            val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)
            cameraProviderFuture.addListener({
                // Every step below is inside this outer try/catch because the
                // listener runs on the main executor and an uncaught exception
                // here becomes a FATAL crash with the obfuscated jb2.run frame
                // that was observed in the 2026-04-24 QA pass.
                try {
                    val cameraProvider = cameraProviderFuture.get()
                        ?: run {
                            Log.e(TAG, "cameraProviderFuture.get() returned null — aborting preview setup")
                            return@addListener
                        }
                    cameraProviderState.value = cameraProvider

                    val preview = Preview.Builder().build().also {
                        it.surfaceProvider = previewView.surfaceProvider
                    }
                    val scanner = BarcodeScanning.getClient()
                    val executor = Executors.newSingleThreadExecutor()
                    executorState.value = executor

                    val analysis = ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build()

                    analysis.setAnalyzer(executor) { imageProxy ->
                        // Every branch below must close the imageProxy exactly
                        // once. Failure to close drives the analyzer into a
                        // starved state where no further frames arrive.
                        try {
                            @androidx.annotation.OptIn(androidx.camera.core.ExperimentalGetImage::class)
                            val mediaImage = imageProxy.image
                            if (mediaImage == null || scannedState.value) {
                                imageProxy.close()
                                return@setAnalyzer
                            }
                            val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
                            scanner.process(image)
                                .addOnSuccessListener { barcodes ->
                                    try {
                                        if (scannedState.value) return@addOnSuccessListener
                                        val qr = barcodes.firstOrNull { it.format == Barcode.FORMAT_QR_CODE }
                                        val value = qr?.rawValue
                                        if (!value.isNullOrBlank()) {
                                            scannedState.value = true
                                            onScanned(value)
                                        }
                                    } catch (e: Throwable) {
                                        Log.e(TAG, "Barcode success handler threw: ${e.message}", e)
                                    }
                                }
                                .addOnFailureListener { e ->
                                    Log.w(TAG, "Barcode scan failed: ${e.message}")
                                }
                                .addOnCompleteListener {
                                    runCatching { imageProxy.close() }
                                        .onFailure { Log.w(TAG, "imageProxy.close threw: ${it.message}") }
                                }
                        } catch (e: Throwable) {
                            Log.e(TAG, "Analyzer frame handler threw: ${e.message}", e)
                            runCatching { imageProxy.close() }
                        }
                    }

                    runCatching { cameraProvider.unbindAll() }
                        .onFailure { Log.w(TAG, "unbindAll (pre-bind) threw: ${it.message}") }
                    cameraProvider.bindToLifecycle(
                        lifecycleOwner,
                        CameraSelector.DEFAULT_BACK_CAMERA,
                        preview,
                        analysis,
                    )
                    Log.i(TAG, "QR camera preview bound to lifecycle")
                } catch (e: Throwable) {
                    Log.e(TAG, "Camera preview setup FAILED (${e::class.simpleName}): ${e.message}", e)
                }
            }, ContextCompat.getMainExecutor(ctx))
            previewView
        },
        modifier = Modifier.fillMaxSize(),
    )
}

@Composable
private fun CornerBrackets() {
    val color = CyanAccent
    val strokeWidth = 3.dp
    val bracketSize = 24.dp

    Box(Modifier.fillMaxSize()) {
        HorizontalDivider(Modifier.width(bracketSize).align(Alignment.TopStart), thickness = strokeWidth, color = color)
        VerticalDivider(Modifier.height(bracketSize).align(Alignment.TopStart), thickness = strokeWidth, color = color)
        HorizontalDivider(Modifier.width(bracketSize).align(Alignment.TopEnd), thickness = strokeWidth, color = color)
        VerticalDivider(Modifier.height(bracketSize).align(Alignment.TopEnd), thickness = strokeWidth, color = color)
        HorizontalDivider(Modifier.width(bracketSize).align(Alignment.BottomStart), thickness = strokeWidth, color = color)
        VerticalDivider(Modifier.height(bracketSize).align(Alignment.BottomStart), thickness = strokeWidth, color = color)
        HorizontalDivider(Modifier.width(bracketSize).align(Alignment.BottomEnd), thickness = strokeWidth, color = color)
        VerticalDivider(Modifier.height(bracketSize).align(Alignment.BottomEnd), thickness = strokeWidth, color = color)
    }
}
