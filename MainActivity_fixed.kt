package com.example.docscanics

import android.graphics.*
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.example.docscanics.databinding.ActivityMainBinding
import com.google.ai.client.generativeai.GenerativeModel
import com.google.ai.client.generativeai.type.content
import com.google.ai.client.generativeai.type.generationConfig
import kotlinx.coroutines.runBlocking
import java.io.IOException
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.*
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.regex.Pattern
import kotlin.math.max
import kotlin.math.min

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var ocrExecutor: ExecutorService
    private var progressDialog: AlertDialog? = null

    private val selectionOverlay by lazy { SelectionOverlayView(this) }
    private var selectedBitmap: Bitmap? = null
    private var pendingAppointment: AppointmentDetails? = null

    private val imagePickerLauncher =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
            if (uri != null) {
                loadImageFromUri(uri)
            }
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

        // Add selection overlay on top with proper layout parameters
        val layoutParams = androidx.coordinatorlayout.widget.CoordinatorLayout.LayoutParams(
            androidx.coordinatorlayout.widget.CoordinatorLayout.LayoutParams.MATCH_PARENT,
            androidx.coordinatorlayout.widget.CoordinatorLayout.LayoutParams.MATCH_PARENT
        )
        binding.rootContainer.addView(selectionOverlay, layoutParams)
        selectionOverlay.visibility = View.GONE

        binding.btnPrimary.setOnClickListener { onPrimaryButton() }
        binding.btnProcess?.setOnClickListener { processSelectionAndExtract() }

        // Remove the save ICS button - we'll use dialog buttons instead
        binding.btnSaveIcs.visibility = View.GONE

        ocrExecutor = Executors.newSingleThreadExecutor()

        // Start in image selection mode
        updateUI()
    }

    private fun updateUI() {
        if (selectedBitmap == null) {
            // No image selected - show home screen
            binding.homeScreen?.visibility = View.VISIBLE
            binding.imagePreview.visibility = View.GONE
            binding.btnProcess?.visibility = View.GONE
            selectionOverlay.visibility = View.GONE
            binding.btnPrimary.text = "Now Scan"
        } else {
            // Image selected - hide home screen, show image with processing button
            binding.homeScreen?.visibility = View.GONE
            binding.imagePreview.visibility = View.VISIBLE
            binding.btnProcess?.visibility = View.VISIBLE
            selectionOverlay.visibility = View.VISIBLE
            binding.btnPrimary.text = "Now Scan" // Keep home button text
        }
    }

    private fun onPrimaryButton() {
        if (selectedBitmap == null) {
            // Launch image picker
            imagePickerLauncher.launch("image/*")
        } else {
            // Process the selected area
            processSelectionAndExtract()
        }
    }

    private fun loadImageFromUri(uri: Uri) {
        try {
            val inputStream = contentResolver.openInputStream(uri)
            val bitmap = BitmapFactory.decodeStream(inputStream)
            inputStream?.close()

            if (bitmap != null) {
                selectedBitmap = bitmap
                binding.imagePreview.setImageBitmap(bitmap)
                selectionOverlay.reset()
                updateUI()
                Toast.makeText(this, "Image loaded. Draw a selection box around appointment details.", Toast.LENGTH_LONG).show()
            } else {
                Toast.makeText(this, "Failed to load image", Toast.LENGTH_SHORT).show()
            }
        } catch (e: IOException) {
            Log.e("MainActivity", "Error loading image", e)
            Toast.makeText(this, "Error loading image: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun processSelectionAndExtract() {
        val bitmap = selectedBitmap
        if (bitmap == null) {
            Toast.makeText(this, "No image selected. Please choose an image first.", Toast.LENGTH_LONG).show()
            return
        }

        // Get the selected area or use entire image if no selection
        val crop: Bitmap =
            if (selectionOverlay.hasValidSelection()) {
                val selection = selectionOverlay.getSelection()!!
                cropBitmapToSelection(bitmap, selection) ?: bitmap
            } else {
                // No selection - ask user to draw one
                Toast.makeText(this, "Please draw a selection box around the appointment details first.", Toast.LENGTH_LONG).show()
                return
            }

        // Show prominent processing feedback
        showProgressDialog("🔍 Analyzing image with AI...\nThis may take a few seconds.")
        binding.btnProcess?.isEnabled = false

        ocrExecutor.execute {
            try {
                // Send image directly to Gemini for analysis
                val appointmentDetails = runBlocking { extractFromImageWithGemini(crop) }

                runOnUiThread {
                    hideProgressDialog()
                    binding.btnProcess?.isEnabled = true
                    binding.btnProcess?.visibility = View.GONE // Hide button after processing

                    // Use OCR text as description
                    val ocrResult = runBlocking { OcrProcessor.recognize(crop) }
                    showAppointmentDetails(appointmentDetails, ocrResult.text)
                }
            } catch (e: Exception) {
                Log.w("MainActivity", "Gemini analysis failed, falling back to OCR", e)
                runOnUiThread {
                    // Update progress dialog for fallback
                    showProgressDialog("📄 Analyzing text with OCR...\nThis may take a moment.")
                    // Fallback to OCR + local extraction
                    fallbackToLocalExtraction(crop)
                }
            }
        }
    }

    private fun fallbackToLocalExtraction(crop: Bitmap) {
        ocrExecutor.execute {
            try {
                // Run OCR on the cropped area
                val result = runBlocking { OcrProcessor.recognize(crop) }
                val text = result.text

                runOnUiThread {
                    hideProgressDialog()
                    binding.btnProcess?.isEnabled = true
                    binding.btnProcess?.visibility = View.GONE // Hide button after processing

                    if (text.isNotBlank()) {
                        // Try AI extraction with text, fall back to local extraction if needed
                        extractWithAI(text)
                    } else {
                        Toast.makeText(
                            this@MainActivity,
                            "No text detected in the selected area. Please try a different selection.",
                            Toast.LENGTH_LONG,
                        ).show()
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    hideProgressDialog()
                    binding.btnProcess?.isEnabled = true
                    binding.btnProcess?.visibility = View.GONE
                    Log.e("MainActivity", "OCR failed", e)
                    Toast.makeText(this@MainActivity, "OCR failed: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun cropBitmapToSelection(
        source: Bitmap,
        selectionInView: RectF,
    ): Bitmap? {
        // Get the displayed image dimensions within the ImageView
        val drawable = binding.imagePreview.drawable ?: return null
        val imageMatrix = binding.imagePreview.imageMatrix

        val drawableRect = RectF(0f, 0f, drawable.intrinsicWidth.toFloat(), drawable.intrinsicHeight.toFloat())
        val displayedRect = RectF()
        imageMatrix.mapRect(displayedRect, drawableRect)

        // Intersect selection with displayed image area
        val intersect =
            RectF(
                max(selectionInView.left, displayedRect.left),
                max(selectionInView.top, displayedRect.top),
                min(selectionInView.right, displayedRect.right),
                min(selectionInView.bottom, displayedRect.bottom),
            )
        if (intersect.width() <= 1f || intersect.height() <= 1f) return null

        // Map from view coordinates to bitmap coordinates
        val scaleX = source.width / displayedRect.width()
        val scaleY = source.height / displayedRect.height()

        val leftPx = ((intersect.left - displayedRect.left) * scaleX).toInt().coerceIn(0, source.width)
        val topPx = ((intersect.top - displayedRect.top) * scaleY).toInt().coerceIn(0, source.height)
        val rightPx = ((intersect.right - displayedRect.left) * scaleX).toInt().coerceIn(0, source.width)
        val bottomPx = ((intersect.bottom - displayedRect.top) * scaleY).toInt().coerceIn(0, source.height)

        val cropW = (rightPx - leftPx).coerceAtLeast(1)
        val cropH = (bottomPx - topPx).coerceAtLeast(1)

        if (leftPx >= source.width || topPx >= source.height || cropW <= 1 || cropH <= 1) return null

        return try {
            Bitmap.createBitmap(source, leftPx, topPx, cropW, cropH)
        } catch (e: IllegalArgumentException) {
            null
        }
    }

    data class AppointmentDetails(
        val dates: List<String>,
        val times: List<String>,
        val locations: List<String>,
        val originalText: String,
    )

    /**
     * Extracts appointment details directly from image using Gemini Vision
     */
    private suspend fun extractFromImageWithGemini(bitmap: Bitmap): AppointmentDetails {
        // Replace with your actual Gemini API key
        val apiKey = "AIzaSyCOpiy_zYAXNf9u4FPQ8Mt-NkY8BK0jIUU" // Your Gemini API key

        if (apiKey == "YOUR_GEMINI_API_KEY_HERE") {
            throw Exception("No API key configured - Please add your Gemini API key")
        }

        val generativeModel =
            GenerativeModel(
                modelName = "gemini-2.5-flash", // Using latest flash model for image analysis
                apiKey = apiKey,
                generationConfig =
                    generationConfig {
                        temperature = 0.1f
                        maxOutputTokens = 500
                    },
            )

        val prompt = """
        Analyze this appointment image and extract ONLY the following information:
        
        DATE: [Find the single appointment date - format as found in image]
        TIME: [Find the earliest appointment time if multiple times exist - format as found in image]
        LOCATION: [Extract ONLY the address from venue/building name to postcode]
        
        Rules:
        - Return exactly one date (the appointment date)
        - Return exactly one time (earliest if multiple)
        - For LOCATION: Extract ONLY the address portion, starting from venue/hospital/clinic name and ending with postcode/ZIP
        - DO NOT include appointment details, times, dates, or other non-address text
        - Start from: Hospital name, Clinic name, Medical Center, Practice name, or building name
        - End at: Postcode (UK: SW1A 1AA), ZIP code (US: 12345), or last line of address
        - Include: Venue name, street address, city, state/county, postcode
        - Exclude: Appointment time, date, doctor names (unless part of practice name), phone numbers
        - Reorder address components into proper format: [Building/Venue Name], [Street Number Street Name], [City], [State/Region], [Postcode/ZIP]
        - If words appear to be in wrong order, reorder them correctly
        - If any field is not found, return "Not found" for that field
        - Do not include any other text or explanations
        
        Format your response exactly like this:
        DATE: [date or "Not found"]
        TIME: [time or "Not found"]
        LOCATION: [venue name to postcode address only, or "Not found"]
        """

        val inputContent =
            content {
                image(bitmap)
                text(prompt)
            }

        val response = generativeModel.generateContent(inputContent)
        val responseText = response.text ?: throw Exception("Empty response from Gemini")

        return parseGeminiImageResponse(responseText)
    }

    /**
     * Parses the structured response from Gemini image analysis
     */
    private fun parseGeminiImageResponse(response: String): AppointmentDetails {
        val dates = mutableListOf<String>()
        val times = mutableListOf<String>()
        val locations = mutableListOf<String>()

        Log.d("Gemini", "Gemini Image Response: $response")

        response.lines().forEach { line ->
            val trimmedLine = line.trim()
            when {
                trimmedLine.startsWith("DATE:", true) -> {
                    val dateStr = trimmedLine.substringAfter(":").trim()
                    if (dateStr.isNotEmpty() && dateStr != "Not found") {
                        dates.add(dateStr)
                    }
                }
                trimmedLine.startsWith("TIME:", true) -> {
                    val timeStr = trimmedLine.substringAfter(":").trim()
                    if (timeStr.isNotEmpty() && timeStr != "Not found") {
                        times.add(timeStr)
                    }
                }
                trimmedLine.startsWith("LOCATION:", true) -> {
                    val locationStr = trimmedLine.substringAfter(":").trim()
                    if (locationStr.isNotEmpty() && locationStr != "Not found") {
                        // Don't enhance - Gemini Vision already provides clean addresses
                        locations.add(locationStr)
                    }
                }
            }
        }

        Log.d("Gemini", "Parsed from image - Dates: $dates, Times: $times, Locations: $locations")

        return AppointmentDetails(dates, times, locations, "Extracted from image")
    }

    // AI-powered extraction using Google Gemini
    private fun extractWithAI(ocrText: String) {
        // Update progress dialog for AI processing
        showProgressDialog("🤖 Processing with AI...\nExtracting appointment details.")

        ocrExecutor.execute {
            try {
                val appointmentDetails = runBlocking { performAIExtraction(ocrText) }
                runOnUiThread {
                    hideProgressDialog()
                    binding.btnProcess?.isEnabled = true
                    showAppointmentDetails(appointmentDetails, ocrText)
                }
            } catch (e: Exception) {
                Log.w("AI", "AI extraction failed, using local extraction: ${e.message}")
                runOnUiThread {
                    // Fallback to local extraction
                    val appointmentDetails = extractAppointmentDetails(ocrText)
                    hideProgressDialog()
                    binding.btnProcess?.isEnabled = true
                    showAppointmentDetails(appointmentDetails, ocrText)
                }
            }
        }
    }

    private suspend fun performAIExtraction(ocrText: String): AppointmentDetails {
        // Replace with your actual Gemini API key
        val apiKey = "AIzaSyCOpiy_zYAXNf9u4FPQ8Mt-NkY8BK0jIUU" // Your Gemini API key

        if (apiKey == "YOUR_GEMINI_API_KEY_HERE") {
            Log.w("AI", "No API key configured, using local extraction")
            return extractAppointmentDetails(ocrText)
        }

        val generativeModel =
            GenerativeModel(
                modelName = "gemini-2.5-flash",
                apiKey = apiKey,
                generationConfig =
                    generationConfig {
                        temperature = 0.1f // Low temperature for consistent extraction
                        maxOutputTokens = 1000
                    },
            )

        val prompt = """
        You are an expert at extracting appointment information from OCR text. The text may contain errors or be poorly formatted.
        
        Extract these details from the text below and format your response EXACTLY as shown:
        
        DATES: [list all dates found, in any format - separate with commas]
        TIMES: [list all times found, including AM/PM if present - separate with commas]
        LOCATIONS: [extract ONLY address portions from venue name to postcode - separate multiple locations with commas]
        
        Rules:
        - Extract ALL possible dates and times, even if multiple
        - Include partial information (like just month/day if year is missing)
        - For LOCATIONS: Extract ONLY the address portion, starting from venue/facility name and ending with postcode
        - Start address extraction from: Hospital name, Clinic name, Medical Center, Surgery, Practice name, or building name
        - End address extraction at: Postcode (UK: SW1A 1AA), ZIP code (US: 12345), or Canadian postal code
        - Include in address: Venue name, street number and name, city, state/county, postcode
        - Exclude from address: Appointment times, dates, phone numbers, doctor names (unless part of practice name)
        - Reorder address components into proper format: [Building/Venue Name], [Street Number Street Name], [City], [State/Region], [Postcode/ZIP]
        - Fix scrambled addresses where words may be in wrong order due to OCR errors
        - Examples of corrections:
          * "Road Hospital Main 123" → "Hospital, 123 Main Road"
          * "Suite London 5 Street Baker" → "5 Baker Street, Suite, London"
          * "Center Medical City Health" → "City Health Medical Center"
        - Combine fragmented address parts into coherent, properly formatted addresses
        - Remove duplicate or redundant address information
        - Use the exact format above with DATES:, TIMES:, LOCATIONS:
        
        OCR Text to analyze:
        $ocrText
        """

        val response = generativeModel.generateContent(prompt)
        return parseAIResponse(response.text ?: "", ocrText)
    }

    private fun parseAIResponse(
        response: String,
        originalText: String,
    ): AppointmentDetails {
        val dates = mutableListOf<String>()
        val times = mutableListOf<String>()
        val locations = mutableListOf<String>()

        Log.d("AI", "AI Response: $response")

        response.lines().forEach { line ->
            val trimmedLine = line.trim()
            when {
                trimmedLine.startsWith("DATES:", true) -> {
                    val dateStr = trimmedLine.substringAfter(":").trim()
                    if (dateStr.isNotEmpty() && dateStr != "[none found]" && dateStr != "None" && dateStr != "Not found") {
                        dates.addAll(dateStr.split(",").map { it.trim() }.filter { it.isNotEmpty() })
                    }
                }
                trimmedLine.startsWith("TIMES:", true) -> {
                    val timeStr = trimmedLine.substringAfter(":").trim()
                    if (timeStr.isNotEmpty() && timeStr != "[none found]" && timeStr != "None" && timeStr != "Not found") {
                        times.addAll(timeStr.split(",").map { it.trim() }.filter { it.isNotEmpty() })
                    }
                }
                trimmedLine.startsWith("LOCATIONS:", true) -> {
                    val locationStr = trimmedLine.substringAfter(":").trim()
                    if (locationStr.isNotEmpty() && locationStr != "[none found]" && locationStr != "None" && locationStr != "Not found") {
                        // Don't enhance - Gemini already provides clean addresses
                        locations.add(locationStr)
                    }
                }
            }
        }

        Log.d("AI", "Parsed - Dates: $dates, Times: $times, Locations: $locations")

        return AppointmentDetails(dates, times, locations, originalText)
    }

    // Simple local extraction fallback
    private fun extractAppointmentDetails(text: String): AppointmentDetails {
        val dates = mutableListOf<String>()
        val times = mutableListOf<String>()
        val locations = mutableListOf<String>()

        // Basic date patterns
        val datePattern = Pattern.compile("\\b\\d{1,2}[/\\-.]\\d{1,2}[/\\-.]\\d{2,4}\\b")
        val timePattern = Pattern.compile("\\b\\d{1,2}:\\d{2}\\s*(AM|PM|am|pm)?\\b")
        
        val dateMatcher = datePattern.matcher(text)
        while (dateMatcher.find()) {
            dates.add(dateMatcher.group())
        }

        val timeMatcher = timePattern.matcher(text)
        while (timeMatcher.find()) {
            times.add(timeMatcher.group())
        }

        return AppointmentDetails(dates, times, locations, text)
    }

    private fun showAppointmentDetails(
        details: AppointmentDetails,
        originalText: String,
    ) {
        // Store for potential ICS creation
        pendingAppointment = details
        
        val message =
            buildString {
                // Always show date field
                val dateText =
                    if (details.dates.isNotEmpty()) {
                        details.dates.first()
                    } else {
                        "Not found"
                    }
                appendLine("📅 DATE: $dateText")
                appendLine()

                // Always show time field
                val timeText =
                    if (details.times.isNotEmpty()) {
                        findEarliestTime(details.times)
                    } else {
                        "Not found"
                    }
                appendLine("⏰ TIME: $timeText")
                appendLine()

                // Always show location field
                val locationText =
                    if (details.locations.isNotEmpty()) {
                        details.locations.first()
                    } else {
                        "Not found"
                    }
                appendLine("📍 LOCATION: $locationText")
                appendLine()

                // Always show description from OCR text
                if (originalText.isNotBlank()) {
                    appendLine("📝 DESCRIPTION:")
                    appendLine(originalText.trim())
                }
            }

        AlertDialog.Builder(this)
            .setTitle("📅 Appointment Details")
            .setMessage(message)
            .setPositiveButton("💾 Save to ICS File") { dialog, _ ->
                dialog.dismiss()
                saveAppointmentAsIcs(details)
            }
            .setNeutralButton("✨ New Selection") { dialog, _ ->
                dialog.dismiss()
                // Keep the same image but allow new selection, show processing button
                binding.btnProcess?.visibility = View.VISIBLE
                selectionOverlay.reset()
            }
            .setNegativeButton("📷 New Image") { dialog, _ ->
                dialog.dismiss()
                // Reset to choose new image
                selectedBitmap = null
                selectionOverlay.reset()
                updateUI()
            }
            .setCancelable(true)
            .show()
    }

    /**
     * Creates ICS content from appointment details and saves it
     */
    private fun saveAppointmentAsIcs(details: AppointmentDetails) {
        try {
            val summary = "Appointment" // Default summary
            val dateStr = if (details.dates.isNotEmpty()) details.dates.first() else ""
            val timeStr = if (details.times.isNotEmpty()) details.times.first() else ""
            val location = if (details.locations.isNotEmpty()) details.locations.first() else ""
            
            // Parse date and time or use defaults
            val now = ZonedDateTime.now()
            val appointmentDateTime = parseDateTime(dateStr, timeStr) ?: now.plusDays(1).withHour(9).withMinute(0)
            val endDateTime = appointmentDateTime.plusHours(1)
            
            // Create ICS content
            val ics = IcsWriter.buildEvent(
                summary = summary,
                start = appointmentDateTime,
                end = endDateTime,
                location = if (location.isNotBlank()) location else null,
                description = "Created from scanned appointment details\n\nExtracted information:\nDate: $dateStr\nTime: $timeStr\nLocation: $location"
            )
            
            // Store ICS content for saving
            pendingIcsContent = ics
            
            // Launch file picker
            val fileName = "appointment_${System.currentTimeMillis()}.ics"
            createDocumentLauncher.launch(fileName)
            
        } catch (e: Exception) {
            Toast.makeText(this, "Error creating calendar file: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private var pendingIcsContent: String? = null

    private fun saveIcsToUri(uri: Uri) {
        val content = pendingIcsContent ?: return
        try {
            contentResolver.openOutputStream(uri)?.use { os ->
                os.write(content.toByteArray(Charsets.UTF_8))
            }
            Toast.makeText(this, "📅 Calendar event saved successfully!", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Failed to save calendar file: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    /**
     * Parse date and time strings into ZonedDateTime
     */
    private fun parseDateTime(dateStr: String, timeStr: String): ZonedDateTime? {
        return try {
            val now = ZonedDateTime.now()
            // For simplicity, just use current date/time plus parsed info if available
            // In a real app, you'd want more sophisticated parsing
            val baseDateTime = now.plusDays(1).withHour(9).withMinute(0)
            
            // Try to parse time
            if (timeStr.isNotBlank()) {
                val timePattern = Pattern.compile("(\\d{1,2}):(\\d{2})\\s*(AM|PM|am|pm)?")
                val matcher = timePattern.matcher(timeStr)
                if (matcher.find()) {
                    var hour = matcher.group(1).toInt()
                    val minute = matcher.group(2).toInt()
                    val ampm = matcher.group(3)?.uppercase()
                    
                    if (ampm == "PM" && hour != 12) hour += 12
                    if (ampm == "AM" && hour == 12) hour = 0
                    
                    return baseDateTime.withHour(hour).withMinute(minute)
                }
            }
            
            baseDateTime
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Finds the earliest valid time from a list of time strings
     */
    private fun findEarliestTime(times: List<String>): String {
        if (times.isEmpty()) return ""
        if (times.size == 1) return times.first()

        // For simplicity, just return first time
        // In a real app, you'd want more sophisticated time parsing
        return times.first()
    }

    /**
     * Shows a prominent progress dialog
     */
    private fun showProgressDialog(message: String) {
        hideProgressDialog()
        
        progressDialog = AlertDialog.Builder(this)
            .setTitle("Processing")
            .setMessage(message)
            .setCancelable(false)
            .create()
        
        progressDialog?.show()
    }
    
    /**
     * Hides the progress dialog
     */
    private fun hideProgressDialog() {
        progressDialog?.dismiss()
        progressDialog = null
    }

    override fun onDestroy() {
        super.onDestroy()
        hideProgressDialog()
        ocrExecutor.shutdown()
    }
}