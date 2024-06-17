package com.example.documentscanner

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.example.documentscanner.ui.theme.DocumentScannerTheme
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.google.mlkit.vision.text.Text
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.material3.Text
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer

data class MergedTextBlock(val text: String, val boundingBox: Rect)

class MainActivity : ComponentActivity() {
    private lateinit var scannerLauncher: ActivityResultLauncher<IntentSenderRequest>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        scannerLauncher = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
            if (result.resultCode == RESULT_OK && result.data != null) {
                val scanningResult = GmsDocumentScanningResult.fromActivityResultIntent(result.data!!)
                scanningResult!!.getPages()?.forEach { page ->
                    val imageUri = page.getImageUri()
                    val image = InputImage.fromFilePath(baseContext, imageUri)
                    val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
                    recognizer.process(image)
                        .addOnSuccessListener { visionText ->
                            val mergedBlocks = mergeNearbyBlocks(visionText.textBlocks)

                            val inputBitmap = InputImage.fromFilePath(baseContext, imageUri).bitmapInternal!!
                            val mutableBitmap = inputBitmap.copy(Bitmap.Config.ARGB_8888, true)
                            val canvas = Canvas(mutableBitmap)
                            val paint = Paint().apply {
                                color = android.graphics.Color.RED
                                style = Paint.Style.STROKE
                                strokeWidth = 8f
                            }

                            mergedBlocks.forEach { block ->
                                canvas.drawRect(block.boundingBox, paint)
                            }

                            setContent {
                                DocumentScannerTheme {
                                    Surface(
                                        modifier = Modifier.fillMaxSize(),
                                        color = MaterialTheme.colorScheme.background
                                    ) {
                                        ZoomableImageWithTextBlocks(
                                            bitmap = mutableBitmap,
                                            textBlocks = mergedBlocks
                                        )
                                    }
                                }
                            }
                        }
                        .addOnFailureListener { e ->
                            // Task failed with an exception
                        }
                }
                scanningResult.getPdf()?.let { pdf ->
                    val pdfUri = pdf.getUri()
                    val pageCount = pdf.getPageCount()
                }
            }
        }

        setContent {
            DocumentScannerTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    var bitmap by remember { mutableStateOf<Bitmap?>(null) }

                    LaunchedEffect(Unit) {
                        val options = GmsDocumentScannerOptions.Builder()
                            .setGalleryImportAllowed(false)
                            .setPageLimit(2)
                            .setResultFormats(GmsDocumentScannerOptions.RESULT_FORMAT_JPEG, GmsDocumentScannerOptions.RESULT_FORMAT_PDF)
                            .setScannerMode(GmsDocumentScannerOptions.SCANNER_MODE_FULL)
                            .build()

                        val scanner = GmsDocumentScanning.getClient(options)

                        scanner.getStartScanIntent(this@MainActivity)
                            .addOnSuccessListener { intentSender ->
                                val request = IntentSenderRequest.Builder(intentSender).build()
                                scannerLauncher.launch(request)
                            }
                            .addOnFailureListener {
                                // Handle failure
                            }
                    }

                    bitmap?.let {
                        Image(
                            bitmap = it.asImageBitmap(),
                            contentDescription = null,
                            modifier = Modifier.fillMaxSize()
                        )
                    } ?: Text("Scanning document...", modifier = Modifier.fillMaxSize())
                }
            }
        }
    }

    private fun mergeNearbyBlocks(blocks: List<Text.TextBlock>): List<MergedTextBlock> {
        val thresholdDistance = 50
        val mergedBlocks = mutableListOf<MergedTextBlock>()
        val visited = BooleanArray(blocks.size)

        for (i in blocks.indices) {
            if (visited[i]) continue

            val currentBlock = blocks[i]
            val mergedText = StringBuilder(currentBlock.text)
            val boundingBox = Rect(currentBlock.boundingBox!!)

            visited[i] = true

            for (j in i + 1 until blocks.size) {
                if (visited[j]) continue

                val otherBlock = blocks[j]
                val otherBoundingBox = Rect(otherBlock.boundingBox!!)

                if (boundingBox.intersects(
                        otherBoundingBox.left - thresholdDistance,
                        otherBoundingBox.top - thresholdDistance,
                        otherBoundingBox.right + thresholdDistance,
                        otherBoundingBox.bottom + thresholdDistance
                    )) {
                    boundingBox.union(otherBoundingBox)
                    mergedText.append("\n").append(otherBlock.text)
                    visited[j] = true
                }
            }

            mergedBlocks.add(MergedTextBlock(mergedText.toString(), boundingBox))
        }

        return mergedBlocks
    }
}

@Composable
fun ZoomableImageWithTextBlocks(bitmap: Bitmap, textBlocks: List<MergedTextBlock>) {
    var selectedBlock by remember { mutableStateOf<MergedTextBlock?>(null) }
    var scale by remember { mutableStateOf(1f) }
    var offsetX by remember { mutableStateOf(0f) }
    var offsetY by remember { mutableStateOf(0f) }
    var centroidState by remember { mutableStateOf(Offset.Zero) }

    Box(modifier = Modifier
        .fillMaxSize()
        .pointerInput(Unit) {
            detectTransformGestures { centroid, pan, zoom, _ ->
                val oldScale = scale
                scale = (scale * zoom).coerceIn(1f, 1f)
                val scaleFactor = scale / oldScale

                // Update the centroid state
                centroidState = centroid

                offsetX = (offsetX - centroid.x) * scaleFactor + centroid.x + pan.x
                offsetY = (offsetY - centroid.y) * scaleFactor + centroid.y + pan.y
            }
        }
        .pointerInput(Unit) {
            detectTapGestures { tapOffset ->
                Log.d("TapGesture", "Original tap: $tapOffset")
                Log.d("TapGesture", "Offset: $offsetX, $offsetY, Scale: $scale")
                val transformedTapOffset = Offset(
                    (tapOffset.x - offsetX) / scale,
                    (tapOffset.y - offsetY) / scale
                )
                Log.d("TapGesture", "Transformed tap: $transformedTapOffset")
                selectedBlock = textBlocks.find { it.boundingBox.contains(transformedTapOffset.x.toInt(), transformedTapOffset.y.toInt()) }
            }
        }
    ) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer(
                    scaleX = scale,
                    scaleY = scale,
                    translationX = offsetX,
                    translationY = offsetY
                )
        ) {
            drawImageWithTextBlocks(bitmap, textBlocks, selectedBlock)
        }
    }
}

private fun DrawScope.drawImageWithTextBlocks(bitmap: Bitmap, textBlocks: List<MergedTextBlock>, selectedBlock: MergedTextBlock?) {
    drawImage(bitmap.asImageBitmap())

    textBlocks.forEach { block ->
        val rect = block.boundingBox
        val color = if (block == selectedBlock) Color.Blue else Color.Red

        drawRoundRect(
            color = color,
            topLeft = Offset(rect.left.toFloat(), rect.top.toFloat()),
            size = androidx.compose.ui.geometry.Size(rect.width().toFloat(), rect.height().toFloat()),
            cornerRadius = CornerRadius(4.dp.toPx(), 4.dp.toPx()),
            style = Stroke(width = 8f)
        )
    }
}

@Preview(showBackground = true)
@Composable
fun SelectableTextBlocksScreenPreview() {
    DocumentScannerTheme {
        val dummyBitmap = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
        val dummyBlocks = listOf(
            MergedTextBlock("Sample text 1", Rect(0, 0, 50, 50)),
            MergedTextBlock("Sample text 2", Rect(50, 50, 100, 100))
        )
        ZoomableImageWithTextBlocks(dummyBitmap, dummyBlocks)
    }
}
