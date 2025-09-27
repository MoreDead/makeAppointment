package com.example.docscanics

import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID

object IcsWriter {
    private val dtFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")

    fun buildEvent(
        summary: String,
        start: ZonedDateTime,
        end: ZonedDateTime,
        location: String? = null,
        description: String? = null
    ): String {
        val uid = UUID.randomUUID().toString()
        val dtStamp = ZonedDateTime.now(ZoneId.of("UTC")).format(dtFormatter)
        val dtStart = start.withZoneSameInstant(ZoneId.of("UTC")).format(dtFormatter)
        val dtEnd = end.withZoneSameInstant(ZoneId.of("UTC")).format(dtFormatter)

        val lines = buildList {
            add("BEGIN:VCALENDAR")
            add("VERSION:2.0")
            add("PRODID:-//DocScanICS//EN")
            add("CALSCALE:GREGORIAN")
            add("METHOD:PUBLISH")
            add("BEGIN:VEVENT")
            add("UID:$uid")
            add("DTSTAMP:$dtStamp")
            add("DTSTART:$dtStart")
            add("DTEND:$dtEnd")
            add("SUMMARY:${escape(summary)}")
            if (!location.isNullOrBlank()) add("LOCATION:${escape(location)}")
            if (!description.isNullOrBlank()) add("DESCRIPTION:${escape(description)}")
            add("END:VEVENT")
            add("END:VCALENDAR")
        }
        // ICS requires CRLF line endings
        return lines.joinToString(separator = "\r\n", postfix = "\r\n")
    }

    private fun escape(input: String): String =
        input
            .replace("\\", "\\\\")
            .replace(";", "\\;")
            .replace(",", "\\,")
            .replace("\n", "\\n")
}
