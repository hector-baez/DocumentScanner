package com.example.documentscanner

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.example.documentscanner.ui.theme.DocumentScannerTheme
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            DocumentScannerTheme {
                // A surface container using the 'background' color from the theme
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    Greeting("Android")
                }
            }
        }

        val options = GmsDocumentScannerOptions.Builder()
            .setGalleryImportAllowed(false)
            .setPageLimit(2)
            .setResultFormats(GmsDocumentScannerOptions.RESULT_FORMAT_JPEG, GmsDocumentScannerOptions.RESULT_FORMAT_PDF)
            .setScannerMode(GmsDocumentScannerOptions.SCANNER_MODE_FULL)
            .build()

        val scanner = GmsDocumentScanning.getClient(options)
        val scannerLauncher = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
            if (result.resultCode == RESULT_OK && result.data != null) {
                val scanningResult = GmsDocumentScanningResult.fromActivityResultIntent(result.data!!)
                scanningResult!!.getPages()?.forEach { page ->
                    val imageUri = page.getImageUri()
                    // Now recognize text from this image
                    val image = InputImage.fromFilePath(baseContext, imageUri)
                    val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
                    recognizer.process(image)
                        .addOnSuccessListener { visionText ->
                            // Initialize a map to hold detected key/value pairs
                            val keyValuePairs = mutableMapOf<String, String>()
                            var pendingKey: String? = null

                            visionText.textBlocks.forEachIndexed { index, block ->
                                val text = block.text.trim()
                                if (pendingKey != null) {
                                    // This block is a continuation of the previous key
                                    keyValuePairs[pendingKey!!] = text
                                    pendingKey = null
                                } else if (text.endsWith(":")) {
                                    // This block's text ends with ":", so treat it as a key and prepare to append the next block as its value
                                    pendingKey = text
                                } else {
                                    val separatorIndex = text.indexOf(":")
                                    if (separatorIndex != -1) {
                                        val key = text.substring(0, separatorIndex + 1).trim()
                                        val value = if (separatorIndex + 1 < text.length) text.substring(separatorIndex + 1).trim() else ""
                                        if (value.isEmpty() && index + 1 < visionText.textBlocks.size) {
                                            // If there's no value after ":", and there is a next block, prepare to append the next block as its value
                                            pendingKey = key
                                        } else {
                                            // Normal key-value pair processing
                                            keyValuePairs[key] = value
                                        }
                                    }
                                }
                            }
                            keyValuePairs.forEach{
                                Log.println(Log.DEBUG, "KeyValuePairs", it.toString())
                            }
                        }
                        .addOnFailureListener { e ->
                            // Task failed with an exception
                        }
                }
                scanningResult.getPdf()?.let { pdf ->
                    val pdfUri = pdf.getUri()
                    val pageCount = pdf.getPageCount()
                    // Process PDF URI and page count as needed
                }
            }
        }

        scanner.getStartScanIntent(this)
            .addOnSuccessListener { intentSender ->
                val request = IntentSenderRequest.Builder(intentSender).build()
                scannerLauncher.launch(request)
            }
            .addOnFailureListener {
                // Handle failure
            }
    }
}

@Composable
fun Greeting(name: String) {
    Text(
        text = "Hello $name!"
    )
}

@Preview(showBackground = true)
@Composable
fun GreetingPreview() {
    DocumentScannerTheme {
        Greeting("Android")
    }
}
