package com.qwikshot.app

import android.accessibilityservice.AccessibilityService
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.graphics.Point
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import android.util.Log
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.animation.OvershootInterpolator
import android.widget.Toast
import androidx.annotation.StringRes
import com.qwikshot.app.databinding.LayoutCloseTargetBinding
import com.qwikshot.app.databinding.LayoutFloatingBubbleBinding
import java.util.concurrent.CopyOnWriteArraySet
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * Hosts the floating bubble for both apps built from this project:
 *  - QwiKShot (`shot` flavor): a tap hides the bubble and fires the system screenshot.
 *  - QwiKScan (`scan` flavor): a tap opens Google Drive's document scanner ([DriveScan]).
 * Drag, edge snapping, idle fade, drag-to-dismiss, and long-press are shared.
 *
 * Why an AccessibilityService instead of MediaProjection: MediaProjection asks
 * for consent every session and keeps a capture pipeline running. A bound
 * accessibility service gets, with no prompt and no idle cost:
 *  - a window token for TYPE_ACCESSIBILITY_OVERLAY, which draws above every app
 *    without SYSTEM_ALERT_WINDOW,
 *  - performGlobalAction(GLOBAL_ACTION_TAKE_SCREENSHOT), the same path as
 *    Volume Down + Power, and
 *  - an exemption from background activity-start limits, so a tap can bring
 *    Drive's scanner up over whatever app is showing.
 *
 * The system keeps the service bound while it is enabled, so there is no
 * foreground notification, and nothing runs between touches. The class name
 * stays QwiKShotAccessibilityService in both apps: Android records enabled
 * services by class name, so renaming it would switch QwiKShot off on update.
 *
 * Everything here runs on the main thread.
 */
class QwiKShotAccessibilityService : AccessibilityService(),
    SharedPreferences.OnSharedPreferenceChangeListener {

    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var windowManager: WindowManager
    private lateinit var prefs: QwiKShotPrefs
    private var connected = false

    // Bubble window.
    private var bubble: LayoutFloatingBubbleBinding? = null
    private var bubbleParams: WindowManager.LayoutParams? = null
    private var bubbleAttached = false

    // Drag-to-dismiss target window. Created just before the bubble window so the
    // bubble stacks above it, then parked (invisible, window alpha 0) until a drag.
    private var closeTarget: LayoutCloseTargetBinding? = null
    private var closeParams: WindowManager.LayoutParams? = null
    private var closeAttached = false
    private var closeTargetArmed = false

    private var snapAnimator: ValueAnimator? = null
    private var isCapturing = false
    private var lastCaptureSucceeded = false

    private val idleFadeRunnable = Runnable { fadeToIdle() }
    private val fireScreenshotRunnable = Runnable { fireScreenshot() }
    private val restoreBubbleRunnable = Runnable { restoreAfterCapture() }

    // Read from resources each time so a density or font change is picked up.
    private val shadowPadPx get() = resources.getDimensionPixelSize(R.dimen.bubble_shadow_padding)
    private val edgeMarginPx get() = resources.getDimensionPixelSize(R.dimen.bubble_edge_margin)
    private val tapSlopPx get() = resources.getDimension(R.dimen.bubble_tap_slop)
    private val closeWindowSizePx
        get() = resources.getDimensionPixelSize(R.dimen.close_target_size) +
            2 * resources.getDimensionPixelSize(R.dimen.close_target_padding)
    private val closeBottomMarginPx get() = resources.getDimensionPixelSize(R.dimen.close_target_bottom_margin)
    private val closeCaptureRadiusPx get() = resources.getDimension(R.dimen.close_target_capture_radius)

    // ---------------------------------------------------------------------------------------------
    // Service lifecycle
    // ---------------------------------------------------------------------------------------------

    override fun onServiceConnected() {
        super.onServiceConnected()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        prefs = QwiKShotPrefs(this)
        prefs.raw.registerOnSharedPreferenceChangeListener(this)
        connected = true
        setRunning(true)
        if (prefs.bubbleEnabled) showBubble()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Intentionally empty. QwiKShot never inspects screen content; the service
        // exists only for its overlay token and performGlobalAction(). The config
        // subscribes to announcement events alone, so this is almost never called.
    }

    override fun onInterrupt() {
        // Intentionally empty: QwiKShot produces no spoken or ongoing feedback to stop.
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (bubble == null) return
        // Rotation, fold/unfold, or a density change: re-dock to the saved side and
        // height on the new geometry. Posted so window metrics have settled.
        mainHandler.post { relayoutBubble() }
    }

    override fun onUnbind(intent: Intent?): Boolean {
        tearDown()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        tearDown()
        super.onDestroy()
    }

    private fun tearDown() {
        if (!connected) return
        connected = false
        prefs.raw.unregisterOnSharedPreferenceChangeListener(this)
        removeBubble()
        mainHandler.removeCallbacksAndMessages(null)
        setRunning(false)
    }

    // ---------------------------------------------------------------------------------------------
    // Live settings
    // ---------------------------------------------------------------------------------------------

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
        when (key) {
            QwiKShotPrefs.KEY_BUBBLE_ENABLED -> if (prefs.bubbleEnabled) showBubble() else removeBubble()
            QwiKShotPrefs.KEY_BUBBLE_SIZE_DP -> relayoutBubble()
            QwiKShotPrefs.KEY_IDLE_OPACITY_PERCENT -> previewIdleOpacity()
            // Hide delay is read when a capture starts; position keys are written by this service.
            QwiKShotPrefs.KEY_HIDE_DELAY_MS,
            QwiKShotPrefs.KEY_BUBBLE_SIDE,
            QwiKShotPrefs.KEY_BUBBLE_Y_FRACTION -> Unit
            // A null key means the whole file was cleared (API 30+): re-apply everything.
            null -> if (prefs.bubbleEnabled) {
                showBubble()
                relayoutBubble()
            } else {
                removeBubble()
            }
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Bubble window
    // ---------------------------------------------------------------------------------------------

    @SuppressLint("ClickableViewAccessibility") // BubbleTouchListener calls performClick()/performLongClick().
    private fun showBubble() {
        if (bubble != null) return
        val themedContext = ContextThemeWrapper(this, R.style.Theme_QwiKShot)
        val binding = LayoutFloatingBubbleBinding.inflate(LayoutInflater.from(themedContext))
        val params = buildOverlayParams(touchable = true)

        sizeBubble(binding, params)
        val anchor = dockedPosition(prefs.bubbleSide, prefs.bubbleYFraction, params, screenBounds())
        params.x = anchor.x
        params.y = anchor.y

        // Click and long-click listeners are the real actions. The touch listener
        // decides which one a gesture is; TalkBack's double-tap and long-press reach
        // the same listeners directly.
        binding.root.setOnClickListener { onBubbleTapped() }
        binding.root.setOnLongClickListener {
            openDashboard()
            true
        }
        binding.root.setOnTouchListener(BubbleTouchListener())

        // Windows of the same type stack in the order they are added, so the close
        // target goes in first and the bubble always draws on top of it.
        if (closeTarget == null) createCloseTarget()
        if (!addOverlay(binding.root, params)) {
            removeCloseTargetWindow()
            return
        }
        bubble = binding
        bubbleParams = params
        bubbleAttached = true

        binding.root.alpha = 0f
        binding.root.animate().alpha(1f).setDuration(ENTER_FADE_MS).start()
        scheduleIdleFade()
    }

    private fun removeBubble() {
        mainHandler.removeCallbacks(idleFadeRunnable)
        mainHandler.removeCallbacks(fireScreenshotRunnable)
        mainHandler.removeCallbacks(restoreBubbleRunnable)
        snapAnimator?.cancel()
        snapAnimator = null
        isCapturing = false

        removeCloseTargetWindow()

        bubble?.root?.let { root ->
            root.animate().cancel()
            if (bubbleAttached) removeOverlay(root)
        }
        bubbleAttached = false
        bubble = null
        bubbleParams = null
    }

    /** Re-applies the size setting and re-docks to the saved edge and height. */
    private fun relayoutBubble() {
        val binding = bubble ?: return
        val params = bubbleParams ?: return
        snapAnimator?.cancel()
        sizeBubble(binding, params)
        val anchor = dockedPosition(prefs.bubbleSide, prefs.bubbleYFraction, params, screenBounds())
        params.x = anchor.x
        params.y = anchor.y
        updateBubbleLayout()
    }

    private fun sizeBubble(binding: LayoutFloatingBubbleBinding, params: WindowManager.LayoutParams) {
        val sizePx = (prefs.bubbleSizeDp * resources.displayMetrics.density).roundToInt()
        val icon = binding.bubbleIcon
        icon.layoutParams = icon.layoutParams.apply {
            width = sizePx
            height = sizePx
        }
        val inset = (sizePx * ICON_INSET_RATIO).roundToInt()
        icon.setPadding(inset, inset, inset, inset)
        // Keep the squircle proportions (18dp corners at 56dp) at every size.
        (icon.background?.mutate() as? GradientDrawable)?.cornerRadius = sizePx * CORNER_RADIUS_RATIO
        icon.invalidateOutline()

        val windowSize = sizePx + 2 * shadowPadPx
        params.width = windowSize
        params.height = windowSize
    }

    // Gravity.LEFT on purpose: x is an absolute screen coordinate from the left edge.
    // START would flip to the right edge in RTL locales and break all the edge math.
    @SuppressLint("RtlHardcoded")
    private fun buildOverlayParams(touchable: Boolean): WindowManager.LayoutParams {
        var flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
        if (!touchable) flags = flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE

        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            flags,
            PixelFormat.TRANSLUCENT,
        ).apply {
            // Absolute screen coordinates from the top-left corner, ignoring system
            // bars and cutouts: this service does its own inset math.
            gravity = Gravity.TOP or Gravity.LEFT
            layoutInDisplayCutoutMode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            } else {
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) fitInsetsTypes = 0
            title = getString(R.string.app_name)
        }
    }

    /**
     * Adds an overlay window as TYPE_ACCESSIBILITY_OVERLAY. If the system refuses
     * (some heavily modified ROMs do), retries as TYPE_APPLICATION_OVERLAY when the
     * overlay permission from Step 1 has been granted.
     */
    private fun addOverlay(view: View, params: WindowManager.LayoutParams): Boolean {
        try {
            windowManager.addView(view, params)
            return true
        } catch (e: RuntimeException) {
            Log.w(TAG, "Accessibility overlay refused; trying application overlay", e)
        }
        if (!Settings.canDrawOverlays(this)) {
            toast(R.string.toast_overlay_failed)
            return false
        }
        params.type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        return try {
            windowManager.addView(view, params)
            true
        } catch (e: RuntimeException) {
            Log.e(TAG, "Application overlay refused too", e)
            toast(R.string.toast_overlay_failed)
            false
        }
    }

    private fun removeOverlay(view: View) {
        try {
            windowManager.removeViewImmediate(view)
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "Overlay was already detached", e)
        }
    }

    private fun updateBubbleLayout() {
        val root = bubble?.root ?: return
        val params = bubbleParams ?: return
        if (!bubbleAttached) return
        try {
            windowManager.updateViewLayout(root, params)
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "Bubble update after detach", e)
        }
    }

    private fun setBubbleTouchable(touchable: Boolean) {
        val params = bubbleParams ?: return
        params.flags = if (touchable) {
            params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        } else {
            params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        }
        updateBubbleLayout()
    }

    // ---------------------------------------------------------------------------------------------
    // Tap action
    // ---------------------------------------------------------------------------------------------

    /** QwiKShot takes a screenshot; QwiKScan opens Google Drive's document scanner. */
    private fun onBubbleTapped() {
        if (BuildConfig.DRIVE_SCAN_MODE) openDriveScanner() else captureScreenshot()
    }

    private fun openDriveScanner() {
        val root = bubble?.root ?: return
        // Accessibility services are exempt from background activity-start limits,
        // so Drive opens on top of whatever app is showing.
        when (DriveScan.launch(this, prefs.scanFolder)) {
            DriveScan.LaunchResult.OPENED -> pulseHaptic(root, success = true)
            DriveScan.LaunchResult.OPENED_DRIVE_ONLY -> {
                pulseHaptic(root, success = false)
                toast(R.string.toast_drive_scanner_unavailable)
            }
            DriveScan.LaunchResult.DRIVE_MISSING -> {
                pulseHaptic(root, success = false)
                toast(R.string.toast_drive_missing)
            }
            DriveScan.LaunchResult.FAILED -> {
                pulseHaptic(root, success = false)
                toast(R.string.toast_drive_scan_failed)
            }
        }
        scheduleIdleFade()
    }

    // ---------------------------------------------------------------------------------------------
    // Self-hiding capture pipeline (QwiKShot)
    // ---------------------------------------------------------------------------------------------

    /**
     * 1. Hide the bubble (and stop it taking touches).
     * 2. Wait the configured hide delay (default 100 ms) so the compositor draws a
     *    frame without it.
     * 3. Fire GLOBAL_ACTION_TAKE_SCREENSHOT.
     * 4. Wait 600 ms for the system flash/animation, then bring the bubble back
     *    with a haptic pulse.
     */
    private fun captureScreenshot() {
        val binding = bubble ?: return
        if (isCapturing) return
        isCapturing = true

        mainHandler.removeCallbacks(idleFadeRunnable)
        snapAnimator?.cancel()
        parkCloseTarget()
        binding.root.animate().cancel()
        binding.root.visibility = View.INVISIBLE
        setBubbleTouchable(false)

        mainHandler.postDelayed(fireScreenshotRunnable, prefs.hideDelayMs.toLong())
    }

    private fun fireScreenshot() {
        lastCaptureSucceeded = performGlobalAction(GLOBAL_ACTION_TAKE_SCREENSHOT)
        if (!lastCaptureSucceeded) Log.w(TAG, "GLOBAL_ACTION_TAKE_SCREENSHOT was rejected")
        // On failure there is no system animation to wait for.
        mainHandler.postDelayed(restoreBubbleRunnable, if (lastCaptureSucceeded) RESTORE_DELAY_MS else 0L)
    }

    private fun restoreAfterCapture() {
        isCapturing = false
        val binding = bubble ?: return
        binding.root.alpha = 1f
        binding.root.visibility = View.VISIBLE
        setBubbleTouchable(true)
        pulseHaptic(binding.root, success = lastCaptureSucceeded)
        if (!lastCaptureSucceeded) toast(R.string.toast_screenshot_failed)
        scheduleIdleFade()
    }

    // ---------------------------------------------------------------------------------------------
    // Idle fade
    // ---------------------------------------------------------------------------------------------

    private fun scheduleIdleFade() {
        mainHandler.removeCallbacks(idleFadeRunnable)
        mainHandler.postDelayed(idleFadeRunnable, IDLE_TIMEOUT_MS)
    }

    private fun fadeToIdle() {
        val root = bubble?.root ?: return
        if (isCapturing) return
        root.animate().alpha(idleAlpha()).setDuration(IDLE_FADE_MS).start()
    }

    /** Full opacity on touch. */
    private fun wakeUp() {
        mainHandler.removeCallbacks(idleFadeRunnable)
        val root = bubble?.root ?: return
        root.animate().cancel()
        root.alpha = 1f
    }

    /** Shows the new idle opacity right away while the user moves the slider. */
    private fun previewIdleOpacity() {
        val root = bubble?.root ?: return
        if (isCapturing) return
        mainHandler.removeCallbacks(idleFadeRunnable)
        root.animate().alpha(idleAlpha()).setDuration(PREVIEW_FADE_MS).start()
    }

    private fun idleAlpha(): Float = prefs.idleOpacityPercent / 100f

    // ---------------------------------------------------------------------------------------------
    // Drag, edge snapping, and drag-to-dismiss
    // ---------------------------------------------------------------------------------------------

    private inner class BubbleTouchListener : View.OnTouchListener {
        private var downRawX = 0f
        private var downRawY = 0f
        private var downWindowX = 0
        private var downWindowY = 0
        private var downTime = 0L
        private var dragging = false
        private var longPressed = false
        private var bounds = ScreenBounds(0, 0, 0, 0, 0, 0)
        private var pressedView: View? = null

        private val longPressRunnable = Runnable {
            if (!dragging) {
                longPressed = true
                setIconScale(1f)
                pressedView?.performLongClick()
            }
        }

        override fun onTouch(view: View, event: MotionEvent): Boolean {
            val params = bubbleParams ?: return false
            if (isCapturing) return true

            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    snapAnimator?.cancel()
                    wakeUp()
                    downRawX = event.rawX
                    downRawY = event.rawY
                    downWindowX = params.x
                    downWindowY = params.y
                    downTime = event.eventTime
                    dragging = false
                    longPressed = false
                    bounds = screenBounds()
                    pressedView = view
                    setIconScale(PRESSED_SCALE)
                    mainHandler.postDelayed(longPressRunnable, LONG_PRESS_TIMEOUT_MS)
                }

                MotionEvent.ACTION_MOVE -> {
                    if (longPressed) return true
                    val dx = event.rawX - downRawX
                    val dy = event.rawY - downRawY
                    if (!dragging && hypot(dx, dy) >= tapSlopPx) {
                        dragging = true
                        mainHandler.removeCallbacks(longPressRunnable)
                        setIconScale(LIFTED_SCALE)
                        showCloseTarget(bounds)
                    }
                    if (dragging) {
                        dragBubbleTo(downWindowX + dx.roundToInt(), downWindowY + dy.roundToInt(), bounds)
                    }
                }

                MotionEvent.ACTION_UP -> {
                    mainHandler.removeCallbacks(longPressRunnable)
                    setIconScale(1f)
                    val moved = hypot(event.rawX - downRawX, event.rawY - downRawY)
                    val heldMs = event.eventTime - downTime
                    when {
                        dragging -> finishDrag()
                        longPressed -> scheduleIdleFade()
                        moved < tapSlopPx && heldMs < TAP_TIMEOUT_MS -> view.performClick()
                        else -> scheduleIdleFade()
                    }
                    dragging = false
                    pressedView = null
                }

                MotionEvent.ACTION_CANCEL -> {
                    mainHandler.removeCallbacks(longPressRunnable)
                    setIconScale(1f)
                    if (dragging) finishDrag() else scheduleIdleFade()
                    dragging = false
                    pressedView = null
                }
            }
            return true
        }
    }

    private fun setIconScale(scale: Float) {
        bubble?.bubbleIcon?.animate()?.scaleX(scale)?.scaleY(scale)?.setDuration(PRESS_ANIM_MS)?.start()
    }

    private fun dragBubbleTo(x: Int, y: Int, bounds: ScreenBounds) {
        val params = bubbleParams ?: return
        val target = closeTargetCenter(bounds)
        val centerX = x + params.width / 2
        val centerY = y + params.height / 2
        val overTarget = hypot(
            (centerX - target.x).toFloat(),
            (centerY - target.y).toFloat(),
        ) < closeCaptureRadiusPx
        setCloseArmed(overTarget)

        if (overTarget) {
            // Magnet: lock the bubble onto the target while the finger is close.
            params.x = target.x - params.width / 2
            params.y = target.y - params.height / 2
        } else {
            val pad = shadowPadPx
            params.x = x.coerceIn(-pad, bounds.width - params.width + pad)
            params.y = y.coerceIn(-pad, bounds.height - params.height + pad)
        }
        updateBubbleLayout()
    }

    private fun finishDrag() {
        if (closeTargetArmed) {
            dismissViaCloseTarget()
        } else {
            hideCloseTarget()
            snapToNearestEdge()
            scheduleIdleFade()
        }
    }

    private fun snapToNearestEdge() {
        val params = bubbleParams ?: return
        val bounds = screenBounds()
        val side = if (params.x + params.width / 2 < bounds.width / 2) {
            QwiKShotPrefs.SIDE_LEFT
        } else {
            QwiKShotPrefs.SIDE_RIGHT
        }
        val targetX = edgeX(side, params, bounds)
        val (minY, maxY) = yRange(params, bounds)
        val targetY = params.y.coerceIn(minY, maxY)
        val fraction = if (maxY > minY) (targetY - minY).toFloat() / (maxY - minY) else 0f
        // Save the destination up front so a cancelled animation still leaves a
        // correct record of where the bubble lives.
        prefs.saveBubblePosition(side, fraction)
        animateBubbleTo(targetX, targetY)
    }

    private fun animateBubbleTo(targetX: Int, targetY: Int) {
        val params = bubbleParams ?: return
        snapAnimator?.cancel()
        val startX = params.x
        val startY = params.y
        snapAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = SNAP_DURATION_MS
            interpolator = OvershootInterpolator(SNAP_OVERSHOOT_TENSION)
            addUpdateListener { animator ->
                val current = bubbleParams ?: return@addUpdateListener
                val t = animator.animatedValue as Float
                current.x = startX + ((targetX - startX) * t).roundToInt()
                current.y = startY + ((targetY - startY) * t).roundToInt()
                updateBubbleLayout()
            }
            start()
        }
    }

    private fun showCloseTarget(bounds: ScreenBounds) {
        val binding = closeTarget ?: return
        val params = closeParams ?: return
        val center = closeTargetCenter(bounds)
        params.x = center.x - params.width / 2
        params.y = center.y - params.height / 2
        params.alpha = 1f
        updateCloseLayout()
        binding.root.animate().cancel()
        binding.root.visibility = View.VISIBLE
        binding.root.alpha = 0f
        binding.root.scaleX = CLOSE_HIDDEN_SCALE
        binding.root.scaleY = CLOSE_HIDDEN_SCALE
        binding.root.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(CLOSE_ANIM_MS).start()
    }

    private fun createCloseTarget() {
        val themedContext = ContextThemeWrapper(this, R.style.Theme_QwiKShot)
        val binding = LayoutCloseTargetBinding.inflate(LayoutInflater.from(themedContext))
        val params = buildOverlayParams(touchable = false).apply {
            width = closeWindowSizePx
            height = closeWindowSizePx
            // Parked: a fully transparent window never draws and, in the
            // TYPE_APPLICATION_OVERLAY fallback, never blocks touches to apps below.
            alpha = 0f
        }
        binding.root.visibility = View.INVISIBLE
        if (!addOverlay(binding.root, params)) return
        closeTarget = binding
        closeParams = params
        closeAttached = true
    }

    /** Fades the target out at the end of a drag, then parks it. */
    private fun hideCloseTarget() {
        setCloseArmed(false)
        val binding = closeTarget ?: return
        binding.root.animate().cancel()
        binding.root.animate()
            .alpha(0f)
            .scaleX(CLOSE_HIDDEN_SCALE)
            .scaleY(CLOSE_HIDDEN_SCALE)
            .setDuration(CLOSE_ANIM_MS)
            .withEndAction { parkCloseTarget() }
            .start()
    }

    /** Hides the target immediately (used right before a screenshot). */
    private fun parkCloseTarget() {
        setCloseArmed(false)
        val binding = closeTarget ?: return
        val params = closeParams ?: return
        binding.root.animate().cancel()
        binding.root.visibility = View.INVISIBLE
        params.alpha = 0f
        updateCloseLayout()
    }

    private fun updateCloseLayout() {
        val root = closeTarget?.root ?: return
        val params = closeParams ?: return
        if (!closeAttached) return
        try {
            windowManager.updateViewLayout(root, params)
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "Close target update after detach", e)
        }
    }

    private fun removeCloseTargetWindow() {
        setCloseArmed(false)
        closeTarget?.root?.let { root ->
            root.animate().cancel()
            if (closeAttached) removeOverlay(root)
        }
        closeAttached = false
        closeTarget = null
        closeParams = null
    }

    private fun setCloseArmed(armed: Boolean) {
        if (closeTargetArmed == armed) return
        closeTargetArmed = armed
        val icon = closeTarget?.closeIcon ?: return
        icon.backgroundTintList = if (armed) ColorStateList.valueOf(getColor(R.color.qs_close_armed)) else null
        val scale = if (armed) CLOSE_ARMED_SCALE else 1f
        icon.animate().scaleX(scale).scaleY(scale).setDuration(PRESS_ANIM_MS).start()
        if (armed) bubble?.root?.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
    }

    private fun dismissViaCloseTarget() {
        val binding = bubble ?: return
        setBubbleTouchable(false)
        pulseHaptic(binding.root, success = true)
        hideCloseTarget()
        binding.root.animate().cancel()
        binding.root.animate()
            .alpha(0f)
            .scaleX(DISMISS_SCALE)
            .scaleY(DISMISS_SCALE)
            .setDuration(DISMISS_ANIM_MS)
            .withEndAction {
                // Turning the master toggle off removes the window through the
                // preference listener and keeps the dashboard switch in sync.
                if (prefs.bubbleEnabled) prefs.bubbleEnabled = false else removeBubble()
            }
            .start()
        toast(getString(R.string.toast_bubble_hidden, getString(R.string.app_name)))
    }

    // ---------------------------------------------------------------------------------------------
    // Long-press
    // ---------------------------------------------------------------------------------------------

    private fun openDashboard() {
        // Accessibility services are exempt from background activity-start limits.
        val intent = Intent(this, MainActivity::class.java).addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP,
        )
        try {
            startActivity(intent)
        } catch (e: RuntimeException) {
            Log.w(TAG, "Could not open the dashboard", e)
        }
        scheduleIdleFade()
    }

    // ---------------------------------------------------------------------------------------------
    // Geometry
    // ---------------------------------------------------------------------------------------------

    /** Full display size plus the system-bar and cutout insets the bubble should dock clear of. */
    private data class ScreenBounds(
        val width: Int,
        val height: Int,
        val insetLeft: Int,
        val insetTop: Int,
        val insetRight: Int,
        val insetBottom: Int,
    )

    private fun screenBounds(): ScreenBounds {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val metrics = windowManager.maximumWindowMetrics
            val insets = metrics.windowInsets.getInsetsIgnoringVisibility(
                WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout(),
            )
            return ScreenBounds(
                metrics.bounds.width(), metrics.bounds.height(),
                insets.left, insets.top, insets.right, insets.bottom,
            )
        }
        // Android 9-10: no WindowMetrics. Use the real display size and the
        // framework's own bar dimensions (navigation bar sits right in landscape).
        val size = Point()
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getRealSize(size)
        val statusBar = systemDimenPx("status_bar_height")
        val navBar = systemDimenPx("navigation_bar_height")
        val landscape = size.x > size.y
        return ScreenBounds(
            size.x, size.y,
            0, statusBar,
            if (landscape) navBar else 0,
            if (landscape) 0 else navBar,
        )
    }

    @SuppressLint("DiscouragedApi", "InternalInsetResource")
    private fun systemDimenPx(name: String): Int {
        val id = resources.getIdentifier(name, "dimen", "android")
        return if (id != 0) resources.getDimensionPixelSize(id) else 0
    }

    /**
     * Window x that puts the visible bubble [edgeMarginPx] from the given edge.
     * The window is larger than the bubble by [shadowPadPx] on each side.
     */
    private fun edgeX(side: Int, params: WindowManager.LayoutParams, bounds: ScreenBounds): Int =
        if (side == QwiKShotPrefs.SIDE_LEFT) {
            bounds.insetLeft + edgeMarginPx - shadowPadPx
        } else {
            bounds.width - bounds.insetRight - edgeMarginPx - params.width + shadowPadPx
        }

    /** Allowed window y range that keeps the visible bubble clear of the system bars. */
    private fun yRange(params: WindowManager.LayoutParams, bounds: ScreenBounds): Pair<Int, Int> {
        val minY = bounds.insetTop + edgeMarginPx - shadowPadPx
        val maxY = bounds.height - bounds.insetBottom - edgeMarginPx - params.height + shadowPadPx
        return minY to maxY.coerceAtLeast(minY)
    }

    private fun dockedPosition(
        side: Int,
        yFraction: Float,
        params: WindowManager.LayoutParams,
        bounds: ScreenBounds,
    ): Point {
        val (minY, maxY) = yRange(params, bounds)
        val y = (minY + (maxY - minY) * yFraction).roundToInt()
        return Point(edgeX(side, params, bounds), y)
    }

    private fun closeTargetCenter(bounds: ScreenBounds): Point = Point(
        bounds.width / 2,
        bounds.height - bounds.insetBottom - closeBottomMarginPx - closeWindowSizePx / 2,
    )

    // ---------------------------------------------------------------------------------------------
    // Feedback
    // ---------------------------------------------------------------------------------------------

    private fun pulseHaptic(view: View, success: Boolean) {
        val constant = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (success) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.REJECT
        } else {
            if (success) HapticFeedbackConstants.VIRTUAL_KEY else HapticFeedbackConstants.LONG_PRESS
        }
        if (!view.performHapticFeedback(constant)) vibrateFallback(success)
    }

    /**
     * In QwiKShot the bubble is hidden while the screenshot fires, so the pulse is
     * the only sign the capture went through. If the view-level haptic is
     * unavailable, use the vibrator directly.
     */
    private fun vibrateFallback(success: Boolean) {
        val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
        if (vibrator == null || !vibrator.hasVibrator()) return
        val effect = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            VibrationEffect.createPredefined(
                if (success) VibrationEffect.EFFECT_CLICK else VibrationEffect.EFFECT_DOUBLE_CLICK,
            )
        } else {
            VibrationEffect.createOneShot(if (success) 25L else 60L, VibrationEffect.DEFAULT_AMPLITUDE)
        }
        vibrator.vibrate(effect)
    }

    private fun toast(@StringRes message: Int) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private fun toast(message: CharSequence) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    companion object {
        private val TAG = if (BuildConfig.DRIVE_SCAN_MODE) "QwiKScan" else "QwiKShot"

        private const val TAP_TIMEOUT_MS = 250L
        private const val LONG_PRESS_TIMEOUT_MS = 600L
        private const val RESTORE_DELAY_MS = 600L
        private const val IDLE_TIMEOUT_MS = 3_000L

        private const val ENTER_FADE_MS = 180L
        private const val IDLE_FADE_MS = 450L
        private const val PREVIEW_FADE_MS = 120L
        private const val PRESS_ANIM_MS = 90L
        private const val SNAP_DURATION_MS = 300L
        private const val SNAP_OVERSHOOT_TENSION = 0.7f
        private const val CLOSE_ANIM_MS = 160L
        private const val DISMISS_ANIM_MS = 180L

        private const val PRESSED_SCALE = 0.9f
        private const val LIFTED_SCALE = 1.08f
        private const val CLOSE_HIDDEN_SCALE = 0.6f
        private const val CLOSE_ARMED_SCALE = 1.25f
        private const val DISMISS_SCALE = 0.3f

        // 18dp corners and 13dp icon inset on the 56dp default bubble.
        private const val CORNER_RADIUS_RATIO = 18f / 56f
        private const val ICON_INSET_RATIO = 13f / 56f

        /** True while the system has the service bound (same process as the dashboard). */
        @Volatile
        var isRunning: Boolean = false
            private set

        private val runningListeners = CopyOnWriteArraySet<(Boolean) -> Unit>()

        /** Listener is invoked on the main thread whenever the service connects or unbinds. */
        fun addRunningListener(listener: (Boolean) -> Unit) {
            runningListeners.add(listener)
        }

        fun removeRunningListener(listener: (Boolean) -> Unit) {
            runningListeners.remove(listener)
        }

        private fun setRunning(running: Boolean) {
            isRunning = running
            runningListeners.forEach { it(running) }
        }

        /** Whether the user has switched the service on in Settings > Accessibility. */
        fun isEnabledInSettings(context: Context): Boolean {
            val expected = ComponentName(context, QwiKShotAccessibilityService::class.java)
            val enabled = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            ) ?: return false
            return enabled.split(':').any { ComponentName.unflattenFromString(it.trim()) == expected }
        }
    }
}
