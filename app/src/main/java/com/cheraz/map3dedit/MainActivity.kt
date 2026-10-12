package com.cheraz.map3dedit

import android.app.Activity
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.view.MotionEvent
import android.view.ScaleGestureDetector

class MainActivity : Activity() {

    private lateinit var glView: GLSurfaceView
    private val renderer = TerrainRenderer()

    private var lastX = 0f
    private var lastY = 0f
    private var lastPointerCount = 0

    private lateinit var scaleDetector: ScaleGestureDetector

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        scaleDetector = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                renderer.dist = (renderer.dist / detector.scaleFactor).coerceIn(5f, 80f)
                return true
            }
        })

        glView = object : GLSurfaceView(this) {
            override fun onTouchEvent(event: MotionEvent): Boolean {
                scaleDetector.onTouchEvent(event)
                handleOrbit(event)
                return true
            }
        }.apply {
            setEGLContextClientVersion(3)
            setRenderer(renderer)
            renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
        }
        setContentView(glView)
    }

    // Un dedo: orbitar. Dos dedos: solo pinch (zoom).
    private fun handleOrbit(event: MotionEvent) {
        val count = event.pointerCount
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastX = event.x
                lastY = event.y
            }
            MotionEvent.ACTION_MOVE -> {
                if (count == 1 && lastPointerCount <= 1) {
                    val dx = event.x - lastX
                    val dy = event.y - lastY
                    renderer.yaw -= dx * 0.4f
                    renderer.pitch = (renderer.pitch + dy * 0.4f).coerceIn(5f, 85f)
                }
                lastX = event.x
                lastY = event.y
            }
            MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_UP -> {
                // Reubicar el punto de referencia para que no brinque la cámara
                val idx = if (event.actionIndex == 0) 1 else 0
                if (event.pointerCount > 1) {
                    lastX = event.getX(idx)
                    lastY = event.getY(idx)
                }
            }
        }
        lastPointerCount = if (event.actionMasked == MotionEvent.ACTION_UP) 0 else count
    }

    override fun onPause() {
        super.onPause()
        glView.onPause()
    }

    override fun onResume() {
        super.onResume()
        glView.onResume()
    }
}
