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

    private val terrain = Terrain(n = 128, size = 20f)

    private var program = 0
    private var uMvp = 0
    private var uLight = 0
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
        out vec4 outColor;
        void main() {
            float diff = max(dot(normalize(vNormal), normalize(uLight)), 0.0);
            float light = 0.25 + 0.75 * diff;
            vec3 low  = vec3(0.20, 0.45, 0.20);
            vec3 mid  = vec3(0.55, 0.50, 0.30);
            vec3 high = vec3(0.90, 0.90, 0.92);
            float t = clamp((vHeight + 2.0) / 4.0, 0.0, 1.0);
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
        val aspect = width.toFloat() / height.toFloat()
        Matrix.perspectiveM(proj, 0, 55f, aspect, 0.1f, 300f)
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT or GLES30.GL_DEPTH_BUFFER_BIT)

        val yawRad = Math.toRadians(yaw.toDouble())
        val pitchRad = Math.toRadians(pitch.toDouble())
        val d = dist
        val ex = (d * cos(pitchRad) * sin(yawRad)).toFloat()
        val ey = (d * sin(pitchRad)).toFloat()
        val ez = (d * cos(pitchRad) * cos(yawRad)).toFloat()
        Matrix.setLookAtM(view, 0, ex, ey, ez, 0f, 0f, 0f, 0f, 1f, 0f)
        Matrix.multiplyMM(mvp, 0, proj, 0, view, 0)

        GLES30.glUseProgram(program)
        GLES30.glUniformMatrix4fv(uMvp, 1, false, mvp, 0)
        GLES30.glUniform3f(uLight, 0.5f, 1.0f, 0.3f)

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
