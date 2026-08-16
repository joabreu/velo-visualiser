package com.lowlatency.visualizer

import android.graphics.SurfaceTexture
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.projection.MediaProjection
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.os.Handler
import android.os.HandlerThread
import android.util.DisplayMetrics
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.atomic.AtomicBoolean

/**
 * MediaProjection capture path modelled after Android TV ambient-light apps:
 * the VirtualDisplay writes directly into a SurfaceTexture backed by an external
 * GLES texture. Only a tiny 4x3 colour reduction is read back to the CPU.
 *
 * This avoids ImageReader's full RGBA CPU-frame path while preserving the entire
 * projected display and its SurfaceTexture transform.
 */
class ScreenTextureCapture(
    private val projection: MediaProjection,
    private val metrics: DisplayMetrics,
    private val onColours: (FloatArray) -> Unit
) {
    companion object {
        private const val TAG = "ScreenTextureCapture"
        private const val OUT_W = 48
        private const val OUT_H = 36
        private const val ZONES = 12
        private const val COMPONENTS = ZONES * 3

        private val VERTICES = floatArrayOf(
            -1f, -1f, 0f, 1f,
             1f, -1f, 1f, 1f,
            -1f,  1f, 0f, 0f,
             1f,  1f, 1f, 0f
        )

        private const val VERTEX_SHADER = """
            attribute vec2 aPosition;
            attribute vec2 aTexCoord;
            uniform mat4 uTexMatrix;
            varying vec2 vTexCoord;
            void main() {
                gl_Position = vec4(aPosition, 0.0, 1.0);
                vTexCoord = (uTexMatrix * vec4(aTexCoord, 0.0, 1.0)).xy;
            }
        """

        private const val FRAGMENT_SHADER = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            uniform samplerExternalOES uTexture;
            varying vec2 vTexCoord;
            void main() {
                gl_FragColor = texture2D(uTexture, vTexCoord);
            }
        """
    }

    private val running = AtomicBoolean(false)
    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var surfaceTexture: SurfaceTexture? = null
    private var surface: Surface? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE
    private var textureId = 0
    private var program = 0
    private var framebuffer = 0
    private var outputTexture = 0
    private var frameBuffer: ByteBuffer? = null
    private var transform = FloatArray(16)

    fun start() {
        if (!running.compareAndSet(false, true)) return
        thread = HandlerThread("VeloScreenTexture").also { it.start() }
        handler = Handler(thread!!.looper)
        handler!!.post { startOnGlThread() }
    }

    private fun startOnGlThread() {
        try {
            initEgl()
            createExternalTexture()

            val st = SurfaceTexture(textureId)
            surfaceTexture = st

            // Size producer buffers to the complete physical display before
            // handing the Surface to MediaProjection.
            val captureWidth = metrics.widthPixels.coerceAtLeast(320)
            val captureHeight = metrics.heightPixels.coerceAtLeast(180)
            st.setDefaultBufferSize(captureWidth, captureHeight)

            st.setOnFrameAvailableListener({ handler?.post { consumeFrame() } }, handler)
            surface = Surface(st)

            virtualDisplay = projection.createVirtualDisplay(
                "VeloScreenSync",
                metrics.widthPixels.coerceAtLeast(320),
                metrics.heightPixels.coerceAtLeast(180),
                metrics.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                surface,
                null,
                handler
            )
        } catch (t: Throwable) {
            android.util.Log.e(TAG, "Failed to start GPU screen capture", t)
            stopOnGlThread()
        }
    }

    private fun initEgl() {
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        check(eglDisplay != EGL14.EGL_NO_DISPLAY) { "No EGL display" }
        val version = IntArray(2)
        check(EGL14.eglInitialize(eglDisplay, version, 0, version, 1)) { "eglInitialize failed" }

        val configAttrs = intArrayOf(
            EGL14.EGL_RENDERABLE_TYPE, 4,
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
            EGL14.EGL_NONE
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val count = IntArray(1)
        check(EGL14.eglChooseConfig(eglDisplay, configAttrs, 0, configs, 0, 1, count, 0)) { "eglChooseConfig failed" }
        val config = configs[0] ?: error("No EGL config")
        val contextAttrs = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE)
        eglContext = EGL14.eglCreateContext(eglDisplay, config, EGL14.EGL_NO_CONTEXT, contextAttrs, 0)
        check(eglContext != EGL14.EGL_NO_CONTEXT) { "eglCreateContext failed" }
        val surfaceAttrs = intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE)
        eglSurface = EGL14.eglCreatePbufferSurface(eglDisplay, config, surfaceAttrs, 0)
        check(eglSurface != EGL14.EGL_NO_SURFACE) { "eglCreatePbufferSurface failed" }
        check(EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) { "eglMakeCurrent failed" }

        program = createProgram(VERTEX_SHADER, FRAGMENT_SHADER)
        framebuffer = genFramebuffer()
        outputTexture = genOutputTexture()
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, framebuffer)
        GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, outputTexture, 0)
        check(GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER) == GLES20.GL_FRAMEBUFFER_COMPLETE) { "FBO incomplete" }
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        frameBuffer = ByteBuffer.allocateDirect(OUT_W * OUT_H * 4).order(ByteOrder.nativeOrder())
    }

    private fun createExternalTexture() {
        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        textureId = ids[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
    }

    private fun consumeFrame() {
        if (!running.get()) return
        val st = surfaceTexture ?: return
        try {
            st.updateTexImage()
            st.getTransformMatrix(transform)

            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, framebuffer)
            GLES20.glViewport(0, 0, OUT_W, OUT_H)
            GLES20.glClearColor(0f, 0f, 0f, 1f)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            GLES20.glUseProgram(program)

            val position = GLES20.glGetAttribLocation(program, "aPosition")
            val texCoord = GLES20.glGetAttribLocation(program, "aTexCoord")
            val matrix = GLES20.glGetUniformLocation(program, "uTexMatrix")
            val texture = GLES20.glGetUniformLocation(program, "uTexture")

            val vertices = ByteBuffer.allocateDirect(VERTICES.size * 4)
                .order(ByteOrder.nativeOrder()).asFloatBuffer()
            vertices.put(VERTICES).position(0)
            GLES20.glEnableVertexAttribArray(position)
            GLES20.glVertexAttribPointer(position, 2, GLES20.GL_FLOAT, false, 16, vertices)
            vertices.position(2)
            GLES20.glEnableVertexAttribArray(texCoord)
            GLES20.glVertexAttribPointer(texCoord, 2, GLES20.GL_FLOAT, false, 16, vertices)
            GLES20.glUniformMatrix4fv(matrix, 1, false, transform, 0)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
            GLES20.glUniform1i(texture, 0)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            GLES20.glFinish()

            val pixels = frameBuffer ?: return
            pixels.clear()
            GLES20.glReadPixels(0, 0, OUT_W, OUT_H, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, pixels)
            pixels.rewind()

            val sums = FloatArray(COMPONENTS)
            for (y in 0 until OUT_H) {
                for (x in 0 until OUT_W) {
                    val r = pixels.get().toInt() and 0xff
                    val g = pixels.get().toInt() and 0xff
                    val b = pixels.get().toInt() and 0xff
                    val col = x * 4 / OUT_W
                    val row = y * 3 / OUT_H
                    val p = (row * 4 + col) * 3
                    sums[p] += r / 255f
                    sums[p + 1] += g / 255f
                    sums[p + 2] += b / 255f
                }
            }
            val inv = 1f / (OUT_W * OUT_H / 12f)
            for (i in sums.indices) sums[i] *= inv
            onColours(sums)
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        } catch (t: Throwable) {
            android.util.Log.w(TAG, "GPU screen frame failed", t)
        }
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) return
        handler?.post { stopOnGlThread() }
        thread?.quitSafely()
        thread = null
        handler = null
    }

    private fun stopOnGlThread() {
        virtualDisplay?.runCatching { release() }
        virtualDisplay = null
        surface?.runCatching { release() }
        surface = null
        surfaceTexture?.runCatching { release() }
        surfaceTexture = null
        if (outputTexture != 0) GLES20.glDeleteTextures(1, intArrayOf(outputTexture), 0)
        if (textureId != 0) GLES20.glDeleteTextures(1, intArrayOf(textureId), 0)
        if (framebuffer != 0) GLES20.glDeleteFramebuffers(1, intArrayOf(framebuffer), 0)
        outputTexture = 0
        textureId = 0
        framebuffer = 0
        if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
            EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            if (eglSurface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(eglDisplay, eglSurface)
            if (eglContext != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(eglDisplay, eglContext)
            EGL14.eglTerminate(eglDisplay)
        }
        eglDisplay = EGL14.EGL_NO_DISPLAY
        eglSurface = EGL14.EGL_NO_SURFACE
        eglContext = EGL14.EGL_NO_CONTEXT
    }

    private fun genFramebuffer(): Int {
        val ids = IntArray(1)
        GLES20.glGenFramebuffers(1, ids, 0)
        return ids[0]
    }

    private fun genOutputTexture(): Int {
        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, ids[0])
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_NEAREST)
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, OUT_W, OUT_H, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null)
        return ids[0]
    }

    private fun createProgram(vertex: String, fragment: String): Int {
        fun compile(type: Int, source: String): Int {
            val shader = GLES20.glCreateShader(type)
            GLES20.glShaderSource(shader, source)
            GLES20.glCompileShader(shader)
            val ok = IntArray(1)
            GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, ok, 0)
            if (ok[0] == 0) error(GLES20.glGetShaderInfoLog(shader))
            return shader
        }
        val vs = compile(GLES20.GL_VERTEX_SHADER, vertex)
        val fs = compile(GLES20.GL_FRAGMENT_SHADER, fragment)
        val p = GLES20.glCreateProgram()
        GLES20.glAttachShader(p, vs)
        GLES20.glAttachShader(p, fs)
        GLES20.glLinkProgram(p)
        val ok = IntArray(1)
        GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, ok, 0)
        GLES20.glDeleteShader(vs)
        GLES20.glDeleteShader(fs)
        if (ok[0] == 0) error(GLES20.glGetProgramInfoLog(p))
        return p
    }
}
