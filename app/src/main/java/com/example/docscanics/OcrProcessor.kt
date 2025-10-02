package com.example.docscanics

import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.tasks.await

object OcrProcessor {
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    suspend fun recognize(bitmap: Bitmap): TextResult {
        try {
            val input = InputImage.fromBitmap(bitmap, 0)
            val result = recognizer.process(input).await()

            // Extract and order text properly
            val orderedText = getOrderedText(result)

            return TextResult(orderedText)
        } catch (e: Exception) {
            throw Exception("OCR processing failed: ${e.message}", e)
        }
    }

    /**
     * Extracts and orders text blocks in proper reading order
     */
    private fun getOrderedText(text: Text): String {
        if (text.textBlocks.isEmpty()) return ""

        // Extract all text elements with their positions
        val textElements = mutableListOf<TextElement>()

        for (block in text.textBlocks) {
            for (line in block.lines) {
                val boundingBox = line.boundingBox
                if (boundingBox != null) {
                    textElements.add(
                        TextElement(
                            text = line.text,
                            top = boundingBox.top,
                            left = boundingBox.left,
                            bottom = boundingBox.bottom,
                            right = boundingBox.right,
                        ),
                    )
                }
            }
        }

        // Group elements into rows based on vertical overlap
        val rows = groupIntoRows(textElements)

        // Build the final text with proper line breaks and spacing
        return rows.joinToString("\n") { row ->
            buildRowText(row.sortedBy { it.left })
        }
    }

    /**
     * Builds text for a row with proper spacing between columns
     */
    private fun buildRowText(rowElements: List<TextElement>): String {
        if (rowElements.isEmpty()) return ""
        if (rowElements.size == 1) return rowElements.first().text
        
        val result = StringBuilder()
        
        for (i in rowElements.indices) {
            result.append(rowElements[i].text)
            
            // Add spacing between elements if there's a significant gap
            if (i < rowElements.size - 1) {
                val currentRight = rowElements[i].right
                val nextLeft = rowElements[i + 1].left
                val gap = nextLeft - currentRight
                
                // If there's a significant gap, add extra spacing to preserve column structure
                when {
                    gap > 100 -> result.append("    ") // Large gap - likely different columns
                    gap > 50 -> result.append("  ") // Medium gap
                    else -> result.append(" ") // Small gap - normal word spacing
                }
            }
        }
        
        return result.toString()
    }
    
    /**
     * Groups text elements into rows based on vertical position overlap
     */
    private fun groupIntoRows(elements: List<TextElement>): List<List<TextElement>> {
        if (elements.isEmpty()) return emptyList()

        val rows = mutableListOf<MutableList<TextElement>>()

        for (element in elements.sortedBy { it.top }) {
            var addedToRow = false

            // Try to add to existing row if there's vertical overlap
            for (row in rows) {
                if (row.any { hasVerticalOverlap(it, element) }) {
                    row.add(element)
                    addedToRow = true
                    break
                }
            }

            // If no overlap found, create a new row
            if (!addedToRow) {
                rows.add(mutableListOf(element))
            }
        }

        return rows
    }

    /**
     * Checks if two text elements have vertical overlap (are on the same "row")
     */
    private fun hasVerticalOverlap(
        element1: TextElement,
        element2: TextElement,
    ): Boolean {
        // Dynamic tolerance based on text height
        val avgHeight = ((element1.bottom - element1.top) + (element2.bottom - element2.top)) / 2
        val tolerance = maxOf(avgHeight * 0.3f, 20f).toInt() // 30% of average height, min 20px

        return !(element1.bottom + tolerance < element2.top || element2.bottom + tolerance < element1.top)
    }

    /**
     * Data class to hold text element with its position
     */
    private data class TextElement(
        val text: String,
        val top: Int,
        val left: Int,
        val bottom: Int,
        val right: Int,
    )

    /**
     * Simple wrapper class for OCR results
     */
    data class TextResult(
        val text: String,
    )
}
