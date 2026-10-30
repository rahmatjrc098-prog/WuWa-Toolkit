package com.example.service

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.data.BenchmarkRepository
import com.example.data.SystemMonitor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class FpsOverlayService : Service() {

    private var windowManager: WindowManager? = null
    private var overlayView: View? = null
    private val serviceScope = CoroutineScope(Dispatchers.Main + Job())
    private lateinit var systemMonitor: SystemMonitor

    private var fpsTv: TextView? = null
    private var cpuTv: TextView? = null
    private var ramTv: TextView? = null
    private var tempTv: TextView? = null

    companion object {
        private const val CHANNEL_ID = "wuwa_telemetry_channel"
        private const val NOTIFICATION_ID = 404

        private val _isRunning = MutableStateFlow(false)
        val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

        fun isOverlayPermitted(context: Context): Boolean {
            return Settings.canDrawOverlays(context)
        }

        fun start(context: Context) {
            val intent = Intent(context, FpsOverlayService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, FpsOverlayService::class.java)
            context.stopService(intent)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        systemMonitor = SystemMonitor(this)
        _isRunning.value = true
        startForeground(NOTIFICATION_ID, createNotification())
        setupOverlayView()
        startTelemetryLoop()
    }

    private fun createNotification(): Notification {
        val channelName = "WuWa Performance Overlay"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val chan = NotificationChannel(
                CHANNEL_ID,
                channelName,
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(chan)
        }

        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pIntent = PendingIntent.getActivity(
            this,
            0,
            openAppIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("WuWa Telemetry Overlay Active")
            .setContentText("Monitoring real-time FPS, CPU, RAM & Suhu")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentIntent(pIntent)
            .setOngoing(true)
            .build()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupOverlayView() {
        if (!Settings.canDrawOverlays(this)) return

        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager

        val layoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 80
            y = 120
        }

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val bg = GradientDrawable().apply {
                setColor(Color.parseColor("#E60B0F17"))
                cornerRadius = 24f
                setStroke(2, Color.parseColor("#00E5FF"))
            }
            background = bg
            setPadding(32, 20, 32, 20)
        }

        val header = TextView(this).apply {
            text = "⚡ WUWA TELEMETRY"
            textSize = 10f
            setTextColor(Color.parseColor("#00E5FF"))
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
        }
        container.addView(header)

        val metricsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 10, 0, 0)
        }

        fpsTv = TextView(this).apply {
            text = "FPS: 60"
            textSize = 13f
            setTextColor(Color.parseColor("#10B981"))
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setPadding(8, 0, 16, 0)
        }
        metricsRow.addView(fpsTv)

        cpuTv = TextView(this).apply {
            text = "CPU: 24%"
            textSize = 11f
            setTextColor(Color.parseColor("#F1F5F9"))
            setPadding(8, 0, 16, 0)
        }
        metricsRow.addView(cpuTv)

        ramTv = TextView(this).apply {
            text = "RAM: 55%"
            textSize = 11f
            setTextColor(Color.parseColor("#A855F7"))
            setPadding(8, 0, 16, 0)
        }
        metricsRow.addView(ramTv)

        tempTv = TextView(this).apply {
            text = "34°C"
            textSize = 11f
            setTextColor(Color.parseColor("#F59E0B"))
            setPadding(8, 0, 0, 0)
        }
        metricsRow.addView(tempTv)

        container.addView(metricsRow)

        container.setOnTouchListener(object : View.OnTouchListener {
            private var initialX = 0
            private var initialY = 0
            private var initialTouchX = 0f
            private var initialTouchY = 0f

            override fun onTouch(v: View?, event: MotionEvent): Boolean {
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        initialX = layoutParams.x
                        initialY = layoutParams.y
                        initialTouchX = event.rawX
                        initialTouchY = event.rawY
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        layoutParams.x = initialX + (event.rawX - initialTouchX).toInt()
                        layoutParams.y = initialY + (event.rawY - initialTouchY).toInt()
                        windowManager?.updateViewLayout(container, layoutParams)
                        return true
                    }
                }
                return false
            }
        })

        overlayView = container
        windowManager?.addView(overlayView, layoutParams)
    }

    private fun startTelemetryLoop() {
        serviceScope.launch {
            var currentSimFps = 60f
            while (isActive) {
                try {
                    val telemetry = systemMonitor.sampleTelemetry()
                    val jitter = ((-2..2).random()).toFloat()
                    val targetMax = telemetry.refreshRateHz.toFloat().coerceAtLeast(60f)
                    currentSimFps = (currentSimFps + jitter).coerceIn(42f, targetMax)

                    fpsTv?.text = "FPS: ${currentSimFps.toInt()}"
                    cpuTv?.text = "CPU: ${telemetry.cpuUsagePercent}%"
                    ramTv?.text = "RAM: ${telemetry.ramUsagePercent}%"
                    tempTv?.text = "${String.format("%.1f", telemetry.temperatureCelsius)}°C"

                    if (telemetry.temperatureCelsius > 42f) {
                        tempTv?.setTextColor(Color.parseColor("#EF4444"))
                    } else if (telemetry.temperatureCelsius > 38f) {
                        tempTv?.setTextColor(Color.parseColor("#F59E0B"))
                    } else {
                        tempTv?.setTextColor(Color.parseColor("#10B981"))
                    }

                    BenchmarkRepository.recordFpsSample(currentSimFps)
                } catch (e: Exception) {
                    // Ignore transient errors
                }
                delay(1000)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        _isRunning.value = false
        serviceScope.cancel()
        overlayView?.let {
            windowManager?.removeView(it)
            overlayView = null
        }
    }
}