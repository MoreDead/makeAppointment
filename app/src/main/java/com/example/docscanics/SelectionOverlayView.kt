package com.example.docscanics

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.util.Log
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs

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
        private var isProcessing = false

        // Handle state tracking
        private var activeHandle = HandlePosition.NONE
        private var isDraggingBox = false
        private var lastTouchX = 0f
        private var lastTouchY = 0f

        // Selection rect cached for performance
        private val selectionRect = RectF()

    companion object {
        private const val MIN_SELECTION_SIZE = 50f
        private const val CORNER_SIZE = 40f // Larger for better touch targeting
        private const val TOUCH_SLOP = 40f // Larger threshold for handle touch detection
        private const val TAG = "SelectionOverlay"
    }

        // Enum to track which handle is active during resize operations
        private enum class HandlePosition {
            NONE,
            TOP_LEFT,
            TOP_RIGHT,
            BOTTOM_LEFT,
            BOTTOM_RIGHT,
            INSIDE,
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            // If we're near button area (bottom of screen), don't handle touch
            if (event.y > height * 0.85f) {
                Log.d(TAG, "Ignoring touch near button area at y=${event.y}, height=$height")
                return false // Don't consume the touch event - let buttons handle it
            }

            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    lastTouchX = event.x
                    lastTouchY = event.y

                    if (hasSelection && getSelection() != null) {
                        // Check if touching a handle or inside the selection
                        updateSelectionRect()
                        activeHandle = getHandleAtPosition(lastTouchX, lastTouchY)

                        if (activeHandle != HandlePosition.NONE) {
                            // Started touching a handle or inside the box
                            isDragging = true
                            isDraggingBox = (activeHandle == HandlePosition.INSIDE)
                            Log.d(TAG, "Starting ${if (isDraggingBox) "box drag" else "resize"} operation with handle: $activeHandle")
                            invalidate() // Show immediate visual feedback
                            return true
                        } else {
                            Log.d(TAG, "Touch outside handles, will start new selection")
                        }
                    }

                    // Start a new selection
                    startX = event.x
                    startY = event.y
                    currentX = startX
                    currentY = startY
                    hasSelection = true
                    isDragging = true
                    activeHandle = HandlePosition.NONE
                    isDraggingBox = false
                    Log.d("SelectionOverlay", "ACTION_DOWN - starting selection at ($startX, $startY)")
                }

                MotionEvent.ACTION_MOVE -> {
                    if (!isDragging) return true

                    val deltaX = event.x - lastTouchX
                    val deltaY = event.y - lastTouchY
                    lastTouchX = event.x
                    lastTouchY = event.y

                    if (isDraggingBox) {
                        // Move the entire selection box
                        startX += deltaX
                        startY += deltaY
                        currentX += deltaX
                        currentY += deltaY
                    } else if (activeHandle != HandlePosition.NONE) {
                        // Resize by moving specific handle
                        when (activeHandle) {
                            HandlePosition.TOP_LEFT -> {
                                startX += deltaX
                                startY += deltaY
                            }
                            HandlePosition.TOP_RIGHT -> {
                                currentX += deltaX
                                startY += deltaY
                            }
                            HandlePosition.BOTTOM_LEFT -> {
                                startX += deltaX
                                currentY += deltaY
                            }
                            HandlePosition.BOTTOM_RIGHT -> {
                                currentX += deltaX
                                currentY += deltaY
                            }
                            else -> { /* Should not happen */ }
                        }
                    } else {
                        // Normal dragging (creating a new selection)
                        currentX = event.x
                        currentY = event.y
                    }

                    // Ensure we stay within view bounds
                    startX = startX.coerceIn(0f, width.toFloat())
                    startY = startY.coerceIn(0f, height.toFloat())
                    currentX = currentX.coerceIn(0f, width.toFloat())
                    currentY = currentY.coerceIn(0f, height.toFloat())

                    invalidate()
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (!isDragging) {
                        Log.d("SelectionOverlay", "ACTION_UP - not dragging, ignoring")
                        return true
                    }

                    isDragging = false
                    isDraggingBox = false
                    activeHandle = HandlePosition.NONE

                    // Check if selection is too small
                    val width = abs(currentX - startX)
                    val height = abs(currentY - startY)
                    Log.d("SelectionOverlay", "ACTION_UP - selection size: ${width}x$height (min: $MIN_SELECTION_SIZE)")
                    if (width < MIN_SELECTION_SIZE || height < MIN_SELECTION_SIZE) {
                        hasSelection = false // Clear selection if too small
                        Log.d("SelectionOverlay", "ACTION_UP - selection too small, clearing hasSelection")
                    } else {
                        Log.d("SelectionOverlay", "ACTION_UP - valid selection maintained")
                    }

                    invalidate()
                }
            }
            return true
        }

        /**
         * Updates the cached selection rectangle for faster access
         */
        private fun updateSelectionRect() {
            selectionRect.set(
                minOf(startX, currentX),
                minOf(startY, currentY),
                maxOf(startX, currentX),
                maxOf(startY, currentY),
            )
        }

        /**
         * Determines which handle (if any) is at the given position
         */
        private fun getHandleAtPosition(
            x: Float,
            y: Float,
        ): HandlePosition {
            updateSelectionRect()

            // Check if touch is on any of the corner handles first (priority over inside)
            val handleHitArea = CORNER_SIZE / 2f + TOUCH_SLOP
            
            Log.d(TAG, "Checking handle at ($x, $y), selection: $selectionRect, hitArea: $handleHitArea")

            // Top-left corner
            val distanceToTopLeft = kotlin.math.sqrt(
                (x - selectionRect.left) * (x - selectionRect.left) + 
                (y - selectionRect.top) * (y - selectionRect.top)
            )
            if (distanceToTopLeft <= handleHitArea) {
                Log.d(TAG, "Detected TOP_LEFT handle (distance: $distanceToTopLeft)")
                return HandlePosition.TOP_LEFT
            }

            // Top-right corner
            val distanceToTopRight = kotlin.math.sqrt(
                (x - selectionRect.right) * (x - selectionRect.right) + 
                (y - selectionRect.top) * (y - selectionRect.top)
            )
            if (distanceToTopRight <= handleHitArea) {
                Log.d(TAG, "Detected TOP_RIGHT handle (distance: $distanceToTopRight)")
                return HandlePosition.TOP_RIGHT
            }

            // Bottom-left corner
            val distanceToBottomLeft = kotlin.math.sqrt(
                (x - selectionRect.left) * (x - selectionRect.left) + 
                (y - selectionRect.bottom) * (y - selectionRect.bottom)
            )
            if (distanceToBottomLeft <= handleHitArea) {
                Log.d(TAG, "Detected BOTTOM_LEFT handle (distance: $distanceToBottomLeft)")
                return HandlePosition.BOTTOM_LEFT
            }

            // Bottom-right corner
            val distanceToBottomRight = kotlin.math.sqrt(
                (x - selectionRect.right) * (x - selectionRect.right) + 
                (y - selectionRect.bottom) * (y - selectionRect.bottom)
            )
            if (distanceToBottomRight <= handleHitArea) {
                Log.d(TAG, "Detected BOTTOM_RIGHT handle (distance: $distanceToBottomRight)")
                return HandlePosition.BOTTOM_RIGHT
            }

            // Check if inside the selection box (but not too close to edges to avoid conflict)
            val margin = CORNER_SIZE
            val innerRect = RectF(
                selectionRect.left + margin,
                selectionRect.top + margin,
                selectionRect.right - margin,
                selectionRect.bottom - margin
            )
            if (innerRect.contains(x, y)) {
                Log.d(TAG, "Detected INSIDE selection")
                return HandlePosition.INSIDE
            }

            Log.d(TAG, "No handle detected")
            return HandlePosition.NONE
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
                    instructionPaint,
                )
                canvas.drawText(
                    "Tap 'Select' button to start",
                    centerX,
                    centerY + 40f,
                    instructionPaint,
                )
            } else if (hasSelection) {
                updateSelectionRect()

                // Create temporary paints for processing state without modifying the originals
                val currentFillPaint =
                    if (isProcessing) {
                        Paint(fillPaint).apply { alpha = 20 }
                    } else {
                        fillPaint
                    }
                val currentBorderPaint =
                    if (isProcessing) {
                        Paint(borderPaint).apply { alpha = 128 }
                    } else {
                        borderPaint
                    }

                // Draw the selection area
                canvas.drawRect(selectionRect, currentFillPaint)
                canvas.drawRect(selectionRect, currentBorderPaint)

                // Draw corner handles for better visual feedback
                drawCornerHandles(canvas, selectionRect)

                // Show selection dimensions (if dragging or selection is reasonably sized)
                if (isDragging || (selectionRect.width() > MIN_SELECTION_SIZE && selectionRect.height() > MIN_SELECTION_SIZE)) {
                    val dimensionText = "${selectionRect.width().toInt()} x ${selectionRect.height().toInt()}"
                    val textX = selectionRect.centerX()
                    val textY = selectionRect.top - 20f
                    canvas.drawText(dimensionText, textX, textY, instructionPaint)
                }

                // Show visual indicator for current handle operation
                if (isDragging) {
                    val actionText =
                        when {
                            isDraggingBox -> "Moving selection"
                            activeHandle != HandlePosition.NONE -> "Resizing"
                            else -> "Creating selection"
                        }
                    val textX = selectionRect.centerX()
                    val textY = selectionRect.top - 60f // Above the dimensions text
                    canvas.drawText(actionText, textX, textY, instructionPaint)
                }

                // Show processing indicator
                if (isProcessing) {
                    val processingText = "Processing..."
                    val textX = selectionRect.centerX()
                    val textY = selectionRect.centerY()
                    canvas.drawText(processingText, textX, textY, instructionPaint)
                }
            }
        }

        private fun drawCornerHandles(
            canvas: Canvas,
            rect: RectF,
        ) {
            val cornerSize = CORNER_SIZE

            // Top-left corner
            drawHandle(canvas, rect.left, rect.top, cornerSize, HandlePosition.TOP_LEFT == activeHandle)

            // Top-right corner
            drawHandle(canvas, rect.right, rect.top, cornerSize, HandlePosition.TOP_RIGHT == activeHandle)

            // Bottom-left corner
            drawHandle(canvas, rect.left, rect.bottom, cornerSize, HandlePosition.BOTTOM_LEFT == activeHandle)

            // Bottom-right corner
            drawHandle(canvas, rect.right, rect.bottom, cornerSize, HandlePosition.BOTTOM_RIGHT == activeHandle)
        }

        /**
         * Draws an individual handle with optional highlight when active
         */
        private fun drawHandle(
            canvas: Canvas,
            x: Float,
            y: Float,
            size: Float,
            isActive: Boolean,
        ) {
            val halfSize = size / 2
            val handlePaint =
                if (isActive) {
                    Paint(cornerPaint).apply {
                        color = Color.WHITE
                        setShadowLayer(8f, 0f, 0f, Color.parseColor("#4CAF50"))
                        strokeWidth = 3f
                    }
                } else {
                    Paint(cornerPaint).apply {
                        strokeWidth = 2f
                        style = Paint.Style.FILL_AND_STROKE
                    }
                }

            // Draw outer circle for better visibility
            canvas.drawCircle(x, y, halfSize + 2f, Paint().apply {
                color = Color.WHITE
                style = Paint.Style.FILL
                setShadowLayer(4f, 0f, 0f, Color.BLACK)
            })
            
            // Draw the handle itself
            canvas.drawCircle(x, y, halfSize, handlePaint)
        }

        fun reset() {
            Log.d("SelectionOverlay", "reset() called - clearing selection")
            hasSelection = false
            isDragging = false
            isProcessing = false
            invalidate()
        }

        fun getSelection(): RectF? {
            Log.d("SelectionOverlay", "getSelection() called - hasSelection: $hasSelection")
            if (!hasSelection) {
                Log.d("SelectionOverlay", "getSelection() returning null - no selection")
                return null
            }

            val rect =
                RectF(
                    minOf(startX, currentX),
                    minOf(startY, currentY),
                    maxOf(startX, currentX),
                    maxOf(startY, currentY),
                )

            // Only return valid selections that meet minimum size requirements
            val result =
                if (rect.width() >= MIN_SELECTION_SIZE && rect.height() >= MIN_SELECTION_SIZE) {
                    rect
                } else {
                    null
                }

            Log.d(
                "SelectionOverlay",
                "getSelection() returning: ${if (result != null) "valid rect (${rect.width()}x${rect.height()})" else "null (too small)"})",
            )
            return result
        }

        fun hasValidSelection(): Boolean {
            return getSelection() != null
        }

        fun setProcessing(processing: Boolean) {
            isProcessing = processing
            invalidate()
        }

        fun isProcessing(): Boolean {
            return isProcessing
        }
    }
