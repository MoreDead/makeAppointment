package com.example.docscanics

import android.media.Image
import androidx.camera.core.ImageProxy
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.tasks.await

object OcrProcessor {
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    suspend fun recognize(imageProxy: ImageProxy): Text {
        try {
            val mediaImage: Image = imageProxy.image 
                ?: throw IllegalStateException("ImageProxy does not contain a valid image")
            
            if (mediaImage.width <= 0 || mediaImage.height <= 0) {
                throw IllegalStateException("Image has invalid dimensions: ${mediaImage.width}x${mediaImage.height}")
            }
            
            val rotation = imageProxy.imageInfo.rotationDegrees
            val input = InputImage.fromMediaImage(mediaImage, rotation)
            
            val result = recognizer.process(input).await()
            
            // Validate that we got a result
            if (result.text.isBlank()) {
                // This is not an error, just no text found
                return result
            }
            
            return result
        } catch (e: Exception) {
            throw Exception("OCR processing failed: ${e.message}", e)
        }
    }
}
