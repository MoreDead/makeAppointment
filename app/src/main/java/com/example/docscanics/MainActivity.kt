package com.example.docscanics

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.RectF
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import com.example.docscanics.databinding.ActivityMainBinding
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlinx.coroutines.runBlocking

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private var imageCapture: ImageCapture? = null
    private lateinit var cameraExecutor: ExecutorService

    private var selectedBox: RectF? = null
    private val selectionOverlay by lazy { SelectionOverlayView(this) }

    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted: Boolean ->
            if (isGranted) startCamera() else Toast.makeText(this, "Camera permission denied", Toast.LENGTH_SHORT).show()
        }

    private val createDocumentLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("text/calendar")) { uri: Uri? ->
            if (uri != null) {
                saveIcsToUri(uri)
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Add selection overlay on top of preview
        binding.rootContainer.addView(selectionOverlay)
        selectionOverlay.visibility = View.GONE

        binding.btnCapture.setOnClickListener { captureAndRecognize() }
        binding.btnToggleSelect.setOnClickListener { toggleSelectionMode() }
        binding.btnSaveIcs.setOnClickListener { promptSaveIcs() }

        cameraExecutor = Executors.newSingleThreadExecutor()

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            requestPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider: ProcessCameraProvider = cameraProviderFuture.get()

            val preview =
                Preview.Builder().build().also {
                    it.setSurfaceProvider(binding.viewFinder.surfaceProvider)
                }

            imageCapture = ImageCapture.Builder().build()

            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageCapture)
            } catch (exc: Exception) {
                Toast.makeText(this, "Failed to start camera: ${exc.message}", Toast.LENGTH_LONG).show()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun toggleSelectionMode() {
        if (selectionOverlay.visibility == View.VISIBLE) {
            // Exiting selection mode
            selectionOverlay.visibility = View.GONE
            selectedBox = selectionOverlay.getSelection()
            
            if (selectedBox != null) {
                val width = selectedBox!!.width().toInt()
                val height = selectedBox!!.height().toInt()
                Toast.makeText(this, "Selection confirmed: ${width}x${height} pixels", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "No valid selection made. Tap and drag to select a text region.", Toast.LENGTH_LONG).show()
            }
        } else {
            // Entering selection mode
            selectionOverlay.reset()
            selectionOverlay.visibility = View.VISIBLE
            Toast.makeText(this, "Selection mode active. Drag to select text region, then tap 'Select' again.", Toast.LENGTH_LONG).show()
        }
    }

    private fun captureAndRecognize() {
        val imageCapture = imageCapture ?: run {
            Toast.makeText(this, "Camera not ready. Please wait a moment.", Toast.LENGTH_SHORT).show()
            return
        }
        
        // Provide feedback about selection status
        if (selectedBox != null) {
            Toast.makeText(this, "Capturing with selected region...", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "Capturing entire image for text recognition...", Toast.LENGTH_SHORT).show()
        }
        
        imageCapture.takePicture(
            cameraExecutor,
            object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(image: ImageProxy) {
                    processImageWithOcr(image)
                }

                override fun onError(exception: ImageCaptureException) {
                    runOnUiThread {
                        Toast.makeText(this@MainActivity, "Capture failed: ${exception.message}", Toast.LENGTH_LONG).show()
                    }
                }
            },
        )
    }

    private fun processImageWithOcr(image: ImageProxy) {
        try {
            // If we have a selection box, we could crop the image here
            // For now, we'll process the full image and note the selection for future enhancement
            val selectionRect = selectedBox
            
            // Launch coroutine for OCR processing
            cameraExecutor.execute {
                try {
                    val recognizedText = runBlocking {
                        OcrProcessor.recognize(image)
                    }
                    
                    image.close()
                    
                    // Extract text content
                    val extractedText = if (selectionRect != null) {
                        // TODO: Filter OCR results based on selection rectangle coordinates
                        // For now, use all recognized text
                        recognizedText.text
                    } else {
                        recognizedText.text
                    }
                    
                    runOnUiThread {
                        if (extractedText.isNotBlank()) {
                            val now = ZonedDateTime.now(ZoneId.systemDefault())
                            createAppointmentFromText(extractedText, now)
                            Toast.makeText(this@MainActivity, "OCR extracted: ${extractedText.take(50)}...", Toast.LENGTH_LONG).show()
                        } else {
                            Toast.makeText(this@MainActivity, "No text detected in image", Toast.LENGTH_SHORT).show()
                        }
                    }
                } catch (e: Exception) {
                    image.close()
                    runOnUiThread {
                        Toast.makeText(this@MainActivity, "OCR failed: ${e.message}", Toast.LENGTH_LONG).show()
                    }
                }
            }
        } catch (e: Exception) {
            image.close()
            runOnUiThread {
                Toast.makeText(this@MainActivity, "Image processing failed: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private var pendingIcs: String? = null

    private fun createAppointmentFromText(
        text: String,
        referenceTime: ZonedDateTime,
    ) {
        try {
            // Parse text into structured appointment fields
            val parsedAppointment = AppointmentParser.parseAppointment(text, referenceTime)
            
            // Use parsed data or fallback to defaults
            val summary = parsedAppointment.summary.ifBlank { "Appointment" }
            val start = parsedAppointment.startDateTime ?: referenceTime.plusDays(1).withHour(9).withMinute(0)
            val end = parsedAppointment.endDateTime ?: start.plusHours(1)
            val location = parsedAppointment.location
            val description = parsedAppointment.description ?: "Created from scanned text"
            
            // Validate dates are reasonable (not more than 10 years in the future)
            val maxFutureDate = referenceTime.plusYears(10)
            if (start.isAfter(maxFutureDate)) {
                Toast.makeText(this, "Warning: Parsed date seems too far in the future, using tomorrow instead", Toast.LENGTH_LONG).show()
                val adjustedStart = referenceTime.plusDays(1).withHour(9).withMinute(0)
                val adjustedEnd = adjustedStart.plusHours(1)
                createIcsFromValidatedData(summary, adjustedStart, adjustedEnd, location, description)
                return
            }
            
            createIcsFromValidatedData(summary, start, end, location, description)
        } catch (e: Exception) {
            Toast.makeText(this, "Failed to create appointment: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }
    
    private fun createIcsFromValidatedData(
        summary: String,
        start: ZonedDateTime,
        end: ZonedDateTime,
        location: String?,
        description: String?
    ) {
        try {
            val ics = IcsWriter.buildEvent(
                summary = summary,
                start = start,
                end = end,
                location = location,
                description = description,
            )
            
            pendingIcs = ics
            
            // Show user what was parsed
            val parseInfo = buildString {
                append("Parsed: $summary\n")
                append("Start: ${start.format(java.time.format.DateTimeFormatter.ofPattern("MMM dd, yyyy 'at' h:mm a"))}\n")
                if (location != null) append("Location: $location\n")
                append("Ready to save ICS file")
            }
            
            Toast.makeText(this, parseInfo, Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Failed to generate calendar event: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun promptSaveIcs() {
        if (pendingIcs == null) {
            Toast.makeText(this, "No ICS content to save yet. Please capture and process an image first.", Toast.LENGTH_LONG).show()
            return
        }
        try {
            createDocumentLauncher.launch("appointment.ics")
        } catch (e: Exception) {
            Toast.makeText(this, "Failed to launch file picker: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun saveIcsToUri(uri: Uri) {
        val content = pendingIcs ?: return
        try {
            contentResolver.openOutputStream(uri)?.use { os ->
                os.write(content.toByteArray(Charsets.UTF_8))
            }
            Toast.makeText(this, "Saved ICS", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Failed to save: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }
}
