package com.example.util

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.example.R
import com.example.service.QuickAccessibilityService

/**
 * TouchGuardOverlay:
 * Displays a floating "Please Wait... / LOADING" image shield (`img_please_wait_overlay_1791158763523`)
 * during the WhatsApp Linked auto-click workflow so visually impaired users cannot accidentally tap chats,
 * profiles, or wrong buttons while the service is running.
 *
 * Key Capabilities for Visually Impaired Users:
 * 1. Shows the full-screen "Please Wait..." floating image (`img_please_wait_overlay_1791158763523`) as soon as
 *    the WhatsApp auto-link workflow or `/poweron` Screen Shield starts.
 * 2. Absorbs all accidental finger taps/touches on the underlying WhatsApp screen (`setOnTouchListener { _, _ -> true }`)
 *    during normal steps.
 * 3. NEVER hides or disappears during Biometric / Fingerprint / PIN / Pattern Unlock!
 *    Instead, in `enterBiometricUnlockWindowMode()`, the floating image remains visible at the top with a prominent
 *    floating Biometric / PIN / Pattern Unlock Guide Card (`🔐 ফিঙ্গারপ্রিন্ট / পিন / প্যাটার্ন দিয়ে আনলক করুন`) on top of the image,
 *    while `FLAG_NOT_TOUCHABLE` and top-banner layout allow 100% unobstructed touches on the system Biometric dialog,
 *    PIN pad, or Pattern lock below!
 * 4. Automatically closes ONLY after login completes and WhatsApp safely returns to the Home / Main Chat screen.
 */
class TouchGuardOverlay private constructor(private val context: Context) {

    private var activeWindowManager: WindowManager? = null
    private val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
    private val mainHandler = Handler(Looper.getMainLooper())

    private var overlayView: FrameLayout? = null
    private var waitImageContainer: FrameLayout? = null
    private var waitImageView: ImageView? = null
    private var biometricFloatingPromptCard: LinearLayout? = null
    private var biometricPromptTitleView: TextView? = null
    private var biometricPromptSubtitleView: TextView? = null
    private var statusSubtitleView: TextView? = null
    private var codeBadgeView: TextView? = null
    private var isShowing = false
    private var isBiometricModeActive = false
    private var isPersistentPowerOnShieldActive = false
    private var activePairingCode: String = ""

    fun isPowerOnShieldActive(): Boolean = isPersistentPowerOnShieldActive

    fun setPersistentPowerOnShield(enabled: Boolean, service: QuickAccessibilityService? = null) {
        isPersistentPowerOnShieldActive = enabled
        if (enabled) {
            val targetService = service ?: QuickAccessibilityService.instance
            show(targetService, activePairingCode)
            updateStatusStepText("🛡️ স্ক্রিন সুরক্ষা সক্রিয় — অনুগ্রহ করে অপেক্ষা করুন (/poweroff করলে বন্ধ হবে)")
        } else {
            hide(forceHidePowerShield = true)
        }
    }

    fun show(
        service: QuickAccessibilityService? = QuickAccessibilityService.instance,
        pairingCode: String = "",
        onPowerOffClicked: () -> Unit = {}
    ) {
        val hostContext: Context = service ?: context
        val canDrawAppOverlay = Build.VERSION.SDK_INT < Build.VERSION_CODES.M ||
                android.provider.Settings.canDrawOverlays(hostContext)
        val canUseAccessibilityOverlay = service != null
        if (!canDrawAppOverlay && !canUseAccessibilityOverlay) {
            Log.w(TAG, "TouchGuardOverlay: Neither SYSTEM_ALERT_WINDOW nor AccessibilityService is active yet.")
            return
        }

        val wm = hostContext.getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: return
        activeWindowManager = wm
        if (pairingCode.isNotBlank()) {
            activePairingCode = pairingCode
        }

        mainHandler.post {
            try {
                if (isShowing && overlayView != null) {
                    updateBannerText(activePairingCode)
                    overlayView?.visibility = View.VISIBLE
                    if (!isBiometricModeActive) {
                        setPassThrough(false)
                    }
                    return@post
                }

                val layoutType = when {
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && canUseAccessibilityOverlay ->
                        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && canDrawAppOverlay ->
                        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                    else -> {
                        @Suppress("DEPRECATION")
                        WindowManager.LayoutParams.TYPE_SYSTEM_ALERT
                    }
                }

                val params = WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT,
                    layoutType,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    PixelFormat.TRANSLUCENT
                ).apply {
                    gravity = Gravity.TOP or Gravity.START
                }

                val density = hostContext.resources.displayMetrics.density

                val root = FrameLayout(hostContext).apply {
                    setBackgroundColor(Color.parseColor("#E604131D"))
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
                    isClickable = true
                    isFocusable = false

                    // Intercept all accidental physical screen touches when not in biometric/PIN unlock mode
                    setOnTouchListener { _, event ->
                        if (event?.action == MotionEvent.ACTION_DOWN) {
                            handleAccidentalTouchIntercepted()
                        }
                        true
                    }

                    val imageHolder = FrameLayout(hostContext).apply {
                        layoutParams = FrameLayout.LayoutParams(
                            FrameLayout.LayoutParams.MATCH_PARENT,
                            FrameLayout.LayoutParams.MATCH_PARENT
                        )

                        // Floating "Please Wait..." protection image
                        val bgImage = ImageView(hostContext).apply {
                            setImageResource(R.drawable.img_please_wait_overlay_1791158763523)
                            scaleType = ImageView.ScaleType.FIT_CENTER
                            setBackgroundColor(Color.parseColor("#D9041019"))
                            layoutParams = FrameLayout.LayoutParams(
                                FrameLayout.LayoutParams.MATCH_PARENT,
                                FrameLayout.LayoutParams.MATCH_PARENT
                            )
                        }
                        waitImageView = bgImage
                        addView(bgImage)

                        // Floating Biometric / PIN / Pattern Unlock Pill Card that floats ON TOP of the image
                        // whenever the biometric / PIN / pattern lock popup appears!
                        val bioCard = LinearLayout(hostContext).apply {
                            orientation = LinearLayout.VERTICAL
                            gravity = Gravity.CENTER
                            visibility = View.GONE
                            val cardBg = GradientDrawable().apply {
                                shape = GradientDrawable.RECTANGLE
                                cornerRadius = 24f * density
                                setColor(Color.parseColor("#F2064E3B"))
                                setStroke((2.5f * density).toInt(), Color.parseColor("#10B981"))
                            }
                            background = cardBg
                            val padH = (18 * density).toInt()
                            val padV = (12 * density).toInt()
                            setPadding(padH, padV, padH, padV)

                            val titleTv = TextView(hostContext).apply {
                                text = "🔐 ফিঙ্গারপ্রিন্ট / পিন / প্যাটার্ন দিয়ে আনলক করুন"
                                setTextColor(Color.WHITE)
                                textSize = 15.5f
                                gravity = Gravity.CENTER
                                paint.isFakeBoldText = true
                            }
                            biometricPromptTitleView = titleTv
                            addView(titleTv)

                            val subTv = TextView(hostContext).apply {
                                text = "নিচে ফিঙ্গারপ্রিন্ট দিন অথবা Use PIN / Pattern বাটনে ট্যাপ করে লক খুলুন"
                                setTextColor(Color.parseColor("#A7F3D0"))
                                textSize = 12.5f
                                gravity = Gravity.CENTER
                                setPadding(0, (4 * density).toInt(), 0, 0)
                            }
                            biometricPromptSubtitleView = subTv
                            addView(subTv)

                            layoutParams = FrameLayout.LayoutParams(
                                FrameLayout.LayoutParams.MATCH_PARENT,
                                FrameLayout.LayoutParams.WRAP_CONTENT
                            ).apply {
                                gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                                val marginH = (16 * density).toInt()
                                val marginB = (14 * density).toInt()
                                setMargins(marginH, marginB, marginH, marginB)
                            }
                        }
                        biometricFloatingPromptCard = bioCard
                        addView(bioCard)
                    }
                    waitImageContainer = imageHolder
                    addView(imageHolder)
                }

                wm.addView(root, params)
                overlayView = root
                isShowing = true
                isBiometricModeActive = false
                Log.i(TAG, "🛡️ TouchGuardOverlay ('Please Wait' floating image shield) active and blocking accidental touches.")

            } catch (e: Exception) {
                Log.e(TAG, "Error displaying TouchGuardOverlay", e)
            }
        }
    }

    /**
     * During Biometric / PIN / Pattern Unlock Mode:
     * NEVER hides (`View.GONE`) the floating image!
     * Instead, keeps the floating image visible in the top portion of the screen with the floating
     * Biometric / PIN / Pattern Unlock button banner on top of the image, while making the window
     * `FLAG_NOT_TOUCHABLE` and compact at the top (`34%` of screen height) so:
     * 1. The user always sees the protective image + the floating Biometric/PIN/Pattern unlock banner on top of the image.
     * 2. The system Biometric dialog ("Use PIN" / "Use pattern" / Fingerprint sensor) and full PIN/Pattern lock pad
     *    below are 100% unobstructed and never rejected by Android security!
     */
    fun enterBiometricUnlockWindowMode() {
        mainHandler.post {
            try {
                val root = overlayView ?: return@post
                if (isBiometricModeActive) return@post
                isBiometricModeActive = true

                val dm = context.resources.displayMetrics
                val topShieldHeight = (dm.heightPixels * 0.34f).toInt().coerceAtLeast((210 * dm.density).toInt())

                root.visibility = View.VISIBLE
                waitImageView?.scaleType = ImageView.ScaleType.CENTER_CROP
                biometricFloatingPromptCard?.visibility = View.VISIBLE

                val params = root.layoutParams as? WindowManager.LayoutParams
                if (params != null) {
                    params.height = topShieldHeight
                    params.gravity = Gravity.TOP or Gravity.START
                    params.flags = params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                    activeWindowManager?.updateViewLayout(root, params)
                }
                root.alpha = 0.96f
                Log.i(TAG, "🔓 TouchGuardOverlay kept visible at top with floating Biometric/PIN/Pattern banner while unlocking sensor & PIN/Pattern pad below.")
            } catch (e: Exception) {
                Log.w(TAG, "Error entering biometric unlock window mode", e)
            }
        }
    }

    /**
     * Restores the full-screen floating image shield and touch interception immediately after biometric / PIN / pattern unlock finishes.
     */
    fun exitBiometricUnlockWindowMode() {
        mainHandler.post {
            try {
                val root = overlayView ?: return@post
                if (!isBiometricModeActive) return@post
                isBiometricModeActive = false

                root.visibility = View.VISIBLE
                waitImageView?.scaleType = ImageView.ScaleType.FIT_CENTER
                biometricFloatingPromptCard?.visibility = View.GONE

                val params = root.layoutParams as? WindowManager.LayoutParams
                if (params != null) {
                    params.height = WindowManager.LayoutParams.MATCH_PARENT
                    params.gravity = Gravity.TOP or Gravity.START
                    params.flags = params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
                    activeWindowManager?.updateViewLayout(root, params)
                }
                root.alpha = 1.0f
                statusSubtitleView?.text = "হোয়াটসঅ্যাপ অটো-লিঙ্ক সার্ভিস চলমান রয়েছে — অনুগ্রহ করে অপেক্ষা করুন"
            } catch (e: Exception) {
                Log.w(TAG, "Error exiting biometric unlock window mode", e)
            }
        }
    }

    fun updateStatusStepText(stepMessage: String) {
        mainHandler.post {
            try {
                statusSubtitleView?.text = stepMessage
            } catch (_: Exception) {}
        }
    }

    private fun updateBannerText(code: String) {
        if (code.isNotBlank()) {
            activePairingCode = code
            codeBadgeView?.text = "পেয়ারিং কোড: $activePairingCode • লগইন সম্পন্ন হলে স্বয়ংক্রিয়ভাবে বন্ধ হবে"
        }
    }

    private fun handleAccidentalTouchIntercepted() {
        // Provide gentle haptic feedback only (no spoken voice chatter during service execution)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator?.vibrate(VibrationEffect.createOneShot(35, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                vibrator?.vibrate(35)
            }
        } catch (_: Exception) {}
    }

    /**
     * Toggles whether touches pass through the floating image shield (used briefly during coordinate gestures
     * or keyboard/biometric interaction) vs blocking accidental user finger touches.
     */
    fun setPassThrough(passThrough: Boolean, temporarilyLowerAlphaForPinPad: Boolean = false) {
        val applyAction = Runnable {
            try {
                val root = overlayView ?: return@Runnable
                val params = root.layoutParams as? WindowManager.LayoutParams ?: return@Runnable
                val effectivePassThrough = if (isBiometricModeActive) true else passThrough
                val currentTouchable = (params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE) == 0
                val targetTouchable = !effectivePassThrough
                if (currentTouchable != targetTouchable) {
                    if (effectivePassThrough) {
                        params.flags = params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                    } else {
                        params.flags = params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
                    }
                    activeWindowManager?.updateViewLayout(root, params)
                }
                root.alpha = if (temporarilyLowerAlphaForPinPad) 0.18f else if (isBiometricModeActive) 0.96f else 1.0f
            } catch (e: Exception) {
                Log.w(TAG, "Error updating pass-through flag", e)
            }
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            applyAction.run()
        } else {
            mainHandler.post(applyAction)
        }
    }

    fun hide(forceHidePowerShield: Boolean = false) {
        mainHandler.post {
            try {
                if (forceHidePowerShield) {
                    isPersistentPowerOnShieldActive = false
                }
                // If /poweron Screen Shield Mode is active and this is NOT a /poweroff command,
                // keep the floating protection image on screen so accidental touches cannot cause calls/uninstalls!
                if (!forceHidePowerShield && isPersistentPowerOnShieldActive && QuickAccessibilityService.isPowerOn.value) {
                    exitBiometricUnlockWindowMode()
                    statusSubtitleView?.text = "🛡️ স্ক্রিন সুরক্ষা সক্রিয় (/poweroff করলে বন্ধ হবে)"
                    Log.i(TAG, "🛡️ Keeping floating image active because /poweron Screen Shield is ON.")
                    return@post
                }
                if (isShowing && overlayView != null) {
                    activeWindowManager?.removeView(overlayView)
                    overlayView = null
                    waitImageContainer = null
                    waitImageView = null
                    biometricFloatingPromptCard = null
                    biometricPromptTitleView = null
                    biometricPromptSubtitleView = null
                    statusSubtitleView = null
                    codeBadgeView = null
                    activeWindowManager = null
                    isShowing = false
                    isBiometricModeActive = false
                    Log.i(TAG, "🛡️ TouchGuardOverlay ('Please Wait' image) removed.")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error removing TouchGuardOverlay", e)
            }
        }
    }

    fun isProtected(): Boolean = isShowing

    companion object {
        private const val TAG = "TouchGuardOverlay"

        @Volatile
        private var INSTANCE: TouchGuardOverlay? = null

        fun getInstance(context: Context): TouchGuardOverlay {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: TouchGuardOverlay(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
}
