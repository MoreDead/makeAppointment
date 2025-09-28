package com.example.docscanics

import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.regex.Pattern

data class ParsedAppointment(
    val summary: String,
    val startDateTime: ZonedDateTime?,
    val endDateTime: ZonedDateTime?,
    val location: String?,
    val description: String?
)

object AppointmentParser {
    
    // Common date patterns
    private val datePatterns = listOf(
        "\\b(\\d{1,2})[/\\-](\\d{1,2})[/\\-](\\d{2,4})\\b", // MM/dd/yyyy, MM-dd-yyyy
        "\\b(\\d{1,2})\\s+(Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec)\\w*\\s+(\\d{2,4})\\b", // dd Month yyyy
        "\\b(Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec)\\w*\\s+(\\d{1,2}),?\\s+(\\d{2,4})\\b", // Month dd, yyyy
        "\\b(\\d{2,4})[/\\-](\\d{1,2})[/\\-](\\d{1,2})\\b" // yyyy/MM/dd, yyyy-MM-dd
    )
    
    // Common time patterns
    private val timePatterns = listOf(
        "\\b(\\d{1,2}):(\\d{2})\\s*(am|pm|AM|PM)\\b", // 3:30 PM
        "\\b(\\d{1,2})\\s*(am|pm|AM|PM)\\b", // 3 PM
        "\\b(\\d{1,2}):(\\d{2})\\b" // 15:30 (24-hour)
    )
    
    // Location indicators
    private val locationKeywords = listOf(
        "at", "location", "room", "office", "building", "address", "suite", "floor"
    )
    
    // Appointment type keywords
    private val appointmentKeywords = listOf(
        "appointment", "meeting", "visit", "consultation", "session", "call", "conference"
    )

    fun parseAppointment(text: String, referenceTime: ZonedDateTime = ZonedDateTime.now()): ParsedAppointment {
        val cleanText = text.trim()
        
        // Extract summary (try to find appointment type or use first meaningful line)
        val summary = extractSummary(cleanText)
        
        // Extract date and time
        val dateTime = extractDateTime(cleanText, referenceTime)
        val startDateTime = dateTime.first
        val endDateTime = dateTime.second
        
        // Extract location
        val location = extractLocation(cleanText)
        
        // Use original text as description
        val description = "Extracted from OCR: $cleanText"
        
        return ParsedAppointment(
            summary = summary,
            startDateTime = startDateTime,
            endDateTime = endDateTime,
            location = location,
            description = description
        )
    }
    
    private fun extractSummary(text: String): String {
        val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
        
        // Look for lines containing appointment keywords
        for (line in lines) {
            for (keyword in appointmentKeywords) {
                if (line.lowercase().contains(keyword)) {
                    return line.take(100) // Limit length
                }
            }
        }
        
        // If no appointment keywords found, use the first non-empty line
        return lines.firstOrNull()?.take(100) ?: "Appointment"
    }
    
    private fun extractDateTime(text: String, referenceTime: ZonedDateTime): Pair<ZonedDateTime?, ZonedDateTime?> {
        var foundDate: LocalDate? = null
        var foundTime: LocalTime? = null
        
        // Try to extract date
        for (datePattern in datePatterns) {
            val pattern = Pattern.compile(datePattern, Pattern.CASE_INSENSITIVE)
            val matcher = pattern.matcher(text)
            if (matcher.find()) {
                foundDate = tryParseDate(matcher.group())
                if (foundDate != null) break
            }
        }
        
        // Try to extract time
        for (timePattern in timePatterns) {
            val pattern = Pattern.compile(timePattern, Pattern.CASE_INSENSITIVE)
            val matcher = pattern.matcher(text)
            if (matcher.find()) {
                foundTime = tryParseTime(matcher.group())
                if (foundTime != null) break
            }
        }
        
        // Combine date and time
        val startDateTime = when {
            foundDate != null && foundTime != null -> {
                ZonedDateTime.of(foundDate, foundTime, referenceTime.zone)
            }
            foundDate != null -> {
                // Default to 9 AM if only date is found
                ZonedDateTime.of(foundDate, LocalTime.of(9, 0), referenceTime.zone)
            }
            foundTime != null -> {
                // Use tomorrow's date if only time is found
                ZonedDateTime.of(referenceTime.toLocalDate().plusDays(1), foundTime, referenceTime.zone)
            }
            else -> null
        }
        
        // End time is typically 1 hour after start time
        val endDateTime = startDateTime?.plusHours(1)
        
        return Pair(startDateTime, endDateTime)
    }
    
    private fun tryParseDate(dateStr: String): LocalDate? {
        // Clean the input string
        val cleanStr = dateStr.replace(",", "").trim()
        
        val formatters = listOf(
            DateTimeFormatter.ofPattern("M/d/yyyy"),
            DateTimeFormatter.ofPattern("MM/dd/yyyy"),
            DateTimeFormatter.ofPattern("M-d-yyyy"),
            DateTimeFormatter.ofPattern("MM-dd-yyyy"),
            DateTimeFormatter.ofPattern("d MMM yyyy"),
            DateTimeFormatter.ofPattern("MMM d yyyy"),
            DateTimeFormatter.ofPattern("MMM d, yyyy"),
            DateTimeFormatter.ofPattern("MMMM d yyyy"),
            DateTimeFormatter.ofPattern("MMMM d, yyyy"),
            DateTimeFormatter.ofPattern("yyyy/MM/dd"),
            DateTimeFormatter.ofPattern("yyyy-MM-dd")
        )
        
        for (formatter in formatters) {
            try {
                return LocalDate.parse(cleanStr, formatter)
            } catch (e: DateTimeParseException) {
                // Try next formatter
            }
        }
        return null
    }
    
    private fun tryParseTime(timeStr: String): LocalTime? {
        try {
            // Handle AM/PM format
            val amPmPattern = Pattern.compile("(\\d{1,2})(?::(\\d{2}))?\\s*(am|pm)", Pattern.CASE_INSENSITIVE)
            val amPmMatcher = amPmPattern.matcher(timeStr)
            if (amPmMatcher.find()) {
                val hour = amPmMatcher.group(1)?.toInt() ?: return null
                val minute = amPmMatcher.group(2)?.toInt() ?: 0
                val amPm = amPmMatcher.group(3)?.lowercase() ?: return null
                
                val adjustedHour = when {
                    amPm == "am" && hour == 12 -> 0
                    amPm == "pm" && hour != 12 -> hour + 12
                    else -> hour
                }
                
                return LocalTime.of(adjustedHour, minute)
            }
            
            // Handle 24-hour format
            val time24Pattern = Pattern.compile("(\\d{1,2}):(\\d{2})")
            val time24Matcher = time24Pattern.matcher(timeStr)
            if (time24Matcher.find()) {
                val hour = time24Matcher.group(1)?.toInt() ?: return null
                val minute = time24Matcher.group(2)?.toInt() ?: return null
                return LocalTime.of(hour, minute)
            }
        } catch (e: Exception) {
            // Invalid time format
        }
        return null
    }
    
    private fun extractLocation(text: String): String? {
        val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
        
        for (line in lines) {
            for (keyword in locationKeywords) {
                val pattern = "\\b${java.util.regex.Pattern.quote(keyword)}\\b"
                val regex = java.util.regex.Pattern.compile(pattern, java.util.regex.Pattern.CASE_INSENSITIVE)
                val matcher = regex.matcher(line)
                
                if (matcher.find()) {
                    // Extract the part after the location keyword
                    val afterKeyword = line.substring(matcher.end()).trim()
                    if (afterKeyword.isNotEmpty()) {
                        return afterKeyword.take(100) // Limit length
                    }
                }
            }
        }
        
        return null
    }
}