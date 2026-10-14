package com.cheraz.map3dedit

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import android.app.AlertDialog
import android.widget.HorizontalScrollView
import java.io.File
import kotlin.random.Random

class MainActivity : Activity() {

    private lateinit var glView: GLSurfaceView
    private val renderer = TerrainRenderer()

    private var lastX = 0f
    private var lastY = 0f
    private var lastPointerCount = 0
    private var sculptBlocked = false

    private lateinit var scaleDetector: ScaleGestureDetector
    private lateinit var joystickOverlay: JoystickOverlay
    private lateinit var sculptPanel: LinearLayout
    private lateinit var walkBtn: Button
    private lateinit var sculptBtn: Button
    private lateinit var fireBtn: Button
    private val toolButtons = mutableListOf<Button>()

    private var panelOpen = true

    // La mirada puede venir del joystick derecho o de arrastrar sobre el botón de disparo
    private var lookOvX = 0f
    private var lookOvY = 0f
    private var lookFireX = 0f
    private var lookFireY = 0f

    private val pickImageCode = 1001
    private val matchParent = ViewGroup.LayoutParams.MATCH_PARENT
    private val wrapContent = ViewGroup.LayoutParams.WRAP_CONTENT

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Recupera el último terreno guardado automáticamente (si existe y es válido)
        try {
            val f = File(filesDir, "autosave.m3d")
            if (f.exists()) f.inputStream().use { ProjectIO.loadProject(it, renderer.terrain) }
        } catch (e: Exception) {
            // archivo corrupto o de otra versión: se ignora y arranca con el terreno por defecto
        }

        scaleDetector = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                renderer.dist = (renderer.dist / detector.scaleFactor).coerceIn(5f, 150f)
                return true
            }
        })

        glView = object : GLSurfaceView(this) {
            override fun onTouchEvent(event: MotionEvent): Boolean {
                scaleDetector.onTouchEvent(event)
                if (renderer.sculptEnabled) handleSculpt(event) else handleOrbit(event)
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
            onLook = { x, y -> lookOvX = x; lookOvY = y; applyLook() }
        ).apply { visibility = View.GONE }
        root.addView(joystickOverlay)

        fireBtn = buildFireButton()
        root.addView(fireBtn)

        // La barra va al final para quedar encima de los joysticks y recibir sus toques
        root.addView(buildToolbar())
        setContentView(root)
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    // ---------- UI ----------

    private fun smallButton(label: String): Button = Button(this).apply {
        text = label
        textSize = 11f
        minWidth = 0
        minimumWidth = 0
        setPadding(dp(10), 0, dp(10), 0)
    }

    private fun buildToolbar(): LinearLayout {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(8), dp(8), dp(56))
        }

        sculptPanel = buildSculptPanel()
        container.addView(sculptPanel, LinearLayout.LayoutParams(matchParent, wrapContent))

        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }

        walkBtn = smallButton("Caminar").apply {
            setOnClickListener { setWalk(!renderer.walkMode) }
        }
        sculptBtn = smallButton("Esculpir").apply {
            setOnClickListener { setSculpt(!renderer.sculptEnabled) }
        }
        val noiseBtn = smallButton("Ruido nuevo").apply {
            setOnClickListener {
                val seed = Random.nextInt(1, 100000)
                renderer.replaceTerrain { it.generateNoise(seed = seed) }
            }
        }
        val pngBtn = smallButton("Cargar PNG").apply {
            setOnClickListener {
                val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "image/*"
                }
                startActivityForResult(intent, pickImageCode)
            }
        }
        bar.addView(walkBtn)
        bar.addView(sculptBtn)
        bar.addView(noiseBtn)
        val fileBtn = smallButton("Archivo").apply {
            setOnClickListener { showFileMenu() }
        }
        val panelBtn = smallButton("Panel").apply {
            setOnClickListener {
                panelOpen = !panelOpen
                updateUi()
            }
        }
        bar.addView(pngBtn)
        bar.addView(fileBtn)
        bar.addView(panelBtn)
        // Con 5 botones ya no cabe todo en una fila: la barra se desplaza horizontalmente
        val scroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(bar)
        }
        container.addView(scroll)

        container.layoutParams = FrameLayout.LayoutParams(matchParent, wrapContent, Gravity.BOTTOM)
        return container
    }

    private fun buildSculptPanel(): LinearLayout {
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.argb(130, 0, 0, 0))
            setPadding(dp(8), dp(6), dp(8), dp(6))
            isClickable = true // que los toques sobre el panel no lleguen al terreno
            visibility = View.GONE
        }

        val toolRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        listOf("Subir", "Bajar", "Suavizar", "Aplanar", "Rugoso", "Terraza").forEachIndexed { idx, name ->
            val b = smallButton(name).apply { setOnClickListener { selectTool(idx) } }
            toolButtons.add(b)
            toolRow.addView(b, LinearLayout.LayoutParams(0, wrapContent, 1f))
        }
        panel.addView(toolRow)

        // Radio 0.5..8 u (default ~3), fuerza 0.2..6 u/s (default ~2)
        panel.addView(labeledSeek("Radio", 33) { p -> renderer.brushRadius = 0.5f + p / 100f * 7.5f })
        panel.addView(labeledSeek("Fuerza", 31) { p -> renderer.brushStrength = 0.2f + p / 100f * 5.8f })

        val undoRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val undoBtn = smallButton("Deshacer").apply { setOnClickListener { renderer.undo() } }
        val redoBtn = smallButton("Rehacer").apply { setOnClickListener { renderer.redo() } }
        undoRow.addView(undoBtn, LinearLayout.LayoutParams(0, wrapContent, 1f))
        val hereBtn = smallButton("Cámara aquí").apply { setOnClickListener { renderer.placeAvatarAtCursor() } }
        undoRow.addView(redoBtn, LinearLayout.LayoutParams(0, wrapContent, 1f))
        undoRow.addView(hereBtn, LinearLayout.LayoutParams(0, wrapContent, 1f))
        panel.addView(undoRow)

        selectTool(Terrain.TOOL_RAISE)
        return panel
    }

    private fun labeledSeek(label: String, initial: Int, onChange: (Int) -> Unit): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val title = TextView(this).apply {
            text = label
            setTextColor(Color.WHITE)
        }
        val slider = SeekBar(this).apply {
            max = 100
            progress = initial
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) { onChange(p) }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        }
        row.addView(title, LinearLayout.LayoutParams(dp(64), wrapContent))
        row.addView(slider, LinearLayout.LayoutParams(0, wrapContent, 1f))
        return row
    }

    private fun selectTool(idx: Int) {
        renderer.tool = idx
        toolButtons.forEachIndexed { i, b -> b.alpha = if (i == idx) 1f else 0.5f }
    }

    private fun setWalk(walk: Boolean) {
        if (walk && renderer.sculptEnabled) setSculpt(false)
        renderer.walkMode = walk
        if (!walk) {
            renderer.fireActive = false
            lookFireX = 0f
            lookFireY = 0f
            joystickOverlay.reset()
            applyLook()
        }
        walkBtn.text = if (walk) "Orbitar" else "Caminar"
        updateUi()
    }

    private fun setSculpt(on: Boolean) {
        if (on && renderer.walkMode) setWalk(false)
        renderer.sculptEnabled = on
        if (!on) renderer.sculptActive = false
        sculptBtn.text = if (on) "Esculpir ●" else "Esculpir"
        updateUi()
    }

    /** Caminando: joysticks, botón de disparo y panel de herramientas. */
    private fun updateUi() {
        val walking = renderer.walkMode
        joystickOverlay.visibility = if (walking) View.VISIBLE else View.GONE
        fireBtn.visibility = if (walking) View.VISIBLE else View.GONE
        val panelWanted = (renderer.sculptEnabled || walking) && panelOpen
        sculptPanel.visibility = if (panelWanted) View.VISIBLE else View.GONE
    }

    private fun applyLook() {
        renderer.lookX = (lookOvX + lookFireX).coerceIn(-1f, 1f)
        renderer.lookY = (lookOvY + lookFireY).coerceIn(-1f, 1f)
    }

    /**
     * Arma editora: mientras se mantiene presionado esculpe donde apunta la mira,
     * y arrastrar el dedo sin soltarlo gira la vista (así disparas y apuntas con el mismo pulgar).
     */
    private fun buildFireButton(): Button {
        val radius = dp(60).toFloat()
        val b = Button(this).apply {
            text = "ESCULPIR"
            textSize = 12f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.argb(200, 220, 90, 20))
            visibility = View.GONE
        }
        var downX = 0f
        var downY = 0f
        b.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.x
                    downY = e.y
                    renderer.fireActive = true
                }
                MotionEvent.ACTION_MOVE -> {
                    lookFireX = ((e.x - downX) / radius).coerceIn(-1f, 1f)
                    lookFireY = (-(e.y - downY) / radius).coerceIn(-1f, 1f)
                    applyLook()
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    renderer.fireActive = false
                    lookFireX = 0f
                    lookFireY = 0f
                    applyLook()
                }
            }
            true
        }
        b.layoutParams = FrameLayout.LayoutParams(dp(110), dp(110), Gravity.END or Gravity.CENTER_VERTICAL).apply {
            rightMargin = dp(24)
        }
        return b
    }

    // ---------- Archivo: guardar / exportar ----------

    private val reqSaveProject = 2001
    private val reqExportPng = 2002
    private val reqExportRaw = 2003
    private val reqExportObj = 2004
    private val reqOpenProject = 2005

    private fun showFileMenu() {
        val items = arrayOf(
            "Guardar proyecto (.m3d)",
            "Abrir proyecto (.m3d)",
            "Exportar PNG 16 bits",
            "Exportar RAW 16 bits",
            "Exportar malla OBJ"
        )
        AlertDialog.Builder(this)
            .setTitle("Archivo")
            .setItems(items) { _, which ->
                val n = renderer.terrain.n
                when (which) {
                    0 -> createDocument(reqSaveProject, "application/octet-stream", "terreno.m3d")
                    1 -> {
                        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                            addCategory(Intent.CATEGORY_OPENABLE)
                            type = "*/*"
                        }
                        startActivityForResult(i, reqOpenProject)
                    }
                    2 -> createDocument(reqExportPng, "image/png", "terreno_${n}x${n}_16bit.png")
                    3 -> createDocument(reqExportRaw, "application/octet-stream", "terreno_${n}x${n}_16bit.raw")
                    4 -> createDocument(reqExportObj, "application/octet-stream", "terreno.obj")
                }
            }
            .show()
    }

    private fun createDocument(code: Int, mime: String, name: String) {
        val i = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = mime
            putExtra(Intent.EXTRA_TITLE, name)
        }
        startActivityForResult(i, code)
    }

    private fun handleFileResult(code: Int, uri: Uri) {
        val t = renderer.terrain
        try {
            when (code) {
                reqSaveProject -> {
                    contentResolver.openOutputStream(uri)?.use { ProjectIO.saveProject(it, t) }
                    toast("Proyecto guardado")
                }
                reqOpenProject -> {
                    // Se lee a memoria aquí y se aplica en el hilo GL (con paso de deshacer)
                    val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    if (bytes == null) {
                        toast("No se pudo leer el archivo")
                    } else {
                        renderer.replaceTerrain { terrain ->
                            val ok = try {
                                ProjectIO.loadProject(java.io.ByteArrayInputStream(bytes), terrain)
                            } catch (e: Exception) {
                                false
                            }
                            runOnUiThread {
                                toast(if (ok) "Proyecto abierto" else "Archivo no válido (o de otra resolución)")
                            }
                        }
                    }
                }
                reqExportPng -> {
                    val q = contentResolver.openOutputStream(uri)?.use { ProjectIO.writePng16(it, t) }
                    if (q != null) toast(heightInfo(q))
                }
                reqExportRaw -> {
                    val q = contentResolver.openOutputStream(uri)?.use { ProjectIO.writeRaw16(it, t) }
                    if (q != null) toast(heightInfo(q))
                }
                reqExportObj -> {
                    contentResolver.openOutputStream(uri)?.use { ProjectIO.writeObj(it, t) }
                    toast("OBJ exportado")
                }
            }
        } catch (e: Exception) {
            toast("Error: ${e.message}")
        }
    }

    /** Para reconstruir la escala real al reimportar el PNG/RAW en otro motor. */
    private fun heightInfo(q: ProjectIO.Quantized): String {
        val t = renderer.terrain
        return String.format(
            java.util.Locale.US,
            "Exportado. 0 = %.2f u, 65535 = %.2f u (rango %.2f), terreno %.0f x %.0f u",
            q.lo, q.hi, q.hi - q.lo, t.size, t.size
        )
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    // ---------- PNG ----------

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode == RESULT_OK && requestCode in reqSaveProject..reqOpenProject) {
            data?.data?.let { handleFileResult(requestCode, it) }
            return
        }
        if (requestCode != pickImageCode || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        val bmp = decodeBitmap(uri)
        if (bmp == null) {
            Toast.makeText(this, "No se pudo leer la imagen", Toast.LENGTH_SHORT).show()
            return
        }
        renderer.replaceTerrain { it.loadFromBitmap(bmp) }
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

    // ---------- Gestos ----------

    // Cámara orbital: un dedo orbita, pinch hace zoom.
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

    /** Centroide de los dedos, ignorando el índice `skip` (el que se está levantando). */
    private fun centroid(e: MotionEvent, skip: Int = -1): Pair<Float, Float> {
        var sx = 0f
        var sy = 0f
        var c = 0
        for (i in 0 until e.pointerCount) {
            if (i == skip) continue
            sx += e.getX(i)
            sy += e.getY(i)
            c++
        }
        return if (c == 0) Pair(0f, 0f) else Pair(sx / c, sy / c)
    }

    // Esculpir: un dedo pinta; dos dedos orbitan (centroide) y pinch hace zoom.
    private fun handleSculpt(e: MotionEvent) {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                sculptBlocked = false
                renderer.sculptX = e.x
                renderer.sculptY = e.y
                renderer.sculptActive = true
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                sculptBlocked = true
                renderer.sculptActive = false
                val (cx, cy) = centroid(e)
                lastX = cx
                lastY = cy
            }
            MotionEvent.ACTION_MOVE -> {
                if (e.pointerCount == 1) {
                    if (!sculptBlocked) {
                        renderer.sculptX = e.x
                        renderer.sculptY = e.y
                        renderer.sculptActive = true
                    }
                } else {
                    val (cx, cy) = centroid(e)
                    renderer.yaw -= (cx - lastX) * 0.4f
                    renderer.pitch = (renderer.pitch + (cy - lastY) * 0.4f).coerceIn(5f, 85f)
                    lastX = cx
                    lastY = cy
                }
            }
            MotionEvent.ACTION_POINTER_UP -> {
                sculptBlocked = true
                renderer.sculptActive = false
                val (cx, cy) = centroid(e, skip = e.actionIndex)
                lastX = cx
                lastY = cy
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                renderer.sculptActive = false
                sculptBlocked = false
            }
        }
    }

    override fun onPause() {
        super.onPause()
        // Autoguardado: lo que esculpes sobrevive al cerrar la app
        try {
            File(filesDir, "autosave.m3d").outputStream().use { ProjectIO.saveProject(it, renderer.terrain) }
        } catch (e: Exception) {
            // sin espacio o sin permisos: no es crítico
        }
        glView.onPause()
    }

    override fun onResume() {
        super.onResume()
        glView.onResume()
    }
}
