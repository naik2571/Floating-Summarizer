package com.aisummarizer.floating

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.DisplayMetrics
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.google.android.material.button.MaterialButton
import kotlin.math.abs

class FloatingOverlayService : Service() {

    companion object {
        const val TAG = "FloatingOverlayService"
        const val CHANNEL_ID = "floating_summarizer_channel"
        const val NOTIFICATION_ID = 5050

        const val ACTION_START = "com.aisummarizer.floating.ACTION_START"
        const val ACTION_STOP_SERVICE = "com.aisummarizer.floating.ACTION_STOP_SERVICE"
        const val ACTION_TOGGLE_BUBBLE = "com.aisummarizer.floating.ACTION_TOGGLE_BUBBLE"
        const val ACTION_TRIGGER_DEMO = "com.aisummarizer.floating.ACTION_TRIGGER_DEMO"
        const val ACTION_STATUS_UPDATE = "com.aisummarizer.floating.ACTION_STATUS_UPDATE"

        const val EXTRA_IS_RUNNING = "extra_is_running"
        const val EXTRA_BUBBLE_VISIBLE = "extra_bubble_visible"

        var isRunning = false
            private set
    }

    private lateinit var windowManager: WindowManager
    private lateinit var prefs: PreferencesManager

    private var bubbleView: View? = null
    private var summaryOverlayView: View? = null

    private lateinit var bubbleParams: WindowManager.LayoutParams
    private var isBubbleVisible = true

    private var initialX = 0
    private var initialY = 0
    private var initialTouchX = 0f
    private var initialTouchY = 0f

    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        prefs = PreferencesManager(this)

        createNotificationChannel()
        createFloatingBubble()
        isRunning = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP_SERVICE -> {
                Log.d(TAG, "Stop service requested via Notification Kill-Switch")
                stopOverlayService()
                return START_NOT_STICKY
            }
            ACTION_TOGGLE_BUBBLE -> {
                toggleBubbleVisibility()
                return START_STICKY
            }
            ACTION_TRIGGER_DEMO -> {
                triggerDemoSummarization()
                return START_STICKY
            }
        }

        startForeground(NOTIFICATION_ID, buildNotification())
        broadcastStatus()
        return START_NOT_STICKY
    }

    private fun buildNotification(): Notification {
        val openAppIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, FloatingOverlayService::class.java).apply { action = ACTION_STOP_SERVICE },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val toggleIntent = PendingIntent.getService(
            this,
            2,
            Intent(this, FloatingOverlayService::class.java).apply { action = ACTION_TOGGLE_BUBBLE },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val toggleText = if (isBubbleVisible) "👁 HIDE BUBBLE" else "👁 SHOW BUBBLE"

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Floating AI Screen Summarizer")
            .setContentText("Tap the floating orb anytime to summarize on-screen content")
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(openAppIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .addAction(R.drawable.ic_notification, toggleText, toggleIntent)
            .addAction(R.drawable.ic_notification, "🛑 STOP / CLOSE", stopIntent)
            .build()
    }

    private fun updateNotification() {
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIFICATION_ID, buildNotification())
    }

    @SuppressLint("ClickableViewAccessibility", "InflateParams")
    private fun createFloatingBubble() {
        bubbleView = LayoutInflater.from(this).inflate(R.layout.layout_floating_bubble, null)

        val density = resources.displayMetrics.density
        val sizePx = (56 * density).toInt()

        bubbleParams = WindowManager.LayoutParams(
            sizePx,
            sizePx,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (resources.displayMetrics.widthPixels - sizePx - (16 * density).toInt())
            y = (resources.displayMetrics.heightPixels / 3)
        }

        bubbleView?.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = bubbleParams.x
                    initialY = bubbleParams.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    bubbleParams.x = initialX + (event.rawX - initialTouchX).toInt()
                    bubbleParams.y = initialY + (event.rawY - initialTouchY).toInt()
                    try {
                        windowManager.updateViewLayout(bubbleView, bubbleParams)
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed updating bubble layout", e)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    val deltaX = abs(event.rawX - initialTouchX)
                    val deltaY = abs(event.rawY - initialTouchY)
                    if (deltaX < 12 && deltaY < 12) {
                        // Considered a Click / Tap!
                        onBubbleClicked()
                    } else {
                        // Snap bubble to nearest screen edge
                        snapBubbleToEdge()
                    }
                    true
                }
                else -> false
            }
        }

        try {
            windowManager.addView(bubbleView, bubbleParams)
            Log.d(TAG, "Floating bubble added to window")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to add floating bubble to WindowManager", e)
        }
    }

    private fun snapBubbleToEdge() {
        val screenWidth = resources.displayMetrics.widthPixels
        val density = resources.displayMetrics.density
        val margin = (12 * density).toInt()

        val snapX = if (bubbleParams.x < screenWidth / 2) {
            margin
        } else {
            screenWidth - bubbleParams.width - margin
        }

        bubbleParams.x = snapX
        try {
            windowManager.updateViewLayout(bubbleView, bubbleParams)
        } catch (e: Exception) {
            Log.w(TAG, "Failed snapping bubble", e)
        }
    }

    private fun onBubbleClicked() {
        vibrateFeedback()
        Log.d(TAG, "Floating bubble clicked - triggering screen summarization")

        // 1. Extract active screen text via Accessibility Service
        val screenText = ScreenSummarizerAccessibilityService.extractActiveScreenText()

        if (screenText.isBlank()) {
            Toast.makeText(
                this,
                "No readable text found on current screen. Ensure Accessibility is enabled.",
                Toast.LENGTH_LONG
            ).show()
            return
        }

        // 2. Perform On-Device Extractive Summarization
        val summaryResult = TextSummarizerEngine.summarize(screenText, prefs.maxBullets)

        // 3. Render Floating Summary Card Overlay
        showSummaryOverlay(summaryResult)
    }

    fun triggerDemoSummarization() {
        val sampleText = """
            Artificial intelligence (AI) is transforming software development and digital productivity at unprecedented scale. 
            Recent advances in local on-device machine learning models allow smartphones to perform natural language processing, 
            biometric tracking, and semantic understanding with near-zero latency while preserving complete user privacy. 
            According to recent industry benchmarks, over 74% of smartphone interactions involve reading long documents, articles, 
            and messages, where automated smart summaries save users up to 15 minutes of screen time daily. 
            Furthermore, on-device algorithms eliminate external cloud dependencies, allowing full operation offline without cellular data. 
            Future personal assistants will seamlessly integrate spatial gestures, voice recognition, and real-time contextual summaries 
            into everyday mobile workflows.
        """.trimIndent()

        val result = TextSummarizerEngine.summarize(sampleText, prefs.maxBullets)
        showSummaryOverlay(result)
    }

    @SuppressLint("InflateParams")
    private fun showSummaryOverlay(result: SummaryResult) {
        dismissSummaryOverlay()

        val overlay = LayoutInflater.from(this).inflate(R.layout.layout_summary_overlay, null)
        summaryOverlayView = overlay

        val tvTimeSaved = overlay.findViewById<TextView>(R.id.tvTimeSavedBadge)
        val tvHeadline = overlay.findViewById<TextView>(R.id.tvHeadline)
        val layoutBullets = overlay.findViewById<LinearLayout>(R.id.layoutBulletsContainer)
        val tvWordCount = overlay.findViewById<TextView>(R.id.tvWordCountInfo)
        val btnClose = overlay.findViewById<ImageView>(R.id.btnCloseOverlay)
        val btnCopy = overlay.findViewById<MaterialButton>(R.id.btnCopySummary)

        tvTimeSaved.text = result.timeSavedFormatted
        tvHeadline.text = result.headline
        tvWordCount.text = "${result.originalWordCount} words → ${result.summaryWordCount} words"

        // Populate bullets dynamically
        layoutBullets.removeAllViews()
        val density = resources.displayMetrics.density
        for (bullet in result.bullets) {
            val bulletView = TextView(this).apply {
                text = bullet
                setTextColor(Color.parseColor("#F1F5F9"))
                textSize = 13.5f
                setLineSpacing(4f * density, 1f)
                setPadding(0, (6 * density).toInt(), 0, (6 * density).toInt())
            }
            layoutBullets.addView(bulletView)
        }

        btnClose.setOnClickListener {
            dismissSummaryOverlay()
        }

        btnCopy.setOnClickListener {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val fullSummaryText = buildString {
                append("📌 ").append(result.headline).append("\n\n")
                result.bullets.forEach { append(it).append("\n") }
                append("\n⚡ Summarized via Floating AI Screen Summarizer")
            }
            val clip = ClipData.newPlainText("AI Screen Summary", fullSummaryText)
            clipboard.setPrimaryClip(clip)
            Toast.makeText(this, "Summary copied to clipboard! 📋", Toast.LENGTH_SHORT).show()
            vibrateFeedback()
        }

        // Overlay layout parameters
        val displayMetrics = resources.displayMetrics
        val cardWidth = (displayMetrics.widthPixels * 0.92).toInt()

        val overlayParams = WindowManager.LayoutParams(
            cardWidth,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.CENTER
        }

        overlay.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_OUTSIDE) {
                dismissSummaryOverlay()
                true
            } else {
                false
            }
        }

        try {
            windowManager.addView(overlay, overlayParams)
            Log.d(TAG, "Summary card overlay presented on screen")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to display summary overlay", e)
        }
    }

    private fun dismissSummaryOverlay() {
        summaryOverlayView?.let {
            try {
                windowManager.removeView(it)
            } catch (e: Exception) {
                Log.w(TAG, "Summary overlay already removed", e)
            }
            summaryOverlayView = null
        }
    }

    private fun toggleBubbleVisibility() {
        isBubbleVisible = !isBubbleVisible
        bubbleView?.visibility = if (isBubbleVisible) View.VISIBLE else View.GONE
        updateNotification()
        broadcastStatus()
        val state = if (isBubbleVisible) "visible" else "hidden"
        Toast.makeText(this, "Summarizer bubble is now $state", Toast.LENGTH_SHORT).show()
    }

    private fun vibrateFeedback() {
        if (!prefs.isHapticFeedback) return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vibratorManager = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                vibratorManager?.defaultVibrator?.vibrate(VibrationEffect.createOneShot(35, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
                @Suppress("DEPRECATION")
                vibrator?.vibrate(35)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Vibration failed", e)
        }
    }

    private fun broadcastStatus() {
        val intent = Intent(ACTION_STATUS_UPDATE).apply {
            putExtra(EXTRA_IS_RUNNING, isRunning)
            putExtra(EXTRA_BUBBLE_VISIBLE, isBubbleVisible)
            setPackage(packageName)
        }
        sendBroadcast(intent)
    }

    private fun stopOverlayService() {
        prefs.isServiceEnabled = false
        isRunning = false
        dismissSummaryOverlay()
        ScreenSummarizerAccessibilityService.stopService()
        bubbleView?.let {
            try {
                windowManager.removeView(it)
            } catch (e: Exception) {
                Log.w(TAG, "Bubble view already removed", e)
            }
            bubbleView = null
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        broadcastStatus()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Floating AI Screen Summarizer Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps the floating summarizer orb available over active apps"
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopOverlayService()
        Log.d(TAG, "FloatingOverlayService destroyed")
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
