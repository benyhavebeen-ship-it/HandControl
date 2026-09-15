package com.example.handcontrol

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.FrameLayout
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import com.google.mediapipe.framework.image.MediaImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.ImageProcessingOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarkerResult
import java.util.concurrent.Executors

/**
 * Accessibility service that:
 *  1. Reads the front camera through CameraX.
 *  2. Runs MediaPipe HandLandmarker on each frame.
 *  3. When only the index finger is extended, treats the fingertip as a
 *     finger touching the screen and dispatches a real, system-wide touch
 *     gesture at that point (works over any app, like a real finger).
 *  4. Draws a small camera preview + cursor dot as a floating overlay so
 *     the user gets visual feedback.
 */
class HandControlService : AccessibilityService(), LifecycleOwner {

    private val lifecycleRegistry = LifecycleRegistry(this)
    override val lifecycle: Lifecycle get() = lifecycleRegistry

    private lateinit var windowManager: WindowManager
    private lateinit var overlayRoot: FrameLayout
    private lateinit var previewView: PreviewView
    private lateinit var cursorView: CursorOverlayView

    private var handLandmarker: HandLandmarker? = null
    private val analysisExecutor = Executors.newSingleThreadExecutor()

    private var screenWidth = 0
    private var screenHeight = 0

    // --- gesture / pointer state ---
    private var pointerDown = false
    private var lastX = 0f
    private var lastY = 0f
    private var lastFrameTime = 0L
    private var ongoingStroke: GestureDescription.StrokeDescription? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        lifecycleRegistry.currentState = Lifecycle.State.CREATED

        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val dm = DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getRealMetrics(dm)
        screenWidth = dm.widthPixels
        screenHeight = dm.heightPixels

        setupOverlay()
        setupHandLandmarker()
        startCamera()

        lifecycleRegistry.currentState = Lifecycle.State.RESUMED
    }

    // ---------------------------------------------------------------
    // Overlay: floating window with camera preview + cursor, no touch
    // interception (so it never blocks the real gestures we dispatch).
    // ---------------------------------------------------------------
    private fun setupOverlay() {
        overlayRoot = FrameLayout(this)

        val rootParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )
        rootParams.gravity = Gravity.TOP or Gravity.START

        previewView = PreviewView(this).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
        val previewParams = FrameLayout.LayoutParams(dp(120), dp(160)).apply {
            leftMargin = dp(12)
            topMargin = dp(48)
        }

        cursorView = CursorOverlayView(this)
        val cursorParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        )

        overlayRoot.addView(previewView, previewParams)
        overlayRoot.addView(cursorView, cursorParams)

        windowManager.addView(overlayRoot, rootParams)
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    // ---------------------------------------------------------------
    // MediaPipe HandLandmarker setup (LIVE_STREAM mode)
    // Requires hand_landmarker.task in app/src/main/assets — see README.
    // ---------------------------------------------------------------
    private fun setupHandLandmarker() {
        val baseOptions = BaseOptions.builder()
            .setModelAssetPath("hand_landmarker.task")
            .build()

        val options = HandLandmarker.HandLandmarkerOptions.builder()
            .setBaseOptions(baseOptions)
            .setRunningMode(RunningMode.LIVE_STREAM)
            .setNumHands(1)
            .setMinHandDetectionConfidence(0.5f)
            .setMinHandPresenceConfidence(0.5f)
            .setMinTrackingConfidence(0.5f)
            .setResultListener { result, _ -> onHandResult(result) }
            .setErrorListener { /* keep running even if a frame fails */ }
            .build()

        handLandmarker = HandLandmarker.createFromOptions(this, options)
    }

    // ---------------------------------------------------------------
    // CameraX: front camera, small analysis stream feeding MediaPipe
    // ---------------------------------------------------------------
    private fun startCamera() {
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            val provider = providerFuture.get()

            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }

            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()

            analysis.setAnalyzer(analysisExecutor) { imageProxy ->
                processFrame(imageProxy)
            }

            val selector = CameraSelector.DEFAULT_FRONT_CAMERA

            try {
                provider.unbindAll()
                provider.bindToLifecycle(this, selector, preview, analysis)
            } catch (e: Exception) {
                // camera not available yet / no front camera — ignore, overlay stays idle
            }
        }, androidx.core.content.ContextCompat.getMainExecutor(this))
    }

    @SuppressLint("UnsafeOptInUsageError")
    private fun processFrame(imageProxy: ImageProxy) {
        val landmarker = handLandmarker
        if (landmarker == null) {
            imageProxy.close()
            return
        }
        val mediaImage = imageProxy.image
        if (mediaImage == null) {
            imageProxy.close()
            return
        }
        try {
            val mpImage = MediaImageBuilder(mediaImage).build()
            val rotation = imageProxy.imageInfo.rotationDegrees
            val processingOptions = ImageProcessingOptions.builder()
                .setRotationDegrees(rotation)
                .build()
            landmarker.detectAsync(mpImage, processingOptions, System.currentTimeMillis())
        } catch (e: Exception) {
            // skip this frame
        } finally {
            imageProxy.close()
        }
    }

    // ---------------------------------------------------------------
    // Interpret landmarks -> gesture state -> screen coordinates
    // ---------------------------------------------------------------
    private fun onHandResult(result: HandLandmarkerResult) {
        if (result.landmarks().isEmpty()) {
            cursorView.setHand(null)
            endPointerIfNeeded()
            return
        }

        val lm = result.landmarks()[0]

        cursorView.setHand(lm.map { it.x() to it.y() })

        val indexExt = fingerExtended(lm, 8, 6)
        val middleExt = fingerExtended(lm, 12, 10)
        val ringExt = fingerExtended(lm, 16, 14)
        val pinkyExt = fingerExtended(lm, 20, 18)

        val pointerGesture = indexExt && !middleExt && !ringExt && !pinkyExt

        if (pointerGesture) {
            val tip = lm[8]
            // Front camera preview is mirrored for the user, so mirror x
            // back to match natural on-screen movement direction.
            val nx = 1f - tip.x()
            val ny = tip.y()
            val sx = nx * screenWidth
            val sy = ny * screenHeight

            cursorView.setCursor(sx, sy, true)
            dispatchTouch(sx, sy)
        } else {
            cursorView.setCursor(lastX, lastY, false)
            endPointerIfNeeded()
        }
    }

    private fun fingerExtended(lm: List<com.google.mediapipe.tasks.components.containers.NormalizedLandmark>, tipIdx: Int, pipIdx: Int): Boolean {
        val wrist = lm[0]
        val tip = lm[tipIdx]
        val pip = lm[pipIdx]
        val dTip = distance(tip.x(), tip.y(), wrist.x(), wrist.y())
        val dPip = distance(pip.x(), pip.y(), wrist.x(), wrist.y())
        return dTip > dPip * 1.15f
    }

    private fun distance(x1: Float, y1: Float, x2: Float, y2: Float): Float {
        val dx = x1 - x2
        val dy = y1 - y2
        return kotlin.math.sqrt(dx * dx + dy * dy)
    }

    // ---------------------------------------------------------------
    // Continuous gesture dispatch: builds one long touch stroke that
    // follows the fingertip, using StrokeDescription.continueStroke so
    // it behaves like a real, uninterrupted finger drag across the
    // whole system — not a series of separate taps.
    // ---------------------------------------------------------------
    private fun dispatchTouch(x: Float, y: Float) {
        val now = System.currentTimeMillis()

        if (!pointerDown) {
            // start a brand-new stroke (finger just touched down)
            val path = Path().apply { moveTo(x, y) }
            val stroke = GestureDescription.StrokeDescription(path, 0, 40, true)
            val gesture = GestureDescription.Builder().addStroke(stroke).build()
            dispatchGesture(gesture, null, null)
            ongoingStroke = stroke
            pointerDown = true
        } else {
            val prev = ongoingStroke ?: return
            val duration = (now - lastFrameTime).coerceIn(16, 120)
            val path = Path().apply {
                moveTo(lastX, lastY)
                lineTo(x, y)
            }
            val continued = prev.continueStroke(path, 0, duration, true)
            val gesture = GestureDescription.Builder().addStroke(continued).build()
            dispatchGesture(gesture, null, null)
            ongoingStroke = continued
        }

        lastX = x
        lastY = y
        lastFrameTime = now
    }

    private fun endPointerIfNeeded() {
        if (!pointerDown) return
        val prev = ongoingStroke
        if (prev != null) {
            val path = Path().apply { moveTo(lastX, lastY) }
            val finalStroke = prev.continueStroke(path, 0, 20, false)
            val gesture = GestureDescription.Builder().addStroke(finalStroke).build()
            dispatchGesture(gesture, null, null)
        }
        pointerDown = false
        ongoingStroke = null
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // not used — this service acts on camera input, not on accessibility events
    }

    override fun onInterrupt() {
        endPointerIfNeeded()
    }

    override fun onDestroy() {
        super.onDestroy()
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
        analysisExecutor.shutdown()
        handLandmarker?.close()
        if (::windowManager.isInitialized && ::overlayRoot.isInitialized) {
            try { windowManager.removeView(overlayRoot) } catch (e: Exception) { }
        }
    }
}

/**
 * Transparent full-screen view that draws:
 *  - the hand skeleton, scaled into the small camera preview rectangle
 *  - a glowing dot at the current fingertip position (screen coordinates)
 */
private class CursorOverlayView(context: Context) : View(context) {

    private val previewRect = android.graphics.RectF(
        dpF(12), dpF(48), dpF(12 + 120), dpF(48 + 160)
    )

    private var hand: List<Pair<Float, Float>>? = null
    private var cursorX = 0f
    private var cursorY = 0f
    private var cursorActive = false

    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#4ee68a")
        style = Paint.Style.STROKE
        strokeWidth = dpF(3)
    }
    private val dotFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#664ee68a")
        style = Paint.Style.FILL
    }
    private val skeletonPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#4ee68a")
        strokeWidth = dpF(2)
        style = Paint.Style.STROKE
    }

    private val connections = listOf(
        0 to 1, 1 to 2, 2 to 3, 3 to 4,
        0 to 5, 5 to 6, 6 to 7, 7 to 8,
        5 to 9, 9 to 10, 10 to 11, 11 to 12,
        9 to 13, 13 to 14, 14 to 15, 15 to 16,
        13 to 17, 17 to 18, 18 to 19, 19 to 20,
        0 to 17
    )

    fun setHand(points: List<Pair<Float, Float>>?) {
        hand = points
        postInvalidateOnAnimation()
    }

    fun setCursor(x: Float, y: Float, active: Boolean) {
        cursorX = x
        cursorY = y
        cursorActive = active
        postInvalidateOnAnimation()
    }

    private fun dpF(v: Int): Float = v * resources.displayMetrics.density

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        hand?.let { points ->
            // mirror x for the front camera preview, same as the cursor mapping
            fun px(nx: Float) = previewRect.left + (1f - nx) * previewRect.width()
            fun py(ny: Float) = previewRect.top + ny * previewRect.height()

            connections.forEach { (a, b) ->
                val pa = points[a]
                val pb = points[b]
                canvas.drawLine(px(pa.first), py(pa.second), px(pb.first), py(pb.second), skeletonPaint)
            }
        }

        if (cursorActive) {
            canvas.drawCircle(cursorX, cursorY, dpF(14), dotFillPaint)
            canvas.drawCircle(cursorX, cursorY, dpF(14), dotPaint)
        }
    }

    override fun onTouchEvent(event: MotionEvent?): Boolean = false
}
