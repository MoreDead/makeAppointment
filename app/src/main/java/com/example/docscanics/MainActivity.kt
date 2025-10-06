package com.example.docscanics

import android.graphics.*
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
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
        Log.d("MainActivity", "onCreate called - app version with title dialog")
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
                    
                    // Debug logging
                    Log.d("MainActivity", "Extracted dates: ${appointmentDetails.dates}")
                    Log.d("MainActivity", "Extracted times: ${appointmentDetails.times}")
                    Log.d("MainActivity", "Extracted locations: ${appointmentDetails.locations}")
                    Log.d("MainActivity", "OCR text length: ${ocrResult.text.length}")
                    
                    showAppointmentDetails(appointmentDetails, ocrResult.text)
                }
            } catch (e: Exception) {
                val isOverloaded = e.message?.contains("overloaded") == true || e.message?.contains("503") == true
                val errorMsg = if (isOverloaded) "Gemini AI is busy, using OCR + local analysis" else "Gemini analysis failed, using OCR + local analysis"
                Log.w("MainActivity", errorMsg, e)
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
                val isOverloaded = e.message?.contains("overloaded") == true || e.message?.contains("503") == true
                val errorMsg = if (isOverloaded) "AI service busy, using local extraction" else "AI extraction failed, using local extraction: ${e.message}"
                Log.w("AI", errorMsg)
                runOnUiThread {
                    // Update dialog to show local processing
                    showProgressDialog("🔍 Using local text analysis...\nExtracting appointment details.")
                    // Give a brief delay for user to see the message
                    binding.btnProcess?.postDelayed({
                        hideProgressDialog()
                        val appointmentDetails = extractAppointmentDetails(ocrText)
                        binding.btnProcess?.isEnabled = true
                        showAppointmentDetails(appointmentDetails, ocrText)
                    }, 500)
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

    // Enhanced local extraction fallback
    private fun extractAppointmentDetails(text: String): AppointmentDetails {
        val dates = mutableListOf<String>()
        val times = mutableListOf<String>()
        val locations = mutableListOf<String>()

        Log.d("LocalExtract", "Extracting from text: ${text.take(200)}...")

        // Enhanced date patterns
        val datePatterns = listOf(
            Pattern.compile("\\b\\d{1,2}[/\\-.]\\d{1,2}[/\\-.]\\d{2,4}\\b"), // 12/25/2024, 12-25-24
            Pattern.compile("\\b\\d{1,2}(st|nd|rd|th)?\\s+(Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec)\\w*\\s+\\d{2,4}\\b", Pattern.CASE_INSENSITIVE), // 25th March 2024
            Pattern.compile("\\b(Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec)\\w*\\s+\\d{1,2}(st|nd|rd|th)?\\s*,?\\s*\\d{2,4}\\b", Pattern.CASE_INSENSITIVE), // March 25, 2024
            Pattern.compile("\\b(Monday|Tuesday|Wednesday|Thursday|Friday|Saturday|Sunday)\\s+\\d{1,2}(st|nd|rd|th)?\\s+(Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec)\\w*", Pattern.CASE_INSENSITIVE) // Monday 25th March
        )
        
        datePatterns.forEach { pattern ->
            val matcher = pattern.matcher(text)
            while (matcher.find()) {
                dates.add(matcher.group())
            }
        }

        // Enhanced time patterns
        val timePatterns = listOf(
            Pattern.compile("\\b\\d{1,2}:\\d{2}\\s*(AM|PM|am|pm)\\b"), // 2:30 PM
            Pattern.compile("\\b\\d{1,2}:\\d{2}\\b"), // 14:30
            Pattern.compile("\\b\\d{1,2}\\s*(AM|PM|am|pm)\\b") // 2 PM
        )
        
        timePatterns.forEach { pattern ->
            val matcher = pattern.matcher(text)
            while (matcher.find()) {
                times.add(matcher.group())
            }
        }

        // Enhanced location extraction - look for address patterns
        val locationPatterns = listOf(
            // Look for postcode patterns (UK, US, etc.)
            Pattern.compile("([A-Z][A-Za-z\\s,.-]+?)\\s+([A-Z]{1,2}\\d{1,2}[A-Z]?\\s*\\d[A-Z]{2})\\b"), // UK postcodes
            Pattern.compile("([A-Za-z\\s,.-]+?)\\s+(\\d{5}(-\\d{4})?)\\b"), // US ZIP codes
            // Look for hospital/clinic patterns
            Pattern.compile("(\\b(Hospital|Clinic|Medical Centre?|Medical Center|Surgery|Practice|Health Centre?)\\b[^\\n]*)", Pattern.CASE_INSENSITIVE),
            // Look for street address patterns
            Pattern.compile("(\\d+\\s+[A-Za-z\\s]+(Street|St|Road|Rd|Avenue|Ave|Lane|Ln|Drive|Dr|Close|Cl|Way)\\b[^\\n]*)", Pattern.CASE_INSENSITIVE)
        )
        
        locationPatterns.forEach { pattern ->
            val matcher = pattern.matcher(text)
            while (matcher.find()) {
                val location = matcher.group(1)?.trim() ?: matcher.group().trim()
                if (location.length > 5) { // Avoid very short matches
                    locations.add(location)
                }
            }
        }

        // If no structured location found, look for common location keywords
        if (locations.isEmpty()) {
            val keywordPattern = Pattern.compile("(?i)(?:at|visit|location:|address:)\\s*([^\\n]{10,100})")
            val keywordMatcher = keywordPattern.matcher(text)
            while (keywordMatcher.find()) {
                val location = keywordMatcher.group(1)?.trim()
                if (!location.isNullOrBlank() && location.length > 10) {
                    locations.add(location)
                }
            }
        }

        Log.d("LocalExtract", "Found dates: $dates")
        Log.d("LocalExtract", "Found times: $times")
        Log.d("LocalExtract", "Found locations: $locations")

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
            .setPositiveButton("Save to Calendar") { dialog, _ ->
                Log.d("MainActivity", "Save to Calendar button clicked")
                Toast.makeText(this@MainActivity, "Save to Calendar clicked - opening title dialog", Toast.LENGTH_SHORT).show()
                dialog.dismiss()
                showTitleSelectionDialog(details, originalText)
            }
            .setNeutralButton("New Selection") { dialog, _ ->
                dialog.dismiss()
                // Keep the same image but allow new selection, show processing button
                binding.btnProcess?.visibility = View.VISIBLE
                selectionOverlay.reset()
            }
            .setNegativeButton("New Image") { dialog, _ ->
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
     * Shows the title selection dialog for customizing appointment title
     */
    private fun showTitleSelectionDialog(details: AppointmentDetails, originalText: String) {
        Log.d("MainActivity", "showTitleSelectionDialog called")
        try {
            // Test with simple dialog first
            AlertDialog.Builder(this)
                .setTitle("Title Selection Test")
                .setMessage("This is a test of the title selection dialog. If you see this, the method is being called.")
                .setPositiveButton("Continue with Custom Dialog") { dialog, _ ->
                    dialog.dismiss()
                    showFullTitleSelectionDialog(details, originalText)
                }
                .setNegativeButton("Skip to Save") { dialog, _ ->
                    dialog.dismiss()
                    saveAppointmentAsIcs(details, originalText)
                }
                .show()
        } catch (e: Exception) {
            Log.e("MainActivity", "Error in showTitleSelectionDialog", e)
            Toast.makeText(this, "Error showing title selection: ${e.message}", Toast.LENGTH_LONG).show()
            // Fallback to direct save
            saveAppointmentAsIcs(details, originalText)
        }
    }
    
    private fun showFullTitleSelectionDialog(details: AppointmentDetails, originalText: String) {
        Log.d("MainActivity", "showFullTitleSelectionDialog called")
        try {
            val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_title_selection, null)
            Log.d("MainActivity", "Dialog layout inflated successfully")
        
        val spinnerWords = dialogView.findViewById<Spinner>(R.id.spinnerTitleWords)
        val editNewWord = dialogView.findViewById<TextInputEditText>(R.id.editNewWord)
        val btnAddWord = dialogView.findViewById<MaterialButton>(R.id.btnAddWord)
        val textPreview = dialogView.findViewById<TextView>(R.id.textTitlePreview)
        val btnCancel = dialogView.findViewById<MaterialButton>(R.id.btnCancel)
        val btnSave = dialogView.findViewById<MaterialButton>(R.id.btnSave)
        
        // Get appointment data
        val locationText = if (details.locations.isNotEmpty()) details.locations.first() else "General Hospital, London"
        val timeText = if (details.times.isNotEmpty()) details.times.first() else "2:30 PM"
        
        // Setup spinner with title words
        val titleWords = TitleHelper.getAllTitleWords(this).toMutableList()
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, titleWords)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerWords.adapter = adapter
        
        // Function to update preview
        fun updatePreview() {
            val selectedWord = spinnerWords.selectedItem?.toString() ?: ""
            val previewTitle = TitleHelper.formatAppointmentTitle(selectedWord, locationText, timeText)
            textPreview.text = previewTitle
        }
        
        // Initial preview update
        updatePreview()
        
        // Update preview when spinner selection changes
        spinnerWords.setOnItemSelectedListener(object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                updatePreview()
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        })
        
        // Add word functionality
        btnAddWord.setOnClickListener {
            val newWord = editNewWord.text?.toString() ?: ""
            if (newWord.isBlank()) {
                Toast.makeText(this, "Please enter a word to add", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            
            if (TitleHelper.addCustomWord(this, newWord)) {
                // Refresh spinner with new word
                titleWords.clear()
                titleWords.addAll(TitleHelper.getAllTitleWords(this))
                adapter.notifyDataSetChanged()
                
                // Select the new word
                val newWordIndex = titleWords.indexOfFirst { it.equals(newWord.trim().replaceFirstChar { it.uppercase() }, ignoreCase = true) }
                if (newWordIndex >= 0) {
                    spinnerWords.setSelection(newWordIndex)
                }
                
                editNewWord.text?.clear()
                Toast.makeText(this, "Word added successfully!", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Word already exists or is invalid", Toast.LENGTH_SHORT).show()
            }
        }
        
        val dialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .setCancelable(false)
            .create()
        
        // Button handlers
        btnCancel.setOnClickListener {
            dialog.dismiss()
        }
        
        btnSave.setOnClickListener {
            val selectedWord = spinnerWords.selectedItem?.toString() ?: ""
            val customTitle = TitleHelper.formatAppointmentTitle(selectedWord, locationText, timeText)
            dialog.dismiss()
            saveAppointmentAsIcs(details, originalText, customTitle)
        }
        
            Log.d("MainActivity", "About to show title selection dialog")
            dialog.show()
            Log.d("MainActivity", "Title selection dialog shown successfully")
        } catch (e: Exception) {
            Log.e("MainActivity", "Error in showTitleSelectionDialog", e)
            Toast.makeText(this, "Error showing title selection: ${e.message}", Toast.LENGTH_LONG).show()
            // Fallback to direct save
            saveAppointmentAsIcs(details, originalText)
        }
    }
    
    /**
     * Creates ICS content from appointment details and saves it
     */
    private fun saveAppointmentAsIcs(details: AppointmentDetails, originalText: String, customTitle: String? = null) {
        try {
            val dateStr = if (details.dates.isNotEmpty()) details.dates.first() else ""
            val timeStr = if (details.times.isNotEmpty()) details.times.first() else ""
            val locationStr = if (details.locations.isNotEmpty()) details.locations.first() else ""
            
            // Use custom title if provided, otherwise create meaningful summary from extracted data
            val summary = customTitle ?: when {
                dateStr.isNotBlank() && timeStr.isNotBlank() -> "Appointment - $dateStr at $timeStr"
                dateStr.isNotBlank() -> "Appointment - $dateStr"
                timeStr.isNotBlank() -> "Appointment - $timeStr"
                else -> "Appointment"
            }
            
            // Parse date and time or use defaults
            val now = ZonedDateTime.now()
            val appointmentDateTime = parseDateTime(dateStr, timeStr) ?: now.plusDays(1).withHour(9).withMinute(0)
            val endDateTime = appointmentDateTime.plusHours(1)
            
            // Create comprehensive description
            val description = buildString {
                appendLine("Created from scanned appointment details")
                appendLine()
                if (dateStr.isNotBlank()) {
                    appendLine("📅 Date: $dateStr")
                }
                if (timeStr.isNotBlank()) {
                    appendLine("⏰ Time: $timeStr")
                }
                if (locationStr.isNotBlank()) {
                    appendLine("📍 Location: $locationStr")
                }
                appendLine()
                appendLine("📝 Original Text:")
                appendLine(originalText.trim())
            }
            
            // Create ICS content with all details
            val ics = IcsWriter.buildEvent(
                summary = summary,
                start = appointmentDateTime,
                end = endDateTime,
                location = if (locationStr.isNotBlank() && locationStr != "Not found") locationStr else null,
                description = description
            )
            
            // Store ICS content for saving
            pendingIcsContent = ics
            
            // Launch file picker
            val fileName = "appointment_${System.currentTimeMillis()}.ics"
            createDocumentLauncher.launch(fileName)
            
            // Log details for debugging
            Log.d("ICS", "Creating ICS with:")
            Log.d("ICS", "Summary: $summary")
            Log.d("ICS", "Location: $locationStr")
            Log.d("ICS", "Description length: ${description.length}")
            Log.d("ICS", "DateTime: $appointmentDateTime")
            
        } catch (e: Exception) {
            Log.e("ICS", "Error creating ICS file", e)
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
            
            // Show dialog with option to open the file
            AlertDialog.Builder(this)
                .setTitle("📅 Calendar Event Saved!")
                .setMessage("The appointment has been saved as an ICS file. Would you like to open it in your calendar app?")
                .setPositiveButton("📅 Open Calendar") { dialog, _ ->
                    dialog.dismiss()
                    openIcsFile(uri)
                }
                .setNegativeButton("Cancel") { dialog, _ ->
                    dialog.dismiss()
                }
                .setCancelable(true)
                .show()
                
        } catch (e: Exception) {
            Toast.makeText(this, "Failed to save calendar file: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }
    
    /**
     * Opens the ICS file with the default calendar application
     */
    private fun openIcsFile(uri: Uri) {
        try {
            val intent = android.content.Intent().apply {
                action = android.content.Intent.ACTION_VIEW
                setDataAndType(uri, "text/calendar")
                addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            
            // Check if there's an app that can handle calendar files
            if (intent.resolveActivity(packageManager) != null) {
                startActivity(intent)
            } else {
                // Fallback: try to open with any app that can handle the file
                val chooserIntent = android.content.Intent.createChooser(intent, "Open calendar file with:")
                startActivity(chooserIntent)
            }
        } catch (e: Exception) {
            Log.e("MainActivity", "Failed to open ICS file", e)
            Toast.makeText(this, "Could not open calendar file. Please check your calendar app.", Toast.LENGTH_LONG).show()
        }
    }

    /**
     * Parse date and time strings into ZonedDateTime
     */
    private fun parseDateTime(dateStr: String, timeStr: String): ZonedDateTime? {
        return try {
            val now = ZonedDateTime.now()
            var appointmentDateTime = now.plusDays(1).withHour(9).withMinute(0) // Default fallback
            
            // Try to parse date first
            if (dateStr.isNotBlank() && dateStr != "Not found") {
                Log.d("MainActivity", "Parsing date: '$dateStr'")
                
                // Try different date patterns
                val datePatterns = listOf(
                    // DD/MM/YYYY, MM/DD/YYYY patterns
                    Pattern.compile("(\\d{1,2})[/\\-](\\d{1,2})[/\\-](\\d{2,4})"),
                    // DD Month YYYY pattern (e.g., "11 Oct", "11 October 2024")
                    Pattern.compile("(\\d{1,2})\\s+(Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec)\\w*(?:\\s+(\\d{2,4}))?", Pattern.CASE_INSENSITIVE),
                    // Month DD YYYY pattern (e.g., "Oct 11", "October 11, 2024")
                    Pattern.compile("(Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec)\\w*\\s+(\\d{1,2}),?(?:\\s+(\\d{2,4}))?", Pattern.CASE_INSENSITIVE)
                )
                
                for (pattern in datePatterns) {
                    val matcher = pattern.matcher(dateStr)
                    if (matcher.find()) {
                        try {
                            val parts = listOf(matcher.group(1), matcher.group(2), matcher.group(3))
                            Log.d("MainActivity", "Date parts found: $parts")
                            
                            // Try to parse the actual date components
                            // This is a basic implementation - in production you'd want more sophisticated parsing
                            val part1 = parts[0]?.trim() ?: ""
                            val part2 = parts[1]?.trim() ?: ""
                            val part3 = parts[2]?.trim() ?: ""
                            
                            Log.d("MainActivity", "Date parts: '$part1', '$part2', '$part3'")
                            
                            // Month name to number mapping
                            val monthMap = mapOf(
                                "jan" to 1, "january" to 1,
                                "feb" to 2, "february" to 2,
                                "mar" to 3, "march" to 3,
                                "apr" to 4, "april" to 4,
                                "may" to 5,
                                "jun" to 6, "june" to 6,
                                "jul" to 7, "july" to 7,
                                "aug" to 8, "august" to 8,
                                "sep" to 9, "september" to 9,
                                "oct" to 10, "october" to 10,
                                "nov" to 11, "november" to 11,
                                "dec" to 12, "december" to 12
                            )
                            
                            val (day, month, year) = when {
                                // Check if part2 is a month name (DD Month YYYY pattern)
                                monthMap.containsKey(part2.lowercase()) -> {
                                    val dayNum = part1.toIntOrNull() ?: 1
                                    val monthNum = monthMap[part2.lowercase()] ?: 1
                                    val yearNum = if (part3.isNotBlank()) part3.toIntOrNull() ?: now.year else now.year
                                    Triple(dayNum, monthNum, yearNum)
                                }
                                // Check if part1 is a month name (Month DD YYYY pattern)
                                monthMap.containsKey(part1.lowercase()) -> {
                                    val monthNum = monthMap[part1.lowercase()] ?: 1
                                    val dayNum = part2.toIntOrNull() ?: 1
                                    val yearNum = if (part3.isNotBlank()) part3.toIntOrNull() ?: now.year else now.year
                                    Triple(dayNum, monthNum, yearNum)
                                }
                                // Numeric patterns (DD/MM/YYYY or MM/DD/YYYY)
                                else -> {
                                    val num1 = part1.toIntOrNull() ?: 0
                                    val num2 = part2.toIntOrNull() ?: 0
                                    val num3 = part3.toIntOrNull() ?: now.year
                                    
                                    when {
                                        num3 > 31 -> { // num3 is year
                                            if (num1 > 12) Triple(num1, num2, num3) // DD/MM/YYYY
                                            else Triple(num2, num1, num3) // MM/DD/YYYY
                                        }
                                        else -> Triple(num1, num2, now.year) // Use current year if year not provided
                                    }
                                }
                            }
                            
                            // Ensure valid ranges
                            val validYear = if (year < 100) year + 2000 else year // Handle 2-digit years
                            val validMonth = month.coerceIn(1, 12)
                            val validDay = day.coerceIn(1, 31)
                            
                            appointmentDateTime = now.withYear(validYear).withMonth(validMonth).withDayOfMonth(validDay).withHour(9).withMinute(0)
                            Log.d("MainActivity", "Parsed date to: ${validDay}/${validMonth}/${validYear} (${validDay} ${getMonthName(validMonth)} ${validYear})")
                            break
                        } catch (e: Exception) {
                            Log.w("MainActivity", "Failed to parse date parts: ${e.message}")
                        }
                    }
                }
            }
            
            // Try to parse time and apply it to the date
            if (timeStr.isNotBlank() && timeStr != "Not found") {
                Log.d("MainActivity", "Parsing time: '$timeStr'")
                val timePattern = Pattern.compile("(\\d{1,2}):(\\d{2})\\s*(AM|PM|am|pm)?")
                val matcher = timePattern.matcher(timeStr)
                if (matcher.find()) {
                    var hour = matcher.group(1)?.toInt() ?: 9
                    val minute = matcher.group(2)?.toInt() ?: 0
                    val ampm = matcher.group(3)?.uppercase()
                    
                    if (ampm == "PM" && hour != 12) hour += 12
                    if (ampm == "AM" && hour == 12) hour = 0
                    
                    appointmentDateTime = appointmentDateTime.withHour(hour).withMinute(minute)
                    Log.d("MainActivity", "Parsed time to: ${appointmentDateTime.hour}:${appointmentDateTime.minute}")
                }
            }
            
            Log.d("MainActivity", "Final parsed datetime: $appointmentDateTime")
            appointmentDateTime
        } catch (e: Exception) {
            Log.e("MainActivity", "Error parsing date/time", e)
            null
        }
    }

    /**
     * Helper function to get month name for debugging
     */
    private fun getMonthName(month: Int): String {
        val months = arrayOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", 
                            "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")
        return if (month in 1..12) months[month - 1] else "Unknown"
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