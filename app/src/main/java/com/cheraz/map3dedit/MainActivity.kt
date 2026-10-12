package com.cheraz.map3dedit

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.Toast
import kotlin.random.Random

class MainActivity : Activity() {

    private lateinit var glView: GLSurfaceView
    private val renderer = TerrainRenderer()

    private var lastX = 0f
    private var lastY = 0f
    private var lastPointerCount = 0

    private lateinit var scaleDetector: ScaleGestureDetector
    private lateinit var joystickOverlay: JoystickOverlay

    private val pickImageCode = 1001

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

        val root = FrameLayout(this)
        root.addView(glView)

        joystickOverlay = JoystickOverlay(
            this,
            onMove = { x, y -> renderer.moveX = x; renderer.moveY = y },
            onLook = { x, y -> renderer.lookX = x; renderer.lookY = y }
        ).apply { visibility = View.GONE }
        root.addView(joystickOverlay)

        // La barra va al final para quedar encima de los joysticks y recibir sus toques
        root.addView(buildToolbar())
        setContentView(root)
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun buildToolbar(): LinearLayout {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(8), dp(8), dp(8), dp(56))
        }

        val noiseBtn = Button(this).apply {
            text = "Ruido nuevo"
            setOnClickListener {
                renderer.terrain.generateNoise(seed = Random.nextInt(1, 100000))
                renderer.requestUpdate()
            }
        }
        val pngBtn = Button(this).apply {
            text = "Cargar PNG"
            setOnClickListener {
                val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "image/*"
                }
                startActivityForResult(intent, pickImageCode)
            }
        }
        val modeBtn = Button(this).apply {
            text = "Caminar"
            setOnClickListener {
                val walk = !renderer.walkMode
                renderer.walkMode = walk
                if (walk) {
                    joystickOverlay.visibility = View.VISIBLE
                } else {
                    joystickOverlay.visibility = View.GONE
                    joystickOverlay.reset()
                }
                text = if (walk) "Orbitar" else "Caminar"
            }
        }
        bar.addView(modeBtn)
        bar.addView(noiseBtn)
        bar.addView(pngBtn)

        bar.layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM or Gravity.START
        )
        return bar
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != pickImageCode || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        val bmp = decodeBitmap(uri)
        if (bmp == null) {
            Toast.makeText(this, "No se pudo leer la imagen", Toast.LENGTH_SHORT).show()
            return
        }
        renderer.terrain.loadFromBitmap(bmp)
        renderer.requestUpdate()
    }

    /** Decodifica con submuestreo para no reventar memoria con imágenes enormes. */
    private fun decodeBitmap(uri: Uri): Bitmap? {
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            var sample = 1
            while (bounds.outWidth / sample > 2048 || bounds.outHeight / sample > 2048) sample *= 2
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
        } catch (e: Exception) {
            null
        }
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
