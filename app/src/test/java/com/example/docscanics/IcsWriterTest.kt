package com.example.docscanics

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class IcsWriterTest {
    @Test
    fun buildsBasicEvent() {
        val start = ZonedDateTime.now(ZoneId.of("UTC")).withSecond(0).withNano(0)
        val end = start.plusHours(1)
        val ics =
            IcsWriter.buildEvent(
                summary = "Checkup",
                start = start,
                end = end,
                location = "Clinic Room 3",
                description = "Annual physical",
            )

        // Contains expected sections and CRLF line endings
        assertTrue(ics.startsWith("BEGIN:VCALENDAR\r\n"))
        assertTrue(ics.contains("BEGIN:VEVENT\r\n"))
        assertTrue(ics.contains("SUMMARY:Checkup"))
        assertTrue(ics.contains("LOCATION:Clinic Room 3"))
        assertTrue(ics.contains("DESCRIPTION:Annual physical"))
        assertTrue(ics.contains("END:VEVENT\r\n"))
        assertTrue(ics.trimEnd().endsWith("END:VCALENDAR"))
        // Ensure CRLFs are present (not just LF)
        assertTrue("Has CRLF line endings", ics.contains("\r\n"))
        // Should not contain bare \n without \r (heuristic)
        assertFalse("Unexpected bare LF detected", ics.contains("[^\r]\n".toRegex()))
        assertNotNull(ics)
    }
}
