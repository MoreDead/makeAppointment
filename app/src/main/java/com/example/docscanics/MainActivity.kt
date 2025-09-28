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
            selectionOverlay.visibility = View.GONE
            selectedBox = selectionOverlay.getSelection()
            Toast.makeText(this, "Box selected: $selectedBox", Toast.LENGTH_SHORT).show()
        } else {
            selectionOverlay.reset()
            selectionOverlay.visibility = View.VISIBLE
        }
    }

    private fun captureAndRecognize() {
        val imageCapture = imageCapture ?: return
        imageCapture.takePicture(
            cameraExecutor,
            object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(image: ImageProxy) {
                    // TODO: Convert to InputImage and run OCR, cropping to selectedBox if present
                    // For now, demonstrate building an ICS from placeholder text
                    image.close()
                    val now = ZonedDateTime.now(ZoneId.systemDefault())
                    val eventText = "Doctor appointment at 3pm on Oct 2, 2025"
                    createAppointmentFromText(eventText, now)
                }

                override fun onError(exception: ImageCaptureException) {
                    Toast.makeText(this@MainActivity, "Capture failed: ${exception.message}", Toast.LENGTH_LONG).show()
                }
            },
        )
    }

    private var pendingIcs: String? = null

    private fun createAppointmentFromText(
        text: String,
        referenceTime: ZonedDateTime,
    ) {
        // TODO: Parse text into structured fields (summary, start, end, location, description)
        val summary = text
        val start = referenceTime.plusDays(1).withHour(15).withMinute(0)
        val end = start.plusHours(1)
        val ics =
            IcsWriter.buildEvent(
                summary = summary,
                start = start,
                end = end,
                location = null,
                description = "Created from scanned text",
            )
        pendingIcs = ics
        Toast.makeText(this, "ICS ready. Tap Save to export.", Toast.LENGTH_SHORT).show()
    }

    private fun promptSaveIcs() {
        if (pendingIcs == null) {
            Toast.makeText(this, "No ICS content to save yet", Toast.LENGTH_SHORT).show()
            return
        }
        createDocumentLauncher.launch("appointment.ics")
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
