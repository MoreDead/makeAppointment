package com.example.docscanics

import android.content.Context
import android.content.SharedPreferences

object TitleHelper {
    private const val PREFS_NAME = "title_words_prefs"
    private const val CUSTOM_WORDS_KEY = "custom_words"
    
    // Default title words provided with the app
    private val DEFAULT_WORDS = listOf(
        "Medical",
        "Dental", 
        "Hospital",
        "Clinic",
        "Checkup",
        "Surgery",
        "Appointment",
        "Consultation",
        "Treatment",
        "Visit",
        "Therapy",
        "Screening",
        "Follow-up",
        "Emergency",
        "Specialist"
    )
    
    /**
     * Get all available title words (default + custom)
     */
    fun getAllTitleWords(context: Context): List<String> {
        val customWords = getCustomWords(context)
        return (DEFAULT_WORDS + customWords).sorted()
    }
    
    /**
     * Get custom words added by user from SharedPreferences
     */
    fun getCustomWords(context: Context): List<String> {
        val prefs = getPreferences(context)
        val customWordsString = prefs.getString(CUSTOM_WORDS_KEY, "")
        return if (customWordsString.isNullOrBlank()) {
            emptyList()
        } else {
            customWordsString.split(",").map { it.trim() }.filter { it.isNotBlank() }
        }
    }
    
    /**
     * Add a new custom word and save to SharedPreferences
     */
    fun addCustomWord(context: Context, word: String): Boolean {
        val trimmedWord = word.trim().replaceFirstChar { it.uppercase() }
        if (trimmedWord.isBlank() || trimmedWord.length > 20) return false
        
        val allWords = getAllTitleWords(context)
        if (allWords.any { it.equals(trimmedWord, ignoreCase = true) }) {
            return false // Word already exists
        }
        
        val customWords = getCustomWords(context).toMutableList()
        customWords.add(trimmedWord)
        
        val prefs = getPreferences(context)
        prefs.edit().putString(CUSTOM_WORDS_KEY, customWords.joinToString(",")).apply()
        
        return true
    }
    
    /**
     * Format appointment title according to requirements:
     * [Selected Word] - [Location (15 chars or acronym)] - [Time]
     */
    fun formatAppointmentTitle(
        selectedWord: String,
        location: String,
        time: String
    ): String {
        val parts = mutableListOf<String>()
        
        // Part 1: Selected word
        if (selectedWord.isNotBlank()) {
            parts.add(selectedWord.trim())
        }
        
        // Part 2: Location formatting
        if (location.isNotBlank() && location != "Not found") {
            val formattedLocation = formatLocation(location)
            if (formattedLocation.isNotBlank()) {
                parts.add(formattedLocation)
            }
        }
        
        // Part 3: Time
        if (time.isNotBlank() && time != "Not found") {
            parts.add(time.trim())
        }
        
        return if (parts.isNotEmpty()) {
            parts.joinToString(" - ")
        } else {
            "Appointment"
        }
    }
    
    /**
     * Format location according to requirements:
     * - If 15 characters or less, use as-is
     * - If longer, create acronym from first letter of each word
     */
    private fun formatLocation(location: String): String {
        val cleanLocation = location.trim()
        
        if (cleanLocation.length <= 15) {
            return cleanLocation
        }
        
        // Create acronym from first letter of each word
        val words = cleanLocation.split(Regex("\\s+"))
        val acronym = words
            .filter { it.isNotBlank() }
            .map { word ->
                // Take first letter of each word, ignore common words
                val firstChar = word.first().uppercaseChar()
                if (word.length > 2 || !isCommonWord(word)) firstChar else null
            }
            .filterNotNull()
            .take(6) // Limit acronym length
            .joinToString("")
            
        return if (acronym.length >= 2) {
            "$acronym..."
        } else {
            // Fallback: first 15 characters with ellipsis
            "${cleanLocation.take(12)}..."
        }
    }
    
    /**
     * Check if a word is common and should be excluded from acronyms
     */
    private fun isCommonWord(word: String): Boolean {
        val commonWords = setOf("the", "and", "of", "at", "in", "on", "to", "a", "an", "for")
        return commonWords.contains(word.lowercase())
    }
    
    /**
     * Generate a preview title for the dialog
     */
    fun generatePreviewTitle(
        selectedWord: String,
        sampleLocation: String = "General Hospital, London",
        sampleTime: String = "2:30 PM"
    ): String {
        return formatAppointmentTitle(selectedWord, sampleLocation, sampleTime)
    }
    
    private fun getPreferences(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }
}