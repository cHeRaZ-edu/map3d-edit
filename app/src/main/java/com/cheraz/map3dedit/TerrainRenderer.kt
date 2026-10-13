package com.cheraz.map3dedit

import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentLinkedQueue
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.cos
import kotlin.math.sin

class TerrainRenderer : GLSurfaceView.Renderer {

    // Cámara orbital (grados / unidades). Se modifican desde el hilo de UI.
    @Volatile var yaw = 45f
    @Volatile var pitch = 35f
    @Volatile var dist = 55f

    val terrain = Terrain()

    // ---------- Cola de trabajo en el hilo GL ----------
    // Todo lo que modifica el terreno desde la UI pasa por aquí, así no choca con el esculpido.

    private val glQueue = ConcurrentLinkedQueue<() -> Unit>()

    fun runOnGl(task: () -> Unit) {
        glQueue.add(task)
    }

    /** Cambia el terreno (ruido, PNG, abrir proyecto...) guardando antes un paso de deshacer. */
    fun replaceTerrain(block: (Terrain) -> Unit) = runOnGl {
        pushUndo()
        block(terrain)
        dirtyAll = true
    }

    // ---------- Deshacer / rehacer ----------

    private val undoStack = ArrayDeque<FloatArray>()
    private val redoStack = ArrayDeque<FloatArray>()
    private val maxUndo = 40

    private fun pushUndo() {
        undoStack.addLast(terrain.heights.copyOf())
        if (undoStack.size > maxUndo) undoStack.removeFirst()
        redoStack.clear()
    }

    fun undo() = runOnGl {
        if (undoStack.isNotEmpty()) {
            redoStack.addLast(terrain.heights.copyOf())
            terrain.setHeightsRaw(undoStack.removeLast(), terrain.heightRange)
            dirtyAll = true
        }
    }

    fun redo() = runOnGl {
        if (redoStack.isNotEmpty()) {
            undoStack.addLast(terrain.heights.copyOf())
            terrain.setHeightsRaw(redoStack.removeLast(), terrain.heightRange)
            dirtyAll = true
        }
    }

    // ---------- Subida de vértices ----------

    @Volatile private var dirtyAll = true
    private var dirtyZ0 = Int.MAX_VALUE
    private var dirtyZ1 = -1

    fun requestUpdate() {
        dirtyAll = true
    }

    private fun markRows(z0: Int, z1: Int) {
        val a = z0.coerceIn(0, terrain.n - 1)
        val b = z1.coerceIn(0, terrain.n - 1)
        if (a < dirtyZ0) dirtyZ0 = a
        if (b > dirtyZ1) dirtyZ1 = b
    }

    // Buffers reutilizados: al esculpir se re-suben solo las filas tocadas.
    private val vertArr = FloatArray(terrain.n * terrain.n * 6)
    private val vertBuf = ByteBuffer.allocateDirect(vertArr.size * 4)
        .order(ByteOrder.nativeOrder()).asFloatBuffer()

    private fun uploadAll() {
        terrain.buildVertices(vertArr)
        vertBuf.clear()
        vertBuf.put(vertArr)
        vertBuf.flip()
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo)
        GLES30.glBufferSubData(GLES30.GL_ARRAY_BUFFER, 0, vertArr.size * 4, vertBuf)
    }

    private fun uploadRows(z0: Int, z1: Int) {
        val rowFloats = terrain.n * 6
        val start = z0 * rowFloats
        val count = (z1 - z0 + 1) * rowFloats
        terrain.buildVertexRows(vertArr, z0, z1)
        vertBuf.clear()
        vertBuf.put(vertArr, start, count)
        vertBuf.flip()
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo)
        GLES30.glBufferSubData(GLES30.GL_ARRAY_BUFFER, start * 4, count * 4, vertBuf)
    }

    private fun uploadIfDirty() {
        if (dirtyAll) {
            dirtyAll = false
            dirtyZ0 = Int.MAX_VALUE
            dirtyZ1 = -1
            uploadAll()
        } else if (dirtyZ1 >= dirtyZ0) {
            val a = dirtyZ0
            val b = dirtyZ1
            dirtyZ0 = Int.MAX_VALUE
            dirtyZ1 = -1
            uploadRows(a, b)
        }
    }

    // ---------- GL ----------

    private var program = 0
    private var uMvp = 0
    private var uLight = 0
    private var uMinH = 0
    private var uMaxH = 0
    private var vbo = 0
    private var ibo = 0
    private var indexCount = 0

    private var ringProgram = 0
    private var ringMvp = 0
    private var ringColor = 0
    private val ringSegments = 64
    private val ringArr = FloatArray(ringSegments * 3)
    private val ringBuf = ByteBuffer.allocateDirect(ringArr.size * 4)
        .order(ByteOrder.nativeOrder()).asFloatBuffer()

    private val proj = FloatArray(16)
    private val view = FloatArray(16)
    private val mvp = FloatArray(16)

    private val vertexShader = """
        #version 300 es
        layout(location = 0) in vec3 aPos;
        layout(location = 1) in vec3 aNormal;
        uniform mat4 uMvp;
        out vec3 vNormal;
        out float vHeight;
        void main() {
            vNormal = aNormal;
            vHeight = aPos.y;
            gl_Position = uMvp * vec4(aPos, 1.0);
        }
    """.trimIndent()

    private val fragmentShader = """
        #version 300 es
        precision mediump float;
        in vec3 vNormal;
        in float vHeight;
        uniform vec3 uLight;
        uniform float uMinH;
        uniform float uMaxH;
        out vec4 outColor;
        void main() {
            float diff = max(dot(normalize(vNormal), normalize(uLight)), 0.0);
            float light = 0.25 + 0.75 * diff;
            vec3 low  = vec3(0.20, 0.45, 0.20);
            vec3 mid  = vec3(0.55, 0.50, 0.30);
            vec3 high = vec3(0.90, 0.90, 0.92);
            float t = clamp((vHeight - uMinH) / (uMaxH - uMinH), 0.0, 1.0);
            vec3 base = t < 0.5 ? mix(low, mid, t * 2.0) : mix(mid, high, (t - 0.5) * 2.0);
            outColor = vec4(base * light, 1.0);
        }
    """.trimIndent()

    private val ringVertexShader = """
        #version 300 es
        layout(location = 0) in vec3 aPos;
        uniform mat4 uMvp;
        void main() {
            gl_Position = uMvp * vec4(aPos, 1.0);
        }
    """.trimIndent()

    private val ringFragmentShader = """
        #version 300 es
        precision mediump float;
        uniform vec4 uColor;
        out vec4 outColor;
        void main() {
            outColor = uColor;
        }
    """.trimIndent()

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES30.glClearColor(0.53f, 0.74f, 0.92f, 1f)
        GLES30.glEnable(GLES30.GL_DEPTH_TEST)

        program = buildProgram(vertexShader, fragmentShader)
        uMvp = GLES30.glGetUniformLocation(program, "uMvp")
        uLight = GLES30.glGetUniformLocation(program, "uLight")
        uMinH = GLES30.glGetUniformLocation(program, "uMinH")
        uMaxH = GLES30.glGetUniformLocation(program, "uMaxH")

        ringProgram = buildProgram(ringVertexShader, ringFragmentShader)
        ringMvp = GLES30.glGetUniformLocation(ringProgram, "uMvp")
        ringColor = GLES30.glGetUniformLocation(ringProgram, "uColor")

        val indices = terrain.buildIndices()
        indexCount = indices.size
        val ib = ByteBuffer.allocateDirect(indices.size * 2).order(ByteOrder.nativeOrder()).asShortBuffer()
        ib.put(indices).position(0)

        terrain.buildVertices(vertArr)
        vertBuf.clear()
        vertBuf.put(vertArr)
        vertBuf.flip()

        val bufs = IntArray(2)
        GLES30.glGenBuffers(2, bufs, 0)
        vbo = bufs[0]
        ibo = bufs[1]

        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo)
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, vertArr.size * 4, vertBuf, GLES30.GL_DYNAMIC_DRAW)
        GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, ibo)
        GLES30.glBufferData(GLES30.GL_ELEMENT_ARRAY_BUFFER, indices.size * 2, ib, GLES30.GL_STATIC_DRAW)

        dirtyAll = false
        dirtyZ0 = Int.MAX_VALUE
        dirtyZ1 = -1
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES30.glViewport(0, 0, width, height)
        viewW = width
        viewH = height
        val aspect = width.toFloat() / height.toFloat()
        Matrix.perspectiveM(proj, 0, 55f, aspect, 0.1f, 600f)
    }

    // ---------- Modo caminar ----------

    @Volatile var walkMode = false
    /** Joystick izquierdo: moveX derecha+, moveY adelante+ (rango -1..1). */
    @Volatile var moveX = 0f
    @Volatile var moveY = 0f
    /** Joystick derecho: lookX derecha+, lookY arriba+ (rango -1..1). */
    @Volatile var lookX = 0f
    @Volatile var lookY = 0f

    private var px = 0f
    private var pz = 0f
    private var yawW = 0f
    private var pitchW = 0f
    private var eyeY = 0f
    private var eyeInit = false
    private var lastNanos = 0L

    private val eyeHeight = 0.9f
    private val walkSpeed = 4f

    private fun setupCamera() {
        if (!walkMode) {
            lastNanos = 0L
            eyeInit = false
            val yawRad = Math.toRadians(yaw.toDouble())
            val pitchRad = Math.toRadians(pitch.toDouble())
            val d = dist
            val ex = (d * cos(pitchRad) * sin(yawRad)).toFloat()
            val ey = (d * sin(pitchRad)).toFloat()
            val ez = (d * cos(pitchRad) * cos(yawRad)).toFloat()
            Matrix.setLookAtM(view, 0, ex, ey, ez, 0f, 0f, 0f, 0f, 1f, 0f)
            return
        }

        val now = System.nanoTime()
        val dt = if (lastNanos == 0L) 0f else ((now - lastNanos) / 1e9f).coerceAtMost(0.1f)
        lastNanos = now

        yawW += lookX * 100f * dt
        pitchW = (pitchW + lookY * 70f * dt).coerceIn(-80f, 80f)

        val yr = Math.toRadians(yawW.toDouble())
        val fx = sin(yr).toFloat()
        val fz = -cos(yr).toFloat()
        val rx = cos(yr).toFloat()
        val rz = sin(yr).toFloat()

        px += (fx * moveY + rx * moveX) * walkSpeed * dt
        pz += (fz * moveY + rz * moveX) * walkSpeed * dt
        val lim = terrain.size / 2f - 0.2f
        px = px.coerceIn(-lim, lim)
        pz = pz.coerceIn(-lim, lim)

        val targetY = terrain.heightAtWorld(px, pz) + eyeHeight
        if (!eyeInit) {
            eyeY = targetY
            eyeInit = true
        } else {
            eyeY += (targetY - eyeY) * (1f - Math.exp(-12.0 * dt).toFloat())
        }

        val pr = Math.toRadians(pitchW.toDouble())
        val lx = fx * cos(pr).toFloat()
        val ly = sin(pr).toFloat()
        val lz = fz * cos(pr).toFloat()
        Matrix.setLookAtM(view, 0, px, eyeY, pz, px + lx, eyeY + ly, pz + lz, 0f, 1f, 0f)
    }

    // ---------- Esculpir ----------

    @Volatile var sculptEnabled = false
    /** Dedo presionado en modo esculpir; sculptX/Y son coordenadas de pantalla en píxeles. */
    @Volatile var sculptActive = false
    @Volatile var sculptX = 0f
    @Volatile var sculptY = 0f
    @Volatile var tool = Terrain.TOOL_RAISE
    @Volatile var brushRadius = 3f
    @Volatile var brushStrength = 2f

    private var viewW = 1
    private var viewH = 1
    private var sculptLastNanos = 0L
    private var strokeStarted = false
    private var flattenTarget = 0f

    // Posición del anillo del pincel (último punto tocado; al inicio, el centro de la pantalla)
    private var cursorValid = false
    private var cursorX = 0f
    private var cursorZ = 0f
    private var lastSx = -1f
    private var lastSy = -1f

    private fun endStroke() {
        sculptLastNanos = 0L
        strokeStarted = false
    }

    private fun sculptStep() {
        cursorValid = false
        if (!sculptEnabled || walkMode) {
            endStroke()
            return
        }
        val touching = sculptActive
        if (touching) {
            lastSx = sculptX
            lastSy = sculptY
        } else if (lastSx < 0f) {
            lastSx = viewW / 2f
            lastSy = viewH / 2f
        }

        val ray = screenRay(lastSx, lastSy)
        val hit = if (ray != null) terrain.raycast(ray[0], ray[1], ray[2], ray[3], ray[4], ray[5]) else null
        if (hit != null) {
            cursorX = hit[0]
            cursorZ = hit[2]
            cursorValid = true
        }

        if (!touching) {
            endStroke()
            return
        }

        val now = System.nanoTime()
        val dt = if (sculptLastNanos == 0L) 0.016f else ((now - sculptLastNanos) / 1e9f).coerceAtMost(0.05f)
        sculptLastNanos = now

        if (hit == null) return
        if (!strokeStarted) {
            pushUndo() // un trazo completo = un paso de deshacer
            flattenTarget = hit[1]
            strokeStarted = true
        }
        terrain.brush(tool, hit[0], hit[2], brushRadius, brushStrength, dt, flattenTarget)
        // +-1 fila extra: las normales de los bordes dependen de los vecinos
        markRows(terrain.brushZ0 - 1, terrain.brushZ1 + 1)
    }

    /** Rayo desde un punto de pantalla: origen (x,y,z) y dirección normalizada (x,y,z). */
    private fun screenRay(sx: Float, sy: Float): FloatArray? {
        val vp = FloatArray(16)
        Matrix.multiplyMM(vp, 0, proj, 0, view, 0)
        val inv = FloatArray(16)
        if (!Matrix.invertM(inv, 0, vp, 0)) return null
        val nx = 2f * sx / viewW - 1f
        val ny = 1f - 2f * sy / viewH
        val near = FloatArray(4)
        val far = FloatArray(4)
        Matrix.multiplyMV(near, 0, inv, 0, floatArrayOf(nx, ny, -1f, 1f), 0)
        Matrix.multiplyMV(far, 0, inv, 0, floatArrayOf(nx, ny, 1f, 1f), 0)
        for (k in 0..2) {
            near[k] /= near[3]
            far[k] /= far[3]
        }
        var dx = far[0] - near[0]
        var dy = far[1] - near[1]
        var dz = far[2] - near[2]
        val len = Math.sqrt((dx * dx + dy * dy + dz * dz).toDouble()).toFloat()
        dx /= len; dy /= len; dz /= len
        return floatArrayOf(near[0], near[1], near[2], dx, dy, dz)
    }

    /** Anillo que sigue el relieve: borde = radio del pincel, interior = mitad (zona fuerte). */
    private fun drawCursor() {
        if (!sculptEnabled || walkMode || !cursorValid) return

        GLES30.glDisable(GLES30.GL_DEPTH_TEST)
        GLES30.glUseProgram(ringProgram)
        GLES30.glUniformMatrix4fv(ringMvp, 1, false, mvp, 0)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glLineWidth(3f)

        drawRing(brushRadius, 1f, 0.9f, 0.2f)
        drawRing(brushRadius * 0.5f, 1f, 1f, 1f)

        GLES30.glEnable(GLES30.GL_DEPTH_TEST)
    }

    private fun drawRing(radius: Float, r: Float, g: Float, b: Float) {
        for (k in 0 until ringSegments) {
            val a = (2.0 * Math.PI * k / ringSegments)
            val x = cursorX + (radius * cos(a)).toFloat()
            val z = cursorZ + (radius * sin(a)).toFloat()
            ringArr[k * 3] = x
            ringArr[k * 3 + 1] = terrain.heightAtWorld(x, z) + 0.12f
            ringArr[k * 3 + 2] = z
        }
        ringBuf.clear()
        ringBuf.put(ringArr)
        ringBuf.flip()
        GLES30.glUniform4f(ringColor, r, g, b, 1f)
        GLES30.glVertexAttribPointer(0, 3, GLES30.GL_FLOAT, false, 0, ringBuf)
        GLES30.glDrawArrays(GLES30.GL_LINE_LOOP, 0, ringSegments)
    }

    // ---------- Dibujo ----------

    override fun onDrawFrame(gl: GL10?) {
        while (true) {
            val task = glQueue.poll() ?: break
            task()
        }
        sculptStep()
        uploadIfDirty()

        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT or GLES30.GL_DEPTH_BUFFER_BIT)

        setupCamera()
        Matrix.multiplyMM(mvp, 0, proj, 0, view, 0)

        GLES30.glUseProgram(program)
        GLES30.glUniformMatrix4fv(uMvp, 1, false, mvp, 0)
        GLES30.glUniform3f(uLight, 0.5f, 1.0f, 0.3f)
        GLES30.glUniform1f(uMinH, terrain.minH)
        GLES30.glUniform1f(uMaxH, terrain.maxH)

        val stride = 6 * 4
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo)
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 3, GLES30.GL_FLOAT, false, stride, 0)
        GLES30.glEnableVertexAttribArray(1)
        GLES30.glVertexAttribPointer(1, 3, GLES30.GL_FLOAT, false, stride, 3 * 4)

        GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, ibo)
        GLES30.glDrawElements(GLES30.GL_TRIANGLES, indexCount, GLES30.GL_UNSIGNED_SHORT, 0)

        drawCursor()
    }

    private fun buildProgram(vs: String, fs: String): Int {
        val v = compile(GLES30.GL_VERTEX_SHADER, vs)
        val f = compile(GLES30.GL_FRAGMENT_SHADER, fs)
        val p = GLES30.glCreateProgram()
        GLES30.glAttachShader(p, v)
        GLES30.glAttachShader(p, f)
        GLES30.glLinkProgram(p)
        val status = IntArray(1)
        GLES30.glGetProgramiv(p, GLES30.GL_LINK_STATUS, status, 0)
        check(status[0] != 0) { "Link error: " + GLES30.glGetProgramInfoLog(p) }
        return p
    }

    private fun compile(type: Int, src: String): Int {
        val s = GLES30.glCreateShader(type)
        GLES30.glShaderSource(s, src)
        GLES30.glCompileShader(s)
        val status = IntArray(1)
        GLES30.glGetShaderiv(s, GLES30.GL_COMPILE_STATUS, status, 0)
        check(status[0] != 0) { "Shader error: " + GLES30.glGetShaderInfoLog(s) }
        return s
    }
}
