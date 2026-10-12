package com.cheraz.map3dedit

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.MotionEvent
import android.view.View
import kotlin.math.hypot

/**
 * Dos joysticks flotantes: el dedo que toca en la mitad izquierda mueve,
 * el de la mitad derecha mira. Salidas normalizadas -1..1 con "arriba" positivo.
 */
class JoystickOverlay(
    context: Context,
    private val onMove: (Float, Float) -> Unit,
    private val onLook: (Float, Float) -> Unit
) : View(context) {

    private val radius = 60f * resources.displayMetrics.density

    private class Stick {
        var id = -1
        var cx = 0f
        var cy = 0f
        var kx = 0f
        var ky = 0f
        val active get() = id != -1
    }

    private val left = Stick()
    private val right = Stick()

    private val basePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f * resources.displayMetrics.density
        color = Color.argb(140, 255, 255, 255)
    }
    private val knobPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.argb(150, 255, 255, 255)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val i = e.actionIndex
                val x = e.getX(i)
                val y = e.getY(i)
                val stick = if (x < width / 2f) left else right
                if (!stick.active) {
                    stick.id = e.getPointerId(i)
                    stick.cx = x; stick.cy = y
                    stick.kx = x; stick.ky = y
                }
            }
            MotionEvent.ACTION_MOVE -> {
                for (i in 0 until e.pointerCount) {
                    val id = e.getPointerId(i)
                    val stick = when (id) {
                        left.id -> left
                        right.id -> right
                        else -> continue
                    }
                    updateKnob(stick, e.getX(i), e.getY(i))
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                release(e.getPointerId(e.actionIndex))
            }
            MotionEvent.ACTION_CANCEL -> {
                release(left.id)
                release(right.id)
            }
        }
        emit()
        invalidate()
        return true
    }

    private fun updateKnob(s: Stick, x: Float, y: Float) {
        var dx = x - s.cx
        var dy = y - s.cy
        val len = hypot(dx, dy)
        if (len > radius) {
            dx = dx / len * radius
            dy = dy / len * radius
        }
        s.kx = s.cx + dx
        s.ky = s.cy + dy
    }

    private fun release(id: Int) {
        if (id == -1) return
        if (left.id == id) left.id = -1
        if (right.id == id) right.id = -1
    }

    private fun emit() {
        if (left.active) onMove((left.kx - left.cx) / radius, -(left.ky - left.cy) / radius)
        else onMove(0f, 0f)
        if (right.active) onLook((right.kx - right.cx) / radius, -(right.ky - right.cy) / radius)
        else onLook(0f, 0f)
    }

    /** Se llama al ocultar el overlay para que no queden joysticks "pegados". */
    fun reset() {
        left.id = -1
        right.id = -1
        emit()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        for (s in listOf(left, right)) {
            if (!s.active) continue
            canvas.drawCircle(s.cx, s.cy, radius, basePaint)
            canvas.drawCircle(s.kx, s.ky, radius * 0.4f, knobPaint)
        }
    }
}
