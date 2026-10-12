package com.cheraz.map3dedit

import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.cos
import kotlin.math.sin

class TerrainRenderer : GLSurfaceView.Renderer {

    // Cámara orbital (grados / unidades). Se modifican desde el hilo de UI.
    @Volatile var yaw = 45f
    @Volatile var pitch = 35f
    @Volatile var dist = 28f

    val terrain = Terrain(n = 128, size = 20f)

    /** Se pone en true cuando el heightmap cambió; el hilo GL re-sube los vértices. */
    @Volatile private var dirty = false

    fun requestUpdate() {
        dirty = true
    }

    private var program = 0
    private var uMvp = 0
    private var uLight = 0
    private var uMinH = 0
    private var uMaxH = 0
    private var vbo = 0
    private var ibo = 0
    private var indexCount = 0

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

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES30.glClearColor(0.53f, 0.74f, 0.92f, 1f)
        GLES30.glEnable(GLES30.GL_DEPTH_TEST)

        program = buildProgram(vertexShader, fragmentShader)
        uMvp = GLES30.glGetUniformLocation(program, "uMvp")
        uLight = GLES30.glGetUniformLocation(program, "uLight")
        uMinH = GLES30.glGetUniformLocation(program, "uMinH")
        uMaxH = GLES30.glGetUniformLocation(program, "uMaxH")

        val vertices = terrain.buildVertices()
        val indices = terrain.buildIndices()
        indexCount = indices.size

        val vb = ByteBuffer.allocateDirect(vertices.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        vb.put(vertices).position(0)
        val ib = ByteBuffer.allocateDirect(indices.size * 2).order(ByteOrder.nativeOrder()).asShortBuffer()
        ib.put(indices).position(0)

        val bufs = IntArray(2)
        GLES30.glGenBuffers(2, bufs, 0)
        vbo = bufs[0]
        ibo = bufs[1]

        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo)
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, vertices.size * 4, vb, GLES30.GL_DYNAMIC_DRAW)
        GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, ibo)
        GLES30.glBufferData(GLES30.GL_ELEMENT_ARRAY_BUFFER, indices.size * 2, ib, GLES30.GL_STATIC_DRAW)
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES30.glViewport(0, 0, width, height)
        viewW = width
        viewH = height
        val aspect = width.toFloat() / height.toFloat()
        Matrix.perspectiveM(proj, 0, 55f, aspect, 0.1f, 300f)
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
    private val walkSpeed = 3f

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

    // Buffers reutilizados: al esculpir se re-sube la malla en cada frame.
    private val vertArr = FloatArray(terrain.n * terrain.n * 6)
    private val vertBuf = ByteBuffer.allocateDirect(vertArr.size * 4)
        .order(ByteOrder.nativeOrder()).asFloatBuffer()

    private fun uploadVertices() {
        terrain.buildVertices(vertArr)
        vertBuf.clear()
        vertBuf.put(vertArr)
        vertBuf.position(0)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo)
        GLES30.glBufferSubData(GLES30.GL_ARRAY_BUFFER, 0, vertArr.size * 4, vertBuf)
    }

    // ---------- Esculpir ----------

    @Volatile var sculptEnabled = false
    /** Dedo presionado en modo esculpir; sculptX/Y son coordenadas de pantalla en píxeles. */
    @Volatile var sculptActive = false
    @Volatile var sculptX = 0f
    @Volatile var sculptY = 0f
    @Volatile var tool = Terrain.TOOL_RAISE
    @Volatile var brushRadius = 2f
    @Volatile var brushStrength = 1.5f

    private var viewW = 1
    private var viewH = 1
    private var sculptLastNanos = 0L
    private var strokeStarted = false
    private var flattenTarget = 0f

    private fun sculptStep() {
        if (!(sculptEnabled && sculptActive && !walkMode)) {
            sculptLastNanos = 0L
            strokeStarted = false
            return
        }
        val now = System.nanoTime()
        val dt = if (sculptLastNanos == 0L) 0.016f else ((now - sculptLastNanos) / 1e9f).coerceAtMost(0.05f)
        sculptLastNanos = now

        val ray = screenRay(sculptX, sculptY) ?: return
        val hit = terrain.raycast(ray[0], ray[1], ray[2], ray[3], ray[4], ray[5]) ?: return
        if (!strokeStarted) {
            flattenTarget = hit[1]
            strokeStarted = true
        }
        terrain.brush(tool, hit[0], hit[2], brushRadius, brushStrength, dt, flattenTarget)
        dirty = true
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

    override fun onDrawFrame(gl: GL10?) {
        sculptStep()
        if (dirty) {
            dirty = false
            uploadVertices()
        }
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
