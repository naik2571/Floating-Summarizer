package com.aisummarizer.floating

import android.Manifest
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.aisummarizer.floating.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: PreferencesManager

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            checkAndStartService()
        }
    }

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == FloatingOverlayService.ACTION_STATUS_UPDATE) {
                val isRunning = intent.getBooleanExtra(FloatingOverlayService.EXTRA_IS_RUNNING, false)
                binding.switchMaster.isChecked = isRunning
                updateStatusUi(isRunning)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = PreferencesManager(this)

        setupUI()
    }

    private fun setupUI() {
        // Dormant by default
        binding.switchMaster.isChecked = FloatingOverlayService.isRunning && prefs.isServiceEnabled
        updateStatusUi(FloatingOverlayService.isRunning && prefs.isServiceEnabled)

        binding.switchMaster.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                checkAndStartService()
            } else {
                stopFloatingService()
            }
        }

        // Permission Buttons
        binding.tvOverlayPermissionPill.setOnClickListener {
            if (!Settings.canDrawOverlays(this)) {
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
                startActivity(intent)
            }
        }

        binding.tvAccessibilityPill.setOnClickListener {
            if (!isAccessibilityServiceEnabled()) {
                val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                startActivity(intent)
                Toast.makeText(this, "Enable 'AI Screen Summarizer' in Accessibility", Toast.LENGTH_LONG).show()
            }
        }

        // Bullets Slider
        val currentBullets = prefs.maxBullets.toFloat()
        binding.sliderBullets.value = currentBullets.coerceIn(2f, 6f)
        binding.tvBulletsValue.text = "${currentBullets.toInt()} Bullets"
        binding.sliderBullets.addOnChangeListener { _, value, _ ->
            prefs.maxBullets = value.toInt()
            binding.tvBulletsValue.text = "${value.toInt()} Bullets"
        }

        // Haptic Feedback Switch
        binding.switchHaptic.isChecked = prefs.isHapticFeedback
        binding.switchHaptic.setOnCheckedChangeListener { _, isChecked ->
            prefs.isHapticFeedback = isChecked
        }

        // Live Test Demo Button
        binding.btnTestSummarize.setOnClickListener {
            if (!Settings.canDrawOverlays(this)) {
                Toast.makeText(this, "Grant 'Display Over Other Apps' permission first", Toast.LENGTH_LONG).show()
                binding.tvOverlayPermissionPill.performClick()
                return@setOnClickListener
            }

            prefs.isServiceEnabled = true
            val serviceIntent = Intent(this, FloatingOverlayService::class.java).apply {
                action = FloatingOverlayService.ACTION_TRIGGER_DEMO
            }
            ContextCompat.startForegroundService(this, serviceIntent)
            binding.switchMaster.isChecked = true
            updateStatusUi(true)
            Toast.makeText(this, "Launching test summary overlay! ✨", Toast.LENGTH_SHORT).show()
        }
    }

    private fun checkAndStartService() {
        if (!Settings.canDrawOverlays(this)) {
            binding.switchMaster.isChecked = false
            Toast.makeText(this, "Please grant 'Display Over Other Apps' permission", Toast.LENGTH_LONG).show()
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivity(intent)
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                return
            }
        }

        startFloatingService()
    }

    private fun startFloatingService() {
        prefs.isServiceEnabled = true
        val serviceIntent = Intent(this, FloatingOverlayService::class.java).apply {
            action = FloatingOverlayService.ACTION_START
        }
        ContextCompat.startForegroundService(this, serviceIntent)
        updateStatusUi(true)
        Toast.makeText(this, "Floating AI Summarizer Activated", Toast.LENGTH_SHORT).show()

        if (!isAccessibilityServiceEnabled()) {
            Toast.makeText(this, "Tip: Enable Accessibility to extract text from any app", Toast.LENGTH_LONG).show()
        }
    }

    private fun stopFloatingService() {
        prefs.isServiceEnabled = false
        val serviceIntent = Intent(this, FloatingOverlayService::class.java).apply {
            action = FloatingOverlayService.ACTION_STOP_SERVICE
        }
        startService(serviceIntent)
        updateStatusUi(false)
        Toast.makeText(this, "Floating Summarizer Stopped (Dormant)", Toast.LENGTH_SHORT).show()
    }

    private fun updateStatusUi(isActive: Boolean) {
        if (isActive) {
            binding.viewStatusDot.setBackgroundResource(R.drawable.bg_dot_active)
            binding.tvStatusText.text = "Active • Floating Orb Ready on Screen"
            binding.tvStatusText.setTextColor(ContextCompat.getColor(this, R.color.status_active))
        } else {
            binding.viewStatusDot.setBackgroundResource(R.drawable.bg_dot_inactive)
            binding.tvStatusText.text = "Service Inactive (Dormant)"
            binding.tvStatusText.setTextColor(ContextCompat.getColor(this, R.color.text_muted))
        }
    }

    private fun updatePermissionPills() {
        val hasOverlay = Settings.canDrawOverlays(this)
        if (hasOverlay) {
            binding.tvOverlayPermissionPill.text = "✓ Granted"
            binding.tvOverlayPermissionPill.setBackgroundResource(R.drawable.bg_pill_granted)
        } else {
            binding.tvOverlayPermissionPill.text = "Grant"
            binding.tvOverlayPermissionPill.setBackgroundResource(R.drawable.bg_pill_action)
        }

        val hasAccessibility = isAccessibilityServiceEnabled()
        if (hasAccessibility) {
            binding.tvAccessibilityPill.text = "✓ Enabled"
            binding.tvAccessibilityPill.setBackgroundResource(R.drawable.bg_pill_granted)
        } else {
            binding.tvAccessibilityPill.text = "Enable"
            binding.tvAccessibilityPill.setBackgroundResource(R.drawable.bg_pill_action)
        }
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        if (ScreenSummarizerAccessibilityService.instance != null) return true
        val am = getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        val enabledServices = am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_GENERIC)
        for (service in enabledServices) {
            if (service.id.contains(packageName)) return true
        }
        return false
    }

    override fun onResume() {
        super.onResume()
        updatePermissionPills()

        val filter = IntentFilter(FloatingOverlayService.ACTION_STATUS_UPDATE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(statusReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(statusReceiver, filter)
        }

        binding.switchMaster.isChecked = FloatingOverlayService.isRunning && prefs.isServiceEnabled
        updateStatusUi(FloatingOverlayService.isRunning && prefs.isServiceEnabled)
    }

    override fun onPause() {
        super.onPause()
        try {
            unregisterReceiver(statusReceiver)
        } catch (e: Exception) {
            // Already unregistered
        }
    }
}
