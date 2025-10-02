package com.example.docscanics

import java.time.LocalDate
import java.time.LocalTime
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.regex.Pattern

data class ParsedAppointment(
    val summary: String,
    val startDateTime: ZonedDateTime?,
    val endDateTime: ZonedDateTime?,
    val location: String?,
    val description: String?,
)

object AppointmentParser {
    // Common date patterns
    private val datePatterns =
        listOf(
            "\\b(\\d{1,2})[/\\-](\\d{1,2})[/\\-](\\d{2,4})\\b", // MM/dd/yyyy, MM-dd-yyyy
            "\\b(\\d{1,2})\\s+(Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec)\\w*\\s+(\\d{2,4})\\b", // dd Month yyyy
            "\\b(Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec)\\w*\\s+(\\d{1,2}),?\\s+(\\d{2,4})\\b", // Month dd, yyyy
            "\\b(\\d{2,4})[/\\-](\\d{1,2})[/\\-](\\d{1,2})\\b", // yyyy/MM/dd, yyyy-MM-dd
        )

    // Common time patterns
    private val timePatterns =
        listOf(
            "\\b(\\d{1,2}):(\\d{2})\\s*(am|pm|AM|PM)\\b", // 3:30 PM
            "\\b(\\d{1,2})\\s*(am|pm|AM|PM)\\b", // 3 PM
            "\\b(\\d{1,2}):(\\d{2})\\b", // 15:30 (24-hour)
        )

    // Location indicators (fallback)
    private val locationKeywords =
        listOf(
            "at",
            "location",
            "room",
            "office",
            "building",
            "address",
            "suite",
            "floor",
        )

    // UK postcode regex (approximate, case-insensitive). Allows optional space before final 3.
    private val ukPostcodeRegex: Regex =
        Regex(
            "\\b([A-Z]{1,2}\\d{1,2}[A-Z]?)\\s?(\\d[A-Z]{2})\\b",
            RegexOption.IGNORE_CASE,
        )

    // Appointment type keywords (fallback summary derivation)
    private val appointmentKeywords =
        listOf(
            "appointment",
            "meeting",
            "visit",
            "consultation",
            "session",
            "call",
            "conference",
        )

    fun parseAppointment(
        text: String,
        referenceTime: ZonedDateTime = ZonedDateTime.now(),
    ): ParsedAppointment {
        val cleanText = text.trim()

        // Extract date and time first
        val (startDateTime, endDateTime) = extractDateTime(cleanText, referenceTime)

        // Extract location using UK postcode heuristic
        val locationParts = extractUkLocation(cleanText)
        val location = locationParts?.fullLocation ?: extractLocationFallback(cleanText)

        // Build summary: acronym of the first part of location + start time
        val summary = buildSummaryFromLocation(locationParts, startDateTime) ?: extractSummary(cleanText)

        // Use original text as description
        val description = "Extracted from OCR: $cleanText"

        return ParsedAppointment(
            summary = summary,
            startDateTime = startDateTime,
            endDateTime = endDateTime,
            location = location,
            description = description,
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

    private fun extractDateTime(
        text: String,
        referenceTime: ZonedDateTime,
    ): Pair<ZonedDateTime?, ZonedDateTime?> {
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
        val startDateTime =
            when {
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
        // Clean the input string and strip leading weekday names if present
        var cleanStr = dateStr.replace(",", "").trim()
        val weekdayRegex =
            Regex("^(Mon|Tue|Wed|Thu|Fri|Sat|Sun|Monday|Tuesday|Wednesday|Thursday|Friday|Saturday|Sunday)\\s+", RegexOption.IGNORE_CASE)
        cleanStr = cleanStr.replace(weekdayRegex, "").trim()

        val formatters =
            listOf(
                DateTimeFormatter.ofPattern("M/d/yyyy", java.util.Locale.ENGLISH),
                DateTimeFormatter.ofPattern("MM/dd/yyyy", java.util.Locale.ENGLISH),
                DateTimeFormatter.ofPattern("M-d-yyyy", java.util.Locale.ENGLISH),
                DateTimeFormatter.ofPattern("MM-dd-yyyy", java.util.Locale.ENGLISH),
                DateTimeFormatter.ofPattern("d MMM yyyy", java.util.Locale.ENGLISH),
                DateTimeFormatter.ofPattern("d MMMM yyyy", java.util.Locale.ENGLISH),
                DateTimeFormatter.ofPattern("MMM d yyyy", java.util.Locale.ENGLISH),
                DateTimeFormatter.ofPattern("MMM d, yyyy", java.util.Locale.ENGLISH),
                DateTimeFormatter.ofPattern("MMMM d yyyy", java.util.Locale.ENGLISH),
                DateTimeFormatter.ofPattern("MMMM d, yyyy", java.util.Locale.ENGLISH),
                DateTimeFormatter.ofPattern("yyyy/MM/dd", java.util.Locale.ENGLISH),
                DateTimeFormatter.ofPattern("yyyy-MM-dd", java.util.Locale.ENGLISH),
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

                val adjustedHour =
                    when {
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

    private data class LocationParts(val namePart: String, val postcode: String, val fullLocation: String)

    private fun extractUkLocation(text: String): LocationParts? {
        val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
        for (i in lines.indices) {
            val line = lines[i]
            val m = ukPostcodeRegex.find(line)
            if (m != null) {
                val postcode = (m.groupValues[1] + " " + m.groupValues[2]).uppercase()
                val before = line.substring(0, m.range.first).trim()
                val namePart = if (before.isNotEmpty()) before else lines.getOrNull(i - 1)?.trim().orEmpty()
                val fullLoc =
                    buildString {
                        if (namePart.isNotEmpty()) append(namePart).append(" ")
                        append(postcode)
                    }.trim()
                if (fullLoc.isNotBlank()) return LocationParts(namePart, postcode, fullLoc)
            }
        }
        return null
    }

    private fun extractLocationFallback(text: String): String? {
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

    private fun buildSummaryFromLocation(
        locationParts: LocationParts?,
        start: ZonedDateTime?,
    ): String? {
        if (locationParts == null || locationParts.namePart.isBlank()) return null
        val acronym =
            locationParts.namePart
                .split(" ", "-", "/", ",", ".")
                .filter { it.isNotBlank() }
                .map { it.trim() }
                .map { it.firstOrNull()?.uppercaseChar() ?: ' ' }
                .joinToString(separator = "")
                .trim()
        if (acronym.isBlank()) return null
        val timePart = start?.toLocalTime()?.let { String.format("%02d:%02d", it.hour, it.minute) }
        return if (timePart != null) "$acronym $timePart" else acronym
    }
}
