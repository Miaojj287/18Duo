package com.jake.duolauncher

import android.content.Context
import android.content.res.AssetManager
import android.animation.ValueAnimator
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ColorSpace
import android.graphics.PixelFormat
import android.hardware.HardwareBuffer
import android.media.Image
import android.media.ImageReader
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES30
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Choreographer
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.ArrayDeque

/** One rendered dunes frame at the size of the window that asked for it. [image] is either a
 * HardwareBuffer from the motion stream (valid while [DuneLive] holds its Image) or, at rest, a
 * stable software copy ([stable]). */
internal class DuneFrame(val bitmap: Bitmap, val fold: Float, val serial: Long, val stable: Boolean) {
    val image: ImageBitmap = bitmap.asImageBitmap()
}

/**
 * Process-wide live dunes (Core.35). Main thread: Apple's spring ([DuneFoldMotion]) stepped on
 * Choreographer frames while the hinge target differs, the window sizes that show the dunes, and
 * the frame state Compose draws. A background GL thread ([DuneGlEngine]) renders Apple's scene:
 * during motion into an ImageReader (no copies), at rest once more into a readable bitmap that
 * outlives the GL resources, which are dropped a few seconds after the dunes settle.
 */
internal object DuneLive {
    private const val TAG = "DuoDunes"
    private const val RELEASE_AFTER_REST_MS = 3_000L
    private const val SHUTDOWN_AFTER_DETACH_MS = 15_000L

    /** The newest frame; read it in the draw phase. */
    val frame = mutableStateOf<DuneFrame?>(null)
    /** The spring's current fold, for fallbacks drawn before the first frame. */
    val fold = mutableStateOf(1f)

    private val main = Handler(Looper.getMainLooper())
    private val motion = DuneFoldMotion(1f)
    private var hingeKnown = false
    private var ticking = false
    private var lastFrameNs = 0L
    private var serial = 0L
    private var acceptedSerial = -1L
    private val held = ArrayDeque<Pair<Long, Image>>()
    private val clients = LinkedHashMap<Any, Long>()
    private var width = 0
    private var height = 0
    private var engine: DuneGlEngine? = null
    private var appContext: Context? = null
    @Volatile var failed = false
        private set

    private val tick = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            val dt = if (lastFrameNs == 0L) 0.0 else (frameTimeNanos - lastFrameNs) / 1e9
            lastFrameNs = frameTimeNanos
            if (motion.advance(dt)) { fold.value = motion.value; render(rest = false) }
            if (motion.moving) Choreographer.getInstance().postFrameCallback(this)
            else { ticking = false; lastFrameNs = 0L; render(rest = true) }
        }
    }
    private val shutdown = Runnable { if (clients.isEmpty()) { engine?.shutdown(); engine = null } }

    fun bind(context: Context) { if (appContext == null) appContext = context.applicationContext }

    /** Apple's Hinge.position from the sensor ([dunesHinge]). The first value after process start,
     * or after the sensors stopped (NaN), is snapped to: a Home recreated or brought back does not
     * replay an opening it never showed. With system animations off every value is snapped to. */
    fun setHinge(hinge: Float) {
        if (hinge.isNaN()) { hingeKnown = false; return }
        val target = DuneFoldMath.foldTarget(hinge)
        if (!hingeKnown || !ValueAnimator.areAnimatorsEnabled()) {
            if (target == motion.target && !motion.moving && (hingeKnown || frame.value?.let { it.stable && it.fold == target } == true)) {
                hingeKnown = true; return
            }
            if (ticking) { Choreographer.getInstance().removeFrameCallback(tick); ticking = false; lastFrameNs = 0L }
            hingeKnown = true; motion.snap(target); fold.value = target
            render(rest = true); return
        }
        if (target == motion.target) return
        motion.retarget(target)
        if (!ticking && motion.moving) {
            ticking = true; lastFrameNs = 0L; motion.primeStep()
            Choreographer.getInstance().postFrameCallback(tick)
        }
    }

    /** A full-window dunes view of [w] × [h] px; the newest one decides the render size. */
    fun attach(token: Any, w: Int, h: Int) {
        if (w <= 0 || h <= 0) return
        main.removeCallbacks(shutdown)
        val size = w.toLong() shl 32 or h.toLong()
        if (clients[token] == size && width == w && height == h) return
        clients.remove(token); clients[token] = size
        if (width != w || height != h) { width = w; height = h; render(rest = !motion.moving) }
    }

    fun detach(token: Any) {
        clients.remove(token) ?: return
        clients.values.lastOrNull()?.let { val w = (it ushr 32).toInt(); val h = it.toInt()
            if (w != width || h != height) { width = w; height = h; render(rest = !motion.moving) } }
        if (clients.isEmpty()) { main.removeCallbacks(shutdown); main.postDelayed(shutdown, SHUTDOWN_AFTER_DETACH_MS) }
    }

    /** A stable bitmap of the dunes at rest for the window size, for consumers that keep a bitmap
     * (the switcher backdrop, split view): never a frame of the motion stream. */
    fun restBitmap(): Bitmap? = frame.value?.takeIf { it.stable && !it.bitmap.isRecycled }?.bitmap

    private fun render(rest: Boolean) {
        if (failed || width <= 0 || height <= 0 || !hingeKnown) return
        val context = appContext ?: return
        val e = engine ?: DuneGlEngine(context.assets, ::deliver, ::fail).also { engine = it }
        e.request(motion.value, width, height, rest, ++serial)
    }

    private fun deliver(next: DuneFrame, image: Image?) = main.post {
        if (next.serial < acceptedSerial) { image?.closeQuietly(); return@post }
        acceptedSerial = next.serial
        frame.value = next
        if (image != null) {
            held.addLast(next.serial to image)
            // The view may still be drawing the previous buffer: keep two, return the rest.
            while (held.size > 2) held.removeFirst().second.closeQuietly()
        } else {
            // A stable rest frame replaces the stream; its buffers go back and the GL side trims.
            while (held.isNotEmpty()) held.removeFirst().second.closeQuietly()
            engine?.trimLater(RELEASE_AFTER_REST_MS)
        }
    }

    private fun fail(error: Throwable) = main.post {
        Log.w(TAG, "live dunes disabled", error)
        failed = true
        engine?.shutdown(); engine = null
        while (held.isNotEmpty()) held.removeFirst().second.closeQuietly()
        if (frame.value?.stable == false) frame.value = null
    }
}

/** An Image whose reader was already replaced (a resize mid-motion) may refuse to close. */
private fun Image.closeQuietly() { runCatching { close() } }

/** The GL side of [DuneLive]: one thread, one ES 3.0 context, Apple's scene. */
private class DuneGlEngine(
    private val assets: AssetManager,
    private val deliver: (DuneFrame, Image?) -> Unit,
    private val fail: (Throwable) -> Unit,
) {
    private val thread = HandlerThread("duo-dunes").apply { start() }
    private val handler = Handler(thread.looper)
    private class Request(val fold: Float, val width: Int, val height: Int, val rest: Boolean, val serial: Long)
    @Volatile private var pending: Request? = null
    private var scene: DuneGlScene? = null
    private var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var context: EGLContext = EGL14.EGL_NO_CONTEXT
    private var config: EGLConfig? = null
    private var pbuffer: EGLSurface = EGL14.EGL_NO_SURFACE
    private var reader: ImageReader? = null
    private var window: EGLSurface = EGL14.EGL_NO_SURFACE
    private var readerWidth = 0
    private var readerHeight = 0
    private val folds = HashMap<Long, Float>()
    private var dead = false
    private val trim = Runnable { scene?.releaseTargets(); releaseReader() }
    private val drain = Runnable {
        val request = pending ?: return@Runnable
        pending = null
        try { draw(request) } catch (t: Throwable) { dead = true; fail(t) }
    }

    fun request(fold: Float, width: Int, height: Int, rest: Boolean, serial: Long) {
        if (dead) return
        val coalesced = pending != null
        pending = Request(fold, width, height, rest, serial)
        handler.removeCallbacks(trim)
        if (!coalesced) handler.post(drain)
    }

    fun trimLater(delayMs: Long) { handler.removeCallbacks(trim); handler.postDelayed(trim, delayMs) }

    fun shutdown() {
        dead = true
        handler.removeCallbacksAndMessages(null)
        handler.post {
            runCatching { scene?.release() }; scene = null
            releaseReader()
            if (display != EGL14.EGL_NO_DISPLAY) {
                EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                if (pbuffer != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, pbuffer)
                if (context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, context)
                EGL14.eglReleaseThread(); EGL14.eglTerminate(display)
            }
            display = EGL14.EGL_NO_DISPLAY; context = EGL14.EGL_NO_CONTEXT; pbuffer = EGL14.EGL_NO_SURFACE
            thread.quitSafely()
        }
    }

    private fun ensureEgl() {
        if (context != EGL14.EGL_NO_CONTEXT) return
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        check(display != EGL14.EGL_NO_DISPLAY) { "no EGL display" }
        val version = IntArray(2)
        check(EGL14.eglInitialize(display, version, 0, version, 1)) { "eglInitialize" }
        val attributes = intArrayOf(
            EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGLExt.EGL_OPENGL_ES3_BIT_KHR,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT or EGL14.EGL_PBUFFER_BIT, EGL14.EGL_NONE)
        val configs = arrayOfNulls<EGLConfig>(1); val count = IntArray(1)
        check(EGL14.eglChooseConfig(display, attributes, 0, configs, 0, 1, count, 0) && count[0] > 0) { "no ES3 RGBA8 config" }
        config = configs[0]
        context = EGL14.eglCreateContext(display, config, EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE), 0)
        check(context != EGL14.EGL_NO_CONTEXT) { "eglCreateContext ${EGL14.eglGetError()}" }
        pbuffer = EGL14.eglCreatePbufferSurface(display, config, intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0)
        check(EGL14.eglMakeCurrent(display, pbuffer, pbuffer, context)) { "eglMakeCurrent" }
    }

    private fun ensureReader(width: Int, height: Int) {
        if (reader != null && readerWidth == width && readerHeight == height) return
        releaseReader()
        val next = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 4,
            HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE or HardwareBuffer.USAGE_GPU_COLOR_OUTPUT)
        next.setOnImageAvailableListener({ r -> onImage(r) }, handler)
        val surface = EGL14.eglCreateWindowSurface(display, config, next.surface, intArrayOf(EGL14.EGL_NONE), 0)
        check(surface != EGL14.EGL_NO_SURFACE) { "eglCreateWindowSurface ${EGL14.eglGetError()}" }
        reader = next; window = surface; readerWidth = width; readerHeight = height
    }

    private fun releaseReader() {
        if (window != EGL14.EGL_NO_SURFACE) {
            EGL14.eglMakeCurrent(display, pbuffer, pbuffer, context)
            EGL14.eglDestroySurface(display, window)
        }
        window = EGL14.EGL_NO_SURFACE
        // Images already handed to DuneLive stay valid until it closes them.
        reader?.close(); reader = null; readerWidth = 0; readerHeight = 0
        folds.clear()
    }

    private fun draw(request: Request) {
        ensureEgl()
        val started = SystemClock.elapsedRealtimeNanos()
        val s = scene ?: DuneGlScene(assets).also { it.create(); scene = it }
        if (request.rest) {
            EGL14.eglMakeCurrent(display, pbuffer, pbuffer, context)
            val bitmap = s.renderToBitmap(request.fold, request.width, request.height)
            deliver(DuneFrame(bitmap, request.fold, request.serial, stable = true), null)
        } else {
            ensureReader(request.width, request.height)
            check(EGL14.eglMakeCurrent(display, window, window, context)) { "eglMakeCurrent window" }
            s.render(request.fold, request.width, request.height, toWindow = true)
            folds[request.serial] = request.fold
            EGLExt.eglPresentationTimeANDROID(display, window, request.serial)
            check(EGL14.eglSwapBuffers(display, window)) { "eglSwapBuffers ${EGL14.eglGetError()}" }
        }
        if (Log.isLoggable("DuoDunes", Log.DEBUG)) {
            // Diagnostics only (setprop log.tag.DuoDunes DEBUG): wait for the GPU so the time is the frame's.
            GLES30.glFinish()
            Log.d("DuoDunes", "frame ${request.serial} fold=${request.fold} " +
            "${request.width}x${request.height} rest=${request.rest} ${(SystemClock.elapsedRealtimeNanos() - started) / 1000} us")
        }
    }

    private fun onImage(r: ImageReader) {
        val image = runCatching { r.acquireLatestImage() }.getOrNull() ?: return
        val buffer = image.hardwareBuffer
        val bitmap = buffer?.let { Bitmap.wrapHardwareBuffer(it, ColorSpace.get(ColorSpace.Named.SRGB)) }
        buffer?.close()
        if (bitmap == null) { image.close(); return }
        val serial = image.timestamp
        val fold = folds.remove(serial) ?: return image.close()
        folds.keys.removeAll { it < serial }
        deliver(DuneFrame(bitmap, fold, serial, stable = false), image)
    }
}

/** Apple's private wallpaper scene on GLES 3.0: the site's meshes, LUTs, textures and compiled
 * ESSL 3.00 programs (assets/dunes, evidence/r19/tools/convert_dunes.py), drawn as three.js does,
 * into a 4× MSAA sRGB target (Apple: MSAA 4), resolved and encoded once. */
internal class DuneGlScene(private val assets: AssetManager) {
    private class Texture(val id: Int, val uniform: String)
    private class Draw(
        val program: Int, val vao: Int, val buffers: IntArray, val indexCount: Int,
        val additive: Boolean, val depthTest: Boolean, val depthWrite: Boolean,
        val world: FloatArray, val textures: List<Texture>, val locations: Map<String, Int>,
    )
    private val draws = ArrayList<Draw>()
    private lateinit var track: DuneCameraTrack
    private var encodeProgram = 0
    private var msaaFbo = 0
    private var msaaColor = 0
    private var msaaDepth = 0
    private var resolveFbo = 0
    private var resolveTexture = 0
    private var outFbo = 0
    private var outTexture = 0
    private var targetWidth = 0
    private var targetHeight = 0
    private val camera = FloatArray(DuneCameraTrack.SAMPLE_FLOATS)
    private val projection = FloatArray(16)
    private val view = FloatArray(16)
    private val modelView = FloatArray(16)
    private val normal = FloatArray(9)

    fun create() {
        track = DuneCameraTrack.parse(read("camera.bin"))
        val manifest = JSONObject(String(read("scene.json")))
        val list = manifest.getJSONArray("draws")
        for (i in 0 until list.length()) draws += loadDraw(list.getJSONObject(i))
        encodeProgram = link(ENCODE_VERTEX, ENCODE_FRAGMENT, "encode")
        checkGl("create")
    }

    private fun read(name: String): ByteArray = assets.open("dunes/$name").use { it.readBytes() }

    private fun direct(bytes: ByteArray): ByteBuffer =
        ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder()).apply { put(bytes); flip() }

    /** RGBA8 rows, top row first (texture t = 0), exactly as stored: no premultiplication, no scaling. */
    private fun decodeImage(name: String, w: Int, h: Int): ByteBuffer {
        val options = BitmapFactory.Options().apply {
            inPremultiplied = false; inScaled = false; inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val bitmap = assets.open("dunes/$name").use { BitmapFactory.decodeStream(it, null, options) }
            ?: error("cannot decode $name")
        check(bitmap.width == w && bitmap.height == h) { "$name is ${bitmap.width}x${bitmap.height}, manifest $w x $h" }
        val data = ByteBuffer.allocateDirect(w * h * 4).order(ByteOrder.nativeOrder())
        bitmap.copyPixelsToBuffer(data); bitmap.recycle(); data.flip()
        return data
    }

    private fun loadDraw(entry: JSONObject): Draw {
        val key = entry.getString("key")
        val program = link(String(read("$key.vert")), String(read("$key.frag")), key)
        val mesh = ByteBuffer.wrap(read("$key.mesh")).order(ByteOrder.LITTLE_ENDIAN)
        val vertices = mesh.int; val indices = mesh.int
        fun slice(bytes: Int): ByteBuffer {
            val view = mesh.slice().order(ByteOrder.LITTLE_ENDIAN); view.limit(bytes)
            mesh.position(mesh.position() + bytes)
            return ByteBuffer.allocateDirect(bytes).order(ByteOrder.nativeOrder()).apply { put(view); flip() }
        }
        val position = slice(vertices * 12); val normals = slice(vertices * 12)
        val uv = slice(vertices * 8); val index = slice(indices * 2)
        val vao = IntArray(1); GLES30.glGenVertexArrays(1, vao, 0); GLES30.glBindVertexArray(vao[0])
        val buffers = IntArray(4); GLES30.glGenBuffers(4, buffers, 0)
        for ((slot, data, components) in listOf(Triple(0, position, 3), Triple(1, normals, 3), Triple(2, uv, 2))) {
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, buffers[slot])
            GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, data.remaining(), data, GLES30.GL_STATIC_DRAW)
            GLES30.glEnableVertexAttribArray(slot)
            GLES30.glVertexAttribPointer(slot, components, GLES30.GL_FLOAT, false, 0, 0)
        }
        GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, buffers[3])
        GLES30.glBufferData(GLES30.GL_ELEMENT_ARRAY_BUFFER, index.remaining(), index, GLES30.GL_STATIC_DRAW)
        GLES30.glBindVertexArray(0)
        val textures = ArrayList<Texture>()
        val bindings = entry.getJSONObject("textures")
        for (uniform in bindings.keys()) textures += Texture(loadTexture(bindings.getJSONObject(uniform)), uniform)
        val names = listOf("modelMatrix", "modelViewMatrix", "projectionMatrix", "viewMatrix", "normalMatrix",
            "cameraPosition", "isOrthographic", "uFoldProgress", "uDuneFarMatrix", "uDuneCloseMatrix") + textures.map { it.uniform }
        val locations = names.associateWith { GLES30.glGetUniformLocation(program, it) }
        val world = entry.getJSONArray("world").let { a -> FloatArray(16) { a.getDouble(it).toFloat() } }
        checkGl("load $key")
        return Draw(program, vao[0], buffers, indices, entry.getBoolean("additive"), entry.getBoolean("depthTest"),
            entry.getBoolean("depthWrite"), world, textures, locations)
    }

    private fun wrap(mode: Int) = when (mode) {
        1000 -> GLES30.GL_REPEAT
        1002 -> GLES30.GL_MIRRORED_REPEAT
        else -> GLES30.GL_CLAMP_TO_EDGE
    }

    private fun loadTexture(spec: JSONObject): Int {
        val w = spec.getInt("w"); val h = spec.getInt("h")
        val kind = spec.getString("kind")
        val id = IntArray(1); GLES30.glGenTextures(1, id, 0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, id[0])
        GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 1)
        var mipmapped = false
        when (kind) {
            "half" -> {
                val data = direct(read(spec.getString("file")))
                val rgba = spec.getInt("components") == 4
                GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, if (rgba) GLES30.GL_RGBA16F else GLES30.GL_R16F, w, h, 0,
                    if (rgba) GLES30.GL_RGBA else GLES30.GL_RED, GLES30.GL_HALF_FLOAT, data)
            }
            else -> {
                // Round 20: the photo textures (hills, dunes) are lossless WebP at 3x the site's resolution,
                // sharpened with the owner's wallpaper (evidence/r20/tools/tex_detail.py); decoded straight,
                // never premultiplied. The sky stays raw RGBA.
                val data = if (kind == "srgb8-image") decodeImage(spec.getString("file"), w, h) else direct(read(spec.getString("file")))
                // three.js uploads these sRGB textures as SRGB8_ALPHA8: the shaders sample linear values.
                GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_SRGB8_ALPHA8, w, h, 0,
                    GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, data)
                // The site samples its KTX2 textures trilinearly through ten mip levels; without them the
                // cover's centre crop and the far hills alias (and shimmer while the dunes move).
                GLES30.glGenerateMipmap(GLES30.GL_TEXTURE_2D); mipmapped = true
            }
        }
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER,
            if (mipmapped) GLES30.GL_LINEAR_MIPMAP_LINEAR else GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, wrap(spec.getInt("wrapS")))
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, wrap(spec.getInt("wrapT")))
        return id[0]
    }

    private fun compile(type: Int, source: String, name: String): Int {
        val shader = GLES30.glCreateShader(type)
        GLES30.glShaderSource(shader, source); GLES30.glCompileShader(shader)
        val ok = IntArray(1); GLES30.glGetShaderiv(shader, GLES30.GL_COMPILE_STATUS, ok, 0)
        if (ok[0] == 0) {
            val log = GLES30.glGetShaderInfoLog(shader); GLES30.glDeleteShader(shader)
            throw IllegalStateException("$name ${if (type == GLES30.GL_VERTEX_SHADER) "vertex" else "fragment"}: $log")
        }
        return shader
    }

    private fun link(vertex: String, fragment: String, name: String): Int {
        val program = GLES30.glCreateProgram()
        val vs = compile(GLES30.GL_VERTEX_SHADER, vertex, name); val fs = compile(GLES30.GL_FRAGMENT_SHADER, fragment, name)
        GLES30.glAttachShader(program, vs); GLES30.glAttachShader(program, fs)
        GLES30.glBindAttribLocation(program, 0, "position")
        GLES30.glBindAttribLocation(program, 1, "normal")
        GLES30.glBindAttribLocation(program, 2, "uv")
        GLES30.glLinkProgram(program)
        GLES30.glDeleteShader(vs); GLES30.glDeleteShader(fs)
        val ok = IntArray(1); GLES30.glGetProgramiv(program, GLES30.GL_LINK_STATUS, ok, 0)
        if (ok[0] == 0) {
            val log = GLES30.glGetProgramInfoLog(program); GLES30.glDeleteProgram(program)
            throw IllegalStateException("$name link: $log")
        }
        return program
    }

    private fun ensureTargets(w: Int, h: Int) {
        if (msaaFbo != 0 && targetWidth == w && targetHeight == h) return
        releaseTargets()
        val ids = IntArray(3)
        GLES30.glGenFramebuffers(3, ids, 0); msaaFbo = ids[0]; resolveFbo = ids[1]; outFbo = ids[2]
        val rb = IntArray(2); GLES30.glGenRenderbuffers(2, rb, 0); msaaColor = rb[0]; msaaDepth = rb[1]
        val samples = IntArray(1)
        GLES30.glGetInternalformativ(GLES30.GL_RENDERBUFFER, GLES30.GL_SRGB8_ALPHA8, GLES30.GL_SAMPLES, 1, samples, 0)
        val count = minOf(4, samples[0].coerceAtLeast(0))
        GLES30.glBindRenderbuffer(GLES30.GL_RENDERBUFFER, msaaColor)
        GLES30.glRenderbufferStorageMultisample(GLES30.GL_RENDERBUFFER, count, GLES30.GL_SRGB8_ALPHA8, w, h)
        GLES30.glBindRenderbuffer(GLES30.GL_RENDERBUFFER, msaaDepth)
        GLES30.glRenderbufferStorageMultisample(GLES30.GL_RENDERBUFFER, count, GLES30.GL_DEPTH_COMPONENT16, w, h)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, msaaFbo)
        GLES30.glFramebufferRenderbuffer(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_RENDERBUFFER, msaaColor)
        GLES30.glFramebufferRenderbuffer(GLES30.GL_FRAMEBUFFER, GLES30.GL_DEPTH_ATTACHMENT, GLES30.GL_RENDERBUFFER, msaaDepth)
        checkFramebuffer("msaa")
        val tex = IntArray(2); GLES30.glGenTextures(2, tex, 0); resolveTexture = tex[0]; outTexture = tex[1]
        for ((texture, format) in listOf(resolveTexture to GLES30.GL_SRGB8_ALPHA8, outTexture to GLES30.GL_RGBA8)) {
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texture)
            GLES30.glTexStorage2D(GLES30.GL_TEXTURE_2D, 1, format, w, h)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_NEAREST)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_NEAREST)
        }
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, resolveFbo)
        GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, resolveTexture, 0)
        checkFramebuffer("resolve")
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, outFbo)
        GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, outTexture, 0)
        checkFramebuffer("out")
        targetWidth = w; targetHeight = h
    }

    /** Drops the frame-size buffers (the 4× target is the bulk); programs and assets stay. */
    fun releaseTargets() {
        if (msaaFbo == 0) return
        GLES30.glDeleteFramebuffers(3, intArrayOf(msaaFbo, resolveFbo, outFbo), 0)
        GLES30.glDeleteRenderbuffers(2, intArrayOf(msaaColor, msaaDepth), 0)
        GLES30.glDeleteTextures(2, intArrayOf(resolveTexture, outTexture), 0)
        msaaFbo = 0; resolveFbo = 0; outFbo = 0; msaaColor = 0; msaaDepth = 0; resolveTexture = 0; outTexture = 0
        targetWidth = 0; targetHeight = 0
    }

    /** Renders and encodes into the current draw surface ([toWindow]) or the readable target. */
    fun render(fold: Float, w: Int, h: Int, toWindow: Boolean) {
        ensureTargets(w, h)
        drawScene(fold, w, h)
        GLES30.glBindFramebuffer(GLES30.GL_READ_FRAMEBUFFER, msaaFbo)
        GLES30.glBindFramebuffer(GLES30.GL_DRAW_FRAMEBUFFER, resolveFbo)
        GLES30.glBlitFramebuffer(0, 0, w, h, 0, 0, w, h, GLES30.GL_COLOR_BUFFER_BIT, GLES30.GL_NEAREST)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, if (toWindow) 0 else outFbo)
        GLES30.glViewport(0, 0, w, h)
        GLES30.glDisable(GLES30.GL_DEPTH_TEST); GLES30.glDisable(GLES30.GL_CULL_FACE); GLES30.glDisable(GLES30.GL_BLEND)
        GLES30.glUseProgram(encodeProgram)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, resolveTexture)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(encodeProgram, "scene"), 0)
        // The readable target is read back top row first, as a Bitmap stores it.
        GLES30.glUniform1f(GLES30.glGetUniformLocation(encodeProgram, "flipY"), if (toWindow) 0f else 1f)
        GLES30.glBindVertexArray(0)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3)
        // Tile GPUs need not write the multisampled and depth contents back to memory.
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, msaaFbo)
        GLES30.glInvalidateFramebuffer(GLES30.GL_FRAMEBUFFER, 2, intArrayOf(GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_DEPTH_ATTACHMENT), 0)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, if (toWindow) 0 else outFbo)
        checkGl("render")
    }

    fun renderToBitmap(fold: Float, w: Int, h: Int): Bitmap {
        render(fold, w, h, toWindow = false)
        val pixels = ByteBuffer.allocateDirect(w * h * 4).order(ByteOrder.nativeOrder())
        GLES30.glReadPixels(0, 0, w, h, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, pixels)
        checkGl("readPixels")
        return Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply { copyPixelsFromBuffer(pixels); setHasAlpha(false) }
    }

    private fun drawScene(fold: Float, w: Int, h: Int) {
        track.sample(fold, camera)
        System.arraycopy(camera, DuneCameraTrack.PROJECTION, projection, 0, 16)
        DuneFoldMath.cropProjection(projection, w.toFloat() / h)
        DuneMatrix.invert(camera, DuneCameraTrack.WORLD, view)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, msaaFbo)
        GLES30.glViewport(0, 0, w, h)
        GLES30.glDisable(GLES30.GL_BLEND)
        GLES30.glDepthMask(true)
        GLES30.glClearColor(0f, 0f, 0f, 0f); GLES30.glClearDepthf(1f)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT or GLES30.GL_DEPTH_BUFFER_BIT)
        // three.js FrontSide: back faces culled, counter-clockwise front.
        GLES30.glEnable(GLES30.GL_CULL_FACE); GLES30.glCullFace(GLES30.GL_BACK); GLES30.glFrontFace(GLES30.GL_CCW)
        GLES30.glDepthFunc(GLES30.GL_LEQUAL)
        for (d in draws) {
            GLES30.glUseProgram(d.program)
            val l = d.locations
            DuneMatrix.multiply(view, 0, d.world, 0, modelView)
            DuneMatrix.normalMatrix(modelView, normal)
            l.getValue("modelMatrix").let { if (it >= 0) GLES30.glUniformMatrix4fv(it, 1, false, d.world, 0) }
            l.getValue("modelViewMatrix").let { if (it >= 0) GLES30.glUniformMatrix4fv(it, 1, false, modelView, 0) }
            l.getValue("projectionMatrix").let { if (it >= 0) GLES30.glUniformMatrix4fv(it, 1, false, projection, 0) }
            l.getValue("viewMatrix").let { if (it >= 0) GLES30.glUniformMatrix4fv(it, 1, false, view, 0) }
            l.getValue("normalMatrix").let { if (it >= 0) GLES30.glUniformMatrix3fv(it, 1, false, normal, 0) }
            l.getValue("cameraPosition").let { if (it >= 0) GLES30.glUniform3f(it, camera[12], camera[13], camera[14]) }
            l.getValue("isOrthographic").let { if (it >= 0) GLES30.glUniform1i(it, 0) }
            l.getValue("uFoldProgress").let { if (it >= 0) GLES30.glUniform1f(it, fold) }
            l.getValue("uDuneFarMatrix").let { if (it >= 0) GLES30.glUniformMatrix4fv(it, 1, false, camera, DuneCameraTrack.FAR) }
            l.getValue("uDuneCloseMatrix").let { if (it >= 0) GLES30.glUniformMatrix4fv(it, 1, false, camera, DuneCameraTrack.CLOSE) }
            d.textures.forEachIndexed { unit, t ->
                GLES30.glActiveTexture(GLES30.GL_TEXTURE0 + unit)
                GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, t.id)
                l.getValue(t.uniform).let { if (it >= 0) GLES30.glUniform1i(it, unit) }
            }
            if (d.depthTest) GLES30.glEnable(GLES30.GL_DEPTH_TEST) else GLES30.glDisable(GLES30.GL_DEPTH_TEST)
            GLES30.glDepthMask(d.depthWrite)
            if (d.additive) {
                GLES30.glEnable(GLES30.GL_BLEND)
                GLES30.glBlendEquation(GLES30.GL_FUNC_ADD); GLES30.glBlendFunc(GLES30.GL_ONE, GLES30.GL_ONE)
            } else GLES30.glDisable(GLES30.GL_BLEND)
            GLES30.glBindVertexArray(d.vao)
            GLES30.glDrawElements(GLES30.GL_TRIANGLES, d.indexCount, GLES30.GL_UNSIGNED_SHORT, 0)
        }
        GLES30.glBindVertexArray(0)
        GLES30.glDisable(GLES30.GL_BLEND)
        GLES30.glDepthMask(true)
    }

    fun release() {
        releaseTargets()
        for (d in draws) {
            GLES30.glDeleteProgram(d.program)
            GLES30.glDeleteVertexArrays(1, intArrayOf(d.vao), 0)
            GLES30.glDeleteBuffers(d.buffers.size, d.buffers, 0)
            GLES30.glDeleteTextures(d.textures.size, d.textures.map { it.id }.toIntArray(), 0)
        }
        draws.clear()
        if (encodeProgram != 0) GLES30.glDeleteProgram(encodeProgram)
        encodeProgram = 0
    }

    private fun checkFramebuffer(name: String) {
        val status = GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER)
        check(status == GLES30.GL_FRAMEBUFFER_COMPLETE) { "framebuffer $name incomplete: 0x${Integer.toHexString(status)}" }
    }

    private fun checkGl(where: String) {
        val error = GLES30.glGetError()
        check(error == GLES30.GL_NO_ERROR) { "GL error 0x${Integer.toHexString(error)} at $where" }
    }

    private companion object {
        const val ENCODE_VERTEX = """#version 300 es
out vec2 v;
void main() {
    vec2 p = vec2(float((gl_VertexID << 1) & 2), float(gl_VertexID & 2));
    v = p;
    gl_Position = vec4(p * 2.0 - 1.0, 0.0, 1.0);
}
"""
        /** Apple's target holds linear light; the screen shows it sRGB encoded, opaque. */
        const val ENCODE_FRAGMENT = """#version 300 es
precision highp float;
uniform sampler2D scene;
uniform float flipY;
in vec2 v;
out vec4 color;
void main() {
    vec2 uv = vec2(v.x, mix(v.y, 1.0 - v.y, flipY));
    vec3 c = clamp(texture(scene, uv).rgb, 0.0, 1.0);
    color = vec4(mix(c * 12.92, 1.055 * pow(c, vec3(1.0 / 2.4)) - 0.055, step(vec3(0.0031308), c)), 1.0);
}
"""
    }
}

/** Static stand-ins of the same scene for the first frames after a process start (and if GL is
 * unavailable): Apple's own renders at fold 0 (assets/dunes/fold0.webp) and fold 1 (the bundled
 * default wallpaper, res/drawable-nodpi/duo_wallpaper_c.webp). */
internal object DuneStills {
    @Volatile private var folded: Bitmap? = null
    fun folded(context: Context): Bitmap? = folded ?: synchronized(this) {
        folded ?: runCatching {
            context.assets.open("dunes/fold0.webp").use { BitmapFactory.decodeStream(it) }
        }.getOrNull()?.also { folded = it }
    }
    fun open(context: Context): Bitmap? = bundledDefaultWallpaper(context)
}
