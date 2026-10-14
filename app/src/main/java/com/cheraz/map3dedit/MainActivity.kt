package com.cheraz.map3dedit

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
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
import java.io.File
import kotlin.random.Random

/**
 * HUD organizado por categorías (pestañas inferiores):
 *  - Terreno: generar / cargar / guardar y exportar.
 *  - Editar:  tipo de edición activa (Ninguna o Esculpir) y sus ajustes.
 *  - Mover:   tipo de movimiento / transformación (Orbitar o Caminar) y el personaje-cámara.
 * Editar y Mover son independientes: se puede esculpir orbitando o caminando.
 */
class MainActivity : Activity() {

    private lateinit var glView: GLSurfaceView
    private val renderer = TerrainRenderer()

    private var lastX = 0f
    private var lastY = 0f
    private var lastPointerCount = 0
    private var sculptBlocked = false

    private lateinit var scaleDetector: ScaleGestureDetector
    private lateinit var joystickOverlay: JoystickOverlay
    private lateinit var fireBtn: Button
    private lateinit var hud: LinearLayout
    private lateinit var panelHost: FrameLayout

    private val tabBtns = mutableListOf<Button>()
    private val tabPanels = mutableListOf<View>()
    private var openTab = -1

    private lateinit var editNoneBtn: Button
    private lateinit var editSculptBtn: Button
    private lateinit var moveOrbitBtn: Button
    private lateinit var moveWalkBtn: Button
    private lateinit var brushGroup: LinearLayout
    private val toolBtns = mutableListOf<Button>()

    // La mirada puede venir del joystick derecho o de arrastrar sobre el botón de disparo
    private var lookOvX = 0f
    private var lookOvY = 0f
    private var lookFireX = 0f
    private var lookFireY = 0f

    private val pickImageCode = 1001
    private val matchParent = ViewGroup.LayoutParams.MATCH_PARENT
    private val wrapContent = ViewGroup.LayoutParams.WRAP_CONTENT

    private val accent = Color.rgb(230, 110, 30)
    private val darkBtn = Color.argb(225, 52, 56, 60)

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

        root.addView(buildHud())
        root.addView(buildUndoBar())

        setContentView(root)
        applyHudSizing()
        updateUi()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        applyHudSizing()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    // ---------- Estilo ----------

    private fun styleBtn(b: Button, selected: Boolean) {
        b.background = GradientDrawable().apply {
            cornerRadius = dp(8).toFloat()
            setColor(if (selected) accent else darkBtn)
        }
    }

    private fun hudButton(label: String, textSp: Float = 12f, onClick: () -> Unit): Button = Button(this).apply {
        text = label
        textSize = textSp
        setAllCaps(false)
        setTextColor(Color.WHITE)
        minHeight = 0
        minimumHeight = 0
        minWidth = 0
        minimumWidth = 0
        setPadding(dp(6), 0, dp(6), 0)
        stateListAnimator = null // sin sombra de elevación
        styleBtn(this, false)
        setOnClickListener { onClick() }
    }

    private fun sectionTitle(text: String): TextView = TextView(this).apply {
        this.text = text
        textSize = 11f
        setTextColor(Color.argb(200, 255, 255, 255))
        setPadding(dp(2), 0, 0, dp(4))
    }

    /** Fila horizontal con los botones repartidos en partes iguales. */
    private fun row(vararg views: View): LinearLayout {
        val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for (v in views) {
            r.addView(v, LinearLayout.LayoutParams(0, dp(38), 1f).apply {
                setMargins(dp(2), dp(2), dp(2), dp(2))
            })
        }
        return r
    }

    // ---------- HUD ----------

    private fun buildHud(): LinearLayout {
        hud = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        panelHost = FrameLayout(this).apply {
            background = GradientDrawable().apply {
                cornerRadius = dp(12).toFloat()
                setColor(Color.argb(150, 0, 0, 0))
            }
            setPadding(dp(8), dp(8), dp(8), dp(8))
            isClickable = true // que los toques sobre el panel no lleguen al terreno
            visibility = View.GONE
        }
        tabPanels.add(buildTerrainPanel())
        tabPanels.add(buildEditPanel())
        tabPanels.add(buildMovePanel())
        for (p in tabPanels) panelHost.addView(p, FrameLayout.LayoutParams(matchParent, wrapContent))
        hud.addView(panelHost, LinearLayout.LayoutParams(matchParent, wrapContent).apply {
            bottomMargin = dp(6)
        })

        val tabBar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for (i in 0..2) {
            val b = hudButton("", 12f) { toggleTab(i) }
            tabBtns.add(b)
            tabBar.addView(b, LinearLayout.LayoutParams(0, dp(42), 1f).apply {
                setMargins(dp(2), 0, dp(2), 0)
            })
        }
        hud.addView(tabBar, LinearLayout.LayoutParams(matchParent, wrapContent))
        return hud
    }

    /** Ancho cómodo: toda la pantalla en vertical, un bloque a la izquierda en horizontal. */
    private fun applyHudSizing() {
        val widthDp = resources.displayMetrics.widthPixels / resources.displayMetrics.density
        val w = if (widthDp > 480f) dp(440) else matchParent
        hud.layoutParams = FrameLayout.LayoutParams(w, wrapContent, Gravity.BOTTOM or Gravity.START).apply {
            setMargins(dp(8), dp(8), dp(8), dp(8))
        }
        hud.requestLayout()
    }

    private fun toggleTab(i: Int) {
        openTab = if (openTab == i) -1 else i
        updateUi()
    }

    private fun buildTerrainPanel(): LinearLayout {
        val p = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        p.addView(sectionTitle("TERRENO"))
        p.addView(
            row(
                hudButton("Ruido nuevo") {
                    val seed = Random.nextInt(1, 100000)
                    renderer.replaceTerrain { it.generateNoise(seed = seed) }
                },
                hudButton("Cargar PNG") { pickPng() },
                hudButton("Archivo…") { showFileMenu() }
            )
        )
        return p
    }

    private fun buildEditPanel(): LinearLayout {
        val p = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        p.addView(sectionTitle("EDITAR"))

        editNoneBtn = hudButton("Ninguna") { setEdit(false) }
        editSculptBtn = hudButton("Esculpir") { setEdit(true) }
        p.addView(row(editNoneBtn, editSculptBtn))

        // Ajustes del pincel: solo visibles mientras Esculpir está activo
        brushGroup = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        brushGroup.addView(sectionTitle("PINCEL").apply { setPadding(dp(2), dp(6), 0, dp(4)) })

        val names = listOf("Subir", "Bajar", "Suavizar", "Aplanar", "Rugoso", "Terraza")
        for (r in 0..1) {
            val buttons = (0..2).map { c ->
                val idx = r * 3 + c
                val b = hudButton(names[idx]) { selectTool(idx) }
                toolBtns.add(b)
                b
            }
            brushGroup.addView(row(*buttons.toTypedArray()))
        }

        // Radio 0.5..8 u (default ~3), fuerza 0.2..6 u/s (default ~2)
        brushGroup.addView(labeledSeek("Radio", 33) { v -> renderer.brushRadius = 0.5f + v / 100f * 7.5f })
        brushGroup.addView(labeledSeek("Fuerza", 31) { v -> renderer.brushStrength = 0.2f + v / 100f * 5.8f })
        p.addView(brushGroup)

        selectTool(Terrain.TOOL_RAISE)
        return p
    }

    private fun buildMovePanel(): LinearLayout {
        val p = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        p.addView(sectionTitle("MOVER"))

        moveOrbitBtn = hudButton("Orbitar") { setWalk(false) }
        moveWalkBtn = hudButton("Caminar") { setWalk(true) }
        p.addView(row(moveOrbitBtn, moveWalkBtn))
        p.addView(row(hudButton("Personaje aquí") { renderer.placeAvatarAtCursor() }))

        p.addView(TextView(this).apply {
            text = "El personaje de cajas es la cámara de Caminar; la baliza lo marca desde lejos."
            textSize = 10f
            setTextColor(Color.argb(170, 255, 255, 255))
            setPadding(dp(2), dp(4), dp(2), 0)
        })
        return p
    }

    private fun buildUndoBar(): LinearLayout {
        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        bar.addView(hudButton("Deshacer", 11f) { renderer.undo() }, LinearLayout.LayoutParams(dp(84), dp(34)).apply {
            rightMargin = dp(4)
        })
        bar.addView(hudButton("Rehacer", 11f) { renderer.redo() }, LinearLayout.LayoutParams(dp(84), dp(34)))
        bar.layoutParams = FrameLayout.LayoutParams(wrapContent, wrapContent, Gravity.TOP or Gravity.END).apply {
            setMargins(0, dp(8), dp(8), 0)
        }
        return bar
    }

    private fun labeledSeek(label: String, initial: Int, onChange: (Int) -> Unit): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val title = TextView(this).apply {
            text = label
            textSize = 12f
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
        row.addView(title, LinearLayout.LayoutParams(dp(52), wrapContent).apply { leftMargin = dp(2) })
        row.addView(slider, LinearLayout.LayoutParams(0, dp(34), 1f))
        return row
    }

    private fun selectTool(idx: Int) {
        renderer.tool = idx
        toolBtns.forEachIndexed { i, b -> styleBtn(b, i == idx) }
    }

    // ---------- Modos ----------

    private fun setEdit(sculpt: Boolean) {
        renderer.sculptEnabled = sculpt
        if (!sculpt) {
            renderer.sculptActive = false
            renderer.fireActive = false
        }
        updateUi()
    }

    private fun setWalk(walk: Boolean) {
        renderer.walkMode = walk
        if (!walk) {
            renderer.fireActive = false
            lookFireX = 0f
            lookFireY = 0f
            joystickOverlay.reset()
            applyLook()
        }
        updateUi()
    }

    /** Refleja el estado (modo de movimiento, edición activa, pestaña abierta) en todo el HUD. */
    private fun updateUi() {
        val walking = renderer.walkMode
        val sculpting = renderer.sculptEnabled

        joystickOverlay.visibility = if (walking) View.VISIBLE else View.GONE
        // El arma editora solo aparece cuando se camina con una edición activa
        fireBtn.visibility = if (walking && sculpting) View.VISIBLE else View.GONE

        panelHost.visibility = if (openTab >= 0) View.VISIBLE else View.GONE
        tabPanels.forEachIndexed { i, p -> p.visibility = if (i == openTab) View.VISIBLE else View.GONE }
        brushGroup.visibility = if (sculpting) View.VISIBLE else View.GONE

        // Las pestañas muestran el modo activo sin necesidad de abrirlas
        tabBtns[0].text = "Terreno"
        tabBtns[1].text = if (sculpting) "Editar · Esculpir" else "Editar"
        tabBtns[2].text = if (walking) "Mover · Caminar" else "Mover · Orbitar"
        tabBtns.forEachIndexed { i, b -> styleBtn(b, i == openTab) }

        styleBtn(editNoneBtn, !sculpting)
        styleBtn(editSculptBtn, sculpting)
        styleBtn(moveOrbitBtn, !walking)
        styleBtn(moveWalkBtn, walking)
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
        val size = dp(84)
        val b = Button(this).apply {
            text = "ESCULPIR"
            textSize = 10f
            setTextColor(Color.WHITE)
            setPadding(0, 0, 0, 0)
            minWidth = 0
            minimumWidth = 0
            minHeight = 0
            minimumHeight = 0
            stateListAnimator = null
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.argb(210, 230, 110, 30))
                setStroke(dp(2), Color.argb(230, 255, 255, 255))
            }
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
        b.layoutParams = FrameLayout.LayoutParams(size, size, Gravity.END or Gravity.CENTER_VERTICAL).apply {
            rightMargin = dp(20)
        }
        return b
    }

    // ---------- Archivo: guardar / exportar ----------

    private val reqSaveProject = 2001
    private val reqExportPng = 2002
    private val reqExportRaw = 2003
    private val reqExportObj = 2004
    private val reqOpenProject = 2005

    private fun pickPng() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "image/*"
        }
        startActivityForResult(intent, pickImageCode)
    }

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

    // Esculpir (vista orbital): un dedo pinta; dos dedos orbitan (centroide) y pinch hace zoom.
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
