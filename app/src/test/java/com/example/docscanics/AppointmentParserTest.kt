package com.example.docscanics

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

class AppointmentParserTest {
    private val testReferenceTime = ZonedDateTime.of(2024, 1, 15, 10, 0, 0, 0, ZoneId.systemDefault())

    @Test
    fun parsesBasicAppointmentWithDateTime() {
        val text = "Doctor appointment on March 15, 2024 at 2:30 PM"
        val result = AppointmentParser.parseAppointment(text, testReferenceTime)

        assertEquals("Doctor appointment on March 15, 2024 at 2:30 PM", result.summary)
        assertNotNull(result.startDateTime)
        assertEquals(LocalDate.of(2024, 3, 15), result.startDateTime!!.toLocalDate())
        assertEquals(LocalTime.of(14, 30), result.startDateTime!!.toLocalTime())
        assertNotNull(result.endDateTime)
        assertEquals(LocalTime.of(15, 30), result.endDateTime!!.toLocalTime()) // 1 hour later
    }

    @Test
    fun parsesAppointmentWithLocation() {
        val text = "Meeting at Room 205 on 12/20/2024 at 9 AM"
        val result = AppointmentParser.parseAppointment(text, testReferenceTime)

        assertEquals("Meeting at Room 205 on 12/20/2024 at 9 AM", result.summary)
        assertEquals("Room 205 on 12/20/2024 at 9 AM", result.location)
        assertNotNull(result.startDateTime)
        assertEquals(LocalDate.of(2024, 12, 20), result.startDateTime!!.toLocalDate())
        assertEquals(LocalTime.of(9, 0), result.startDateTime!!.toLocalTime())
    }

    @Test
    fun parsesDateOnly() {
        val text = "Consultation on January 25, 2024"
        val result = AppointmentParser.parseAppointment(text, testReferenceTime)

        assertEquals("Consultation on January 25, 2024", result.summary)
        assertNotNull(result.startDateTime)
        assertEquals(LocalDate.of(2024, 1, 25), result.startDateTime!!.toLocalDate())
        assertEquals(LocalTime.of(9, 0), result.startDateTime!!.toLocalTime()) // Default 9 AM
    }

    @Test
    fun parsesTimeOnly() {
        val text = "Appointment at 3:45 PM"
        val result = AppointmentParser.parseAppointment(text, testReferenceTime)

        assertEquals("Appointment at 3:45 PM", result.summary)
        assertNotNull(result.startDateTime)
        assertEquals(testReferenceTime.toLocalDate().plusDays(1), result.startDateTime!!.toLocalDate()) // Tomorrow
        assertEquals(LocalTime.of(15, 45), result.startDateTime!!.toLocalTime())
    }

    @Test
    fun parsesDifferentDateFormats() {
        val testCases =
            mapOf(
                "Meeting on 03/15/2024" to LocalDate.of(2024, 3, 15),
                "Visit on 2024-12-25" to LocalDate.of(2024, 12, 25),
                "Call on Dec 31, 2024" to LocalDate.of(2024, 12, 31),
                "Session on 15 Jan 2025" to LocalDate.of(2025, 1, 15),
            )

        for ((text, expectedDate) in testCases) {
            val result = AppointmentParser.parseAppointment(text, testReferenceTime)
            assertNotNull("Failed to parse date from: $text", result.startDateTime)
            assertEquals("Wrong date parsed from: $text", expectedDate, result.startDateTime!!.toLocalDate())
        }
    }

    @Test
    fun parsesDifferentTimeFormats() {
        val testCases =
            mapOf(
                "Meeting at 2:30 PM" to LocalTime.of(14, 30),
                "Visit at 9 AM" to LocalTime.of(9, 0),
                "Call at 15:45" to LocalTime.of(15, 45),
                "Session at 8:00 am" to LocalTime.of(8, 0),
                "Appointment at 12:00 PM" to LocalTime.of(12, 0),
            )

        for ((text, expectedTime) in testCases) {
            val result = AppointmentParser.parseAppointment(text, testReferenceTime)
            assertNotNull("Failed to parse time from: $text", result.startDateTime)
            assertEquals("Wrong time parsed from: $text", expectedTime, result.startDateTime!!.toLocalTime())
        }
    }

    @Test
    fun extractsLocationFromKeywords() {
        val testCases =
            mapOf(
                "Meeting at Building A" to "Building A",
                "Visit to Room 123" to "123", // "room" is a location keyword, should extract "123"
                "Visit to nowhere" to null, // "to" is not a location keyword, no other location words
                "Appointment at office 5B" to "office 5B",
                "Session in room 301" to "301",
                "Call from location Downtown" to "Downtown",
            )

        for ((text, expectedLocation) in testCases) {
            val result = AppointmentParser.parseAppointment(text, testReferenceTime)
            assertEquals("Wrong location parsed from: $text", expectedLocation, result.location)
        }
    }

    @Test
    fun handlesMultilineText() {
        val text =
            """
            Doctor Appointment
            March 20, 2024
            3:00 PM
            at Medical Center
            Room 305
            """.trimIndent()

        val result = AppointmentParser.parseAppointment(text, testReferenceTime)

        assertEquals("Doctor Appointment", result.summary) // First line with keyword
        assertEquals("Medical Center", result.location) // First location keyword match
        assertNotNull(result.startDateTime)
        assertEquals(LocalDate.of(2024, 3, 20), result.startDateTime!!.toLocalDate())
        assertEquals(LocalTime.of(15, 0), result.startDateTime!!.toLocalTime())
    }

    @Test
    fun handlesMidnightAndNoonEdgeCases() {
        val testCases =
            mapOf(
                "Meeting at 12:00 AM" to LocalTime.of(0, 0), // Midnight
                "Visit at 12:00 PM" to LocalTime.of(12, 0), // Noon
                "Call at 12:30 AM" to LocalTime.of(0, 30),
                "Session at 12:30 PM" to LocalTime.of(12, 30),
            )

        for ((text, expectedTime) in testCases) {
            val result = AppointmentParser.parseAppointment(text, testReferenceTime)
            assertNotNull("Failed to parse time from: $text", result.startDateTime)
            assertEquals("Wrong time parsed from: $text", expectedTime, result.startDateTime!!.toLocalTime())
        }
    }

    @Test
    fun fallsBackToDefaultsWhenNothingFound() {
        val text = "Some random text with no appointment info"
        val result = AppointmentParser.parseAppointment(text, testReferenceTime)

        assertEquals("Some random text with no appointment info", result.summary)
        assertNull(result.startDateTime) // No date/time found
        assertNull(result.endDateTime)
        assertNull(result.location)
        assertNotNull(result.description)
        assertTrue(result.description!!.contains("Some random text with no appointment info"))
    }

    @Test
    fun extractsAppointmentKeywords() {
        val testCases =
            listOf(
                "Medical consultation tomorrow",
                "Dentist visit scheduled",
                "Business meeting next week",
                "Therapy session at 3pm",
                "Conference call with team",
            )

        for (text in testCases) {
            val result = AppointmentParser.parseAppointment(text, testReferenceTime)
            assertEquals("Should extract line with appointment keyword", text, result.summary)
        }
    }
}
