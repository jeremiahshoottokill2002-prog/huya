package com.zaza.cloudycam

import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.opengles.GL10

/**
 * Renders the live camera feed with GPU lens/color effects.
 *
 * KEY FEATURE: dual-pass rendering. Every frame is drawn twice:
 *   1. To the screen (the preview you see)
 *   2. To the MediaRecorder's input surface (the video being recorded)
 * This means the recorded file contains EXACTLY what you see,
 * effects included.
 *
 * Effect modes:
 *   0 Normal   1 Fisheye   2 Concave   3 Wide
 *   4 Mirror   5 B&W       6 Crisp     7 Smooth
 */
class CameraRenderer(
    private val onSurfaceReady: (SurfaceTexture) -> Unit,
    private val requestRender: () -> Unit
) : GLSurfaceView.Renderer {

    @Volatile
    var effectMode: Int = 0

    var surfaceTexture: SurfaceTexture? = null
        private set

    // --- Recording state (set from UI thread, consumed on GL thread) ---
    @Volatile
    private var recordingEnabled = false
    @Volatile
    private var pendingEncoderSurface: Surface? = null
    private var encoderEglSurface: EGLSurface? = null
    private var videoWidth = 0
    private var videoHeight = 0

    private var viewWidth = 0
    private var viewHeight = 0

    private var textureId = 0
    private var program = 0
    private var aPosLoc = 0
    private var aTexLoc = 0
    private var uTexMatrixLoc = 0
    private var uModeLoc = 0
    private var uTimeLoc = 0
    private val texMatrix = FloatArray(16)

    private val vertexBuffer: FloatBuffer = floatBufferOf(
        -1f, -1f,
        1f, -1f,
        -1f, 1f,
        1f, 1f
    )

    private val texCoordBuffer: FloatBuffer = floatBufferOf(
        0f, 0f,
        1f, 0f,
        0f, 1f,
        1f, 1f
    )

    /** Call from UI thread to start mirroring frames into the recorder. */
    fun startRecording(encoderSurface: Surface, width: Int, height: Int) {
        pendingEncoderSurface = encoderSurface
        videoWidth = width
        videoHeight = height
        recordingEnabled = true
    }

    /** Call from UI thread to stop mirroring frames. */
    fun stopRecording() {
        recordingEnabled = false
    }

    override fun onSurfaceCreated(gl: GL10?, config: javax.microedition.khronos.egl.EGLConfig?) {
        val tex = IntArray(1)
        GLES20.glGenTextures(1, tex, 0)
        textureId = tex[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR
        )
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR
        )
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE
        )
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE
        )

        val st = SurfaceTexture(textureId)
        st.setOnFrameAvailableListener { requestRender() }
        surfaceTexture = st

        program = buildProgram(VERTEX_SHADER, FRAGMENT_SHADER)
        aPosLoc = GLES20.glGetAttribLocation(program, "aPosition")
        aTexLoc = GLES20.glGetAttribLocation(program, "aTexCoord")
        uTexMatrixLoc = GLES20.glGetUniformLocation(program, "uTexMatrix")
        uModeLoc = GLES20.glGetUniformLocation(program, "uMode")
        uTimeLoc = GLES20.glGetUniformLocation(program, "uTime")

        GLES20.glClearColor(0f, 0f, 0f, 1f)

        onSurfaceReady(st)
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        viewWidth = width
        viewHeight = height
        GLES20.glViewport(0, 0, width, height)
    }

    override fun onDrawFrame(gl: GL10?) {
        val st = surfaceTexture ?: return
        st.updateTexImage()
        st.getTransformMatrix(texMatrix)

        // ---- Pass 1: draw to the screen ----
        GLES20.glViewport(0, 0, viewWidth, viewHeight)
        drawQuad()

        // ---- Pass 2: draw to the recorder (if recording) ----
        if (recordingEnabled) {
            drawToEncoder()
        } else {
            releaseEncoderSurfaceIfNeeded()
        }
    }

    private fun drawToEncoder() {
        val display = EGL14.eglGetCurrentDisplay()
        val context = EGL14.eglGetCurrentContext()
        val prevDraw = EGL14.eglGetCurrentSurface(EGL14.EGL_DRAW)
        val prevRead = EGL14.eglGetCurrentSurface(EGL14.EGL_READ)

        if (encoderEglSurface == null) {
            val target = pendingEncoderSurface ?: return
            val config = chooseRecordableConfig(display) ?: return
            encoderEglSurface = EGL14.eglCreateWindowSurface(
                display, config, target, intArrayOf(EGL14.EGL_NONE), 0
            )
            if (encoderEglSurface == EGL14.EGL_NO_SURFACE) {
                encoderEglSurface = null
                return
            }
        }

        val encSurface = encoderEglSurface ?: return
        if (!EGL14.eglMakeCurrent(display, encSurface, encSurface, context)) return

        GLES20.glViewport(0, 0, videoWidth, videoHeight)
        drawQuad()
        EGLExt.eglPresentationTimeANDROID(display, encSurface, System.nanoTime())
        EGL14.eglSwapBuffers(display, encSurface)

        // Restore the screen surface
        EGL14.eglMakeCurrent(display, prevDraw, prevRead, context)
        GLES20.glViewport(0, 0, viewWidth, viewHeight)
    }

    private fun releaseEncoderSurfaceIfNeeded() {
        val surface = encoderEglSurface ?: return
        val display = EGL14.eglGetCurrentDisplay()
        EGL14.eglDestroySurface(display, surface)
        encoderEglSurface = null
        pendingEncoderSurface = null
    }

    private fun chooseRecordableConfig(display: android.opengl.EGLDisplay): EGLConfig? {
        val EGL_RECORDABLE_ANDROID = 0x3142
        val attribs = intArrayOf(
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL_RECORDABLE_ANDROID, 1,
            EGL14.EGL_NONE
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val numConfigs = IntArray(1)
        if (!EGL14.eglChooseConfig(display, attribs, 0, configs, 0, 1, numConfigs, 0)) {
            return null
        }
        if (numConfigs[0] <= 0) return null
        return configs[0]
    }

    private fun drawQuad() {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        GLES20.glUseProgram(program)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)

        GLES20.glUniformMatrix4fv(uTexMatrixLoc, 1, false, texMatrix, 0)
        GLES20.glUniform1i(uModeLoc, effectMode)
        GLES20.glUniform1f(uTimeLoc, (System.nanoTime() % 100_000_000_000L) / 1_000_000_000f)

        GLES20.glEnableVertexAttribArray(aPosLoc)
        GLES20.glVertexAttribPointer(aPosLoc, 2, GLES20.GL_FLOAT, false, 0, vertexBuffer)
        GLES20.glEnableVertexAttribArray(aTexLoc)
        GLES20.glVertexAttribPointer(aTexLoc, 2, GLES20.GL_FLOAT, false, 0, texCoordBuffer)

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        GLES20.glDisableVertexAttribArray(aPosLoc)
        GLES20.glDisableVertexAttribArray(aTexLoc)
    }

    private fun buildProgram(vertexSrc: String, fragmentSrc: String): Int {
        val vs = compileShader(GLES20.GL_VERTEX_SHADER, vertexSrc)
        val fs = compileShader(GLES20.GL_FRAGMENT_SHADER, fragmentSrc)
        val prog = GLES20.glCreateProgram()
        GLES20.glAttachShader(prog, vs)
        GLES20.glAttachShader(prog, fs)
        GLES20.glLinkProgram(prog)
        val status = IntArray(1)
        GLES20.glGetProgramiv(prog, GLES20.GL_LINK_STATUS, status, 0)
        if (status[0] == 0) {
            val log = GLES20.glGetProgramInfoLog(prog)
            GLES20.glDeleteProgram(prog)
            throw RuntimeException("Shader link failed: $log")
        }
        return prog
    }

    private fun compileShader(type: Int, source: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)
        val status = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
        if (status[0] == 0) {
            val log = GLES20.glGetShaderInfoLog(shader)
            GLES20.glDeleteShader(shader)
            throw RuntimeException("Shader compile failed: $log")
        }
        return shader
    }

    private fun floatBufferOf(vararg values: Float): FloatBuffer =
        ByteBuffer.allocateDirect(values.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .apply { put(values); position(0) }

    companion object {
        private const val VERTEX_SHADER = """
            attribute vec4 aPosition;
            attribute vec4 aTexCoord;
            uniform mat4 uTexMatrix;
            varying vec2 vTexCoord;
            void main() {
                gl_Position = aPosition;
                vTexCoord = (uTexMatrix * aTexCoord).xy;
            }
        """

        private const val FRAGMENT_SHADER = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            uniform samplerExternalOES uTexture;
            uniform int uMode;
            uniform float uTime;
            varying vec2 vTexCoord;

            // 0 Normal  1 Fisheye  2 Concave  3 Wide    4 Mirror
            // 5 B&W     6 Crisp    7 Smooth   8 Glitch  9 VHS
            // 10 Ghost  11 Kaleido 12 Invert  13 Pulse

            float rnd(vec2 p) {
                return fract(sin(dot(p, vec2(12.9898, 78.233))) * 43758.5453);
            }

            void main() {
                vec2 uv = vTexCoord;

                // ---- Lens / geometry stage ----
                if (uMode == 1 || uMode == 2 || uMode == 3) {
                    vec2 c = uv - 0.5;
                    float r2 = dot(c, c);
                    float s = 1.0;
                    if (uMode == 1) s = 1.0 - 0.55 * r2;
                    if (uMode == 2) s = 1.0 + 1.10 * r2;
                    if (uMode == 3) s = 1.0 - 0.25 * r2;
                    uv = 0.5 + c * s;
                }
                if (uMode == 4) {
                    if (uv.x > 0.5) uv.x = 1.0 - uv.x;
                }
                if (uMode == 11) {
                    // Kaleido: 4-way mirror
                    uv = vec2(0.5) - abs(uv - vec2(0.5));
                    uv = vec2(0.5) + (uv - vec2(0.5)) * 1.6;
                }
                if (uMode == 8) {
                    // Glitch: jittering horizontal slice displacement
                    float band = floor(uv.y * 24.0 + uTime * 9.0);
                    float n = rnd(vec2(band, floor(uTime * 9.0)));
                    if (n > 0.78) {
                        uv.x += (n - 0.89) * 0.35;
                    }
                }

                if (uv.x < 0.0 || uv.x > 1.0 || uv.y < 0.0 || uv.y > 1.0) {
                    gl_FragColor = vec4(0.0, 0.0, 0.0, 1.0);
                    return;
                }

                vec4 color = texture2D(uTexture, uv);

                // ---- Color / style stage ----
                if (uMode == 5) {
                    float g = dot(color.rgb, vec3(0.299, 0.587, 0.114));
                    g = clamp((g - 0.5) * 1.15 + 0.5, 0.0, 1.0);
                    color = vec4(g, g, g, 1.0);
                }
                if (uMode == 6) {
                    vec2 t = vec2(1.0 / 720.0, 1.0 / 1280.0);
                    vec4 blur =
                        texture2D(uTexture, uv + vec2( t.x, 0.0)) +
                        texture2D(uTexture, uv + vec2(-t.x, 0.0)) +
                        texture2D(uTexture, uv + vec2(0.0,  t.y)) +
                        texture2D(uTexture, uv + vec2(0.0, -t.y));
                    blur *= 0.25;
                    color = clamp(color + (color - blur) * 0.9, 0.0, 1.0);
                }
                if (uMode == 7) {
                    vec2 t = vec2(1.0 / 720.0, 1.0 / 1280.0);
                    vec4 sum = vec4(0.0);
                    for (int x = -1; x <= 1; x++) {
                        for (int y = -1; y <= 1; y++) {
                            sum += texture2D(uTexture, uv + vec2(float(x) * t.x, float(y) * t.y));
                        }
                    }
                    color = sum / 9.0;
                }
                if (uMode == 8) {
                    // Glitch: RGB split
                    float off = 0.006 + 0.004 * sin(uTime * 13.0);
                    color.r = texture2D(uTexture, uv + vec2(off, 0.0)).r;
                    color.b = texture2D(uTexture, uv - vec2(off, 0.0)).b;
                }
                if (uMode == 9) {
                    // VHS: scanlines + grain + chroma shift + warm fade
                    float scan = 0.88 + 0.12 * sin(uv.y * 900.0 + uTime * 30.0);
                    float grain = (rnd(uv * uTime) - 0.5) * 0.10;
                    color.r = texture2D(uTexture, uv + vec2(0.0025, 0.0)).r;
                    color.b = texture2D(uTexture, uv - vec2(0.0025, 0.0)).b;
                    color.rgb = color.rgb * scan + grain;
                    color.rgb = mix(color.rgb, vec3(dot(color.rgb, vec3(0.3, 0.59, 0.11))), 0.25);
                    color.rgb *= vec3(1.05, 1.0, 0.92);
                }
                if (uMode == 10) {
                    // Ghost: drifting translucent double image
                    vec2 drift = vec2(0.018 * sin(uTime * 1.8), 0.012 * cos(uTime * 1.3));
                    vec4 ghost = texture2D(uTexture, clamp(uv + drift, 0.0, 1.0));
                    color.rgb = max(color.rgb * 0.92, ghost.rgb * 0.75);
                }
                if (uMode == 12) {
                    color.rgb = 1.0 - color.rgb;
                }
                if (uMode == 13) {
                    // Pulse: rhythmic brightness beat (~120 BPM)
                    float beat = 0.85 + 0.30 * pow(abs(sin(uTime * 6.283 * 1.0)), 8.0);
                    color.rgb *= beat;
                }

                gl_FragColor = clamp(color, 0.0, 1.0);
            }
        """
    }
}
