package com.example.docscanics

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View

class SelectionOverlayView
    @JvmOverloads
    constructor(
        context: Context,
        attrs: AttributeSet? = null,
        defStyleAttr: Int = 0,
    ) : View(context, attrs, defStyleAttr) {
        private val borderPaint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#4CAF50") // Material green
                style = Paint.Style.STROKE
                strokeWidth = 8f
            }
        private val fillPaint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.argb(40, 76, 175, 80) // Semi-transparent green
                style = Paint.Style.FILL
            }
        private val cornerPaint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#4CAF50")
                style = Paint.Style.FILL
            }
        private val instructionPaint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                textSize = 48f
                textAlign = Paint.Align.CENTER
                setShadowLayer(4f, 2f, 2f, Color.BLACK)
            }

        private var startX = 0f
        private var startY = 0f
        private var currentX = 0f
        private var currentY = 0f
        private var hasSelection = false
        private var isDragging = false
        
        companion object {
            private const val MIN_SELECTION_SIZE = 50f
            private const val CORNER_SIZE = 20f
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startX = event.x
                    startY = event.y
                    currentX = startX
                    currentY = startY
                    hasSelection = true
                    isDragging = true
                    invalidate()
                }
                MotionEvent.ACTION_MOVE -> {
                    if (isDragging) {
                        currentX = event.x
                        currentY = event.y
                        invalidate()
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    currentX = event.x
                    currentY = event.y
                    isDragging = false
                    
                    // Check if selection is too small
                    val width = kotlin.math.abs(currentX - startX)
                    val height = kotlin.math.abs(currentY - startY)
                    if (width < MIN_SELECTION_SIZE || height < MIN_SELECTION_SIZE) {
                        hasSelection = false // Clear selection if too small
                    }
                    
                    invalidate()
                }
            }
            return true
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            
            if (!hasSelection && !isDragging) {
                // Show instruction text when no selection is active
                val centerX = width / 2f
                val centerY = height / 2f
                canvas.drawText(
                    "Drag to select text region",
                    centerX,
                    centerY - 20f,
                    instructionPaint
                )
                canvas.drawText(
                    "Tap 'Select' button to start",
                    centerX,
                    centerY + 40f,
                    instructionPaint
                )
            } else if (hasSelection) {
                val rect = RectF(
                    minOf(startX, currentX),
                    minOf(startY, currentY),
                    maxOf(startX, currentX),
                    maxOf(startY, currentY),
                )
                
                // Draw the selection area
                canvas.drawRect(rect, fillPaint)
                canvas.drawRect(rect, borderPaint)
                
                // Draw corner handles for better visual feedback
                drawCornerHandles(canvas, rect)
                
                // Show selection dimensions (if dragging or selection is reasonably sized)
                if (isDragging || (rect.width() > MIN_SELECTION_SIZE && rect.height() > MIN_SELECTION_SIZE)) {
                    val dimensionText = "${rect.width().toInt()} x ${rect.height().toInt()}"
                    val textX = rect.centerX()
                    val textY = rect.top - 20f
                    canvas.drawText(dimensionText, textX, textY, instructionPaint)
                }
            }
        }
        
        private fun drawCornerHandles(canvas: Canvas, rect: RectF) {
            val cornerSize = CORNER_SIZE
            
            // Top-left corner
            canvas.drawRect(
                rect.left - cornerSize/2,
                rect.top - cornerSize/2,
                rect.left + cornerSize/2,
                rect.top + cornerSize/2,
                cornerPaint
            )
            
            // Top-right corner
            canvas.drawRect(
                rect.right - cornerSize/2,
                rect.top - cornerSize/2,
                rect.right + cornerSize/2,
                rect.top + cornerSize/2,
                cornerPaint
            )
            
            // Bottom-left corner
            canvas.drawRect(
                rect.left - cornerSize/2,
                rect.bottom - cornerSize/2,
                rect.left + cornerSize/2,
                rect.bottom + cornerSize/2,
                cornerPaint
            )
            
            // Bottom-right corner
            canvas.drawRect(
                rect.right - cornerSize/2,
                rect.bottom - cornerSize/2,
                rect.right + cornerSize/2,
                rect.bottom + cornerSize/2,
                cornerPaint
            )
        }

        fun reset() {
            hasSelection = false
            isDragging = false
            invalidate()
        }

        fun getSelection(): RectF? {
            if (!hasSelection) return null
            
            val rect = RectF(
                minOf(startX, currentX),
                minOf(startY, currentY),
                maxOf(startX, currentX),
                maxOf(startY, currentY),
            )
            
            // Only return valid selections that meet minimum size requirements
            return if (rect.width() >= MIN_SELECTION_SIZE && rect.height() >= MIN_SELECTION_SIZE) {
                rect
            } else {
                null
            }
        }
        
        fun hasValidSelection(): Boolean {
            return getSelection() != null
        }
    }
