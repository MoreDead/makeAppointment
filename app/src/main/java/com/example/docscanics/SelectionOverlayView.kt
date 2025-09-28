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
                color = Color.GREEN
                style = Paint.Style.STROKE
                strokeWidth = 6f
            }
        private val fillPaint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.argb(60, 0, 255, 0)
                style = Paint.Style.FILL
            }

        private var startX = 0f
        private var startY = 0f
        private var currentX = 0f
        private var currentY = 0f
        private var hasSelection = false

        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startX = event.x
                    startY = event.y
                    currentX = startX
                    currentY = startY
                    hasSelection = true
                    invalidate()
                }
                MotionEvent.ACTION_MOVE -> {
                    currentX = event.x
                    currentY = event.y
                    invalidate()
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    currentX = event.x
                    currentY = event.y
                    invalidate()
                }
            }
            return true
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            if (hasSelection) {
                val rect =
                    RectF(
                        minOf(startX, currentX),
                        minOf(startY, currentY),
                        maxOf(startX, currentX),
                        maxOf(startY, currentY),
                    )
                canvas.drawRect(rect, fillPaint)
                canvas.drawRect(rect, borderPaint)
            }
        }

        fun reset() {
            hasSelection = false
            invalidate()
        }

        fun getSelection(): RectF? {
            if (!hasSelection) return null
            return RectF(
                minOf(startX, currentX),
                minOf(startY, currentY),
                maxOf(startX, currentX),
                maxOf(startY, currentY),
            )
        }
    }
