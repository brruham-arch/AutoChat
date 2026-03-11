package com.autochat.app

import android.app.*
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.view.*
import android.widget.TextView
import androidx.core.app.NotificationCompat
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.*

class FloatingButtonService : Service() {

    companion object {
        const val TAG = "FloatingBtn"
        const val CHANNEL_ID = "autochat_channel"
        const val NOTIF_ID = 1
    }

    private lateinit var windowManager: WindowManager
    private lateinit var floatingView: View
    private lateinit var tvBtn: TextView
    private lateinit var tvCounter: TextView

    private var isRunning = false
    private var currentIndex = 0
    private var sendCount = 0
    private var job: Job? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIF_ID, buildNotification("Siap"))
        setupFloatingView()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "AutoChat",
                NotificationManager.IMPORTANCE_LOW
            ).apply { description = "AutoChat floating service" }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(status: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("AutoChat")
            .setContentText(status)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun updateNotification(status: String) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIF_ID, buildNotification(status))
    }

    private fun setupFloatingView() {
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager

        floatingView = LayoutInflater.from(this).inflate(R.layout.floating_button, null)
        tvBtn = floatingView.findViewById(R.id.tvFloatBtn)
        tvCounter = floatingView.findViewById(R.id.tvCounter)

        val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 50
            y = 300
        }

        windowManager.addView(floatingView, params)
        setupDrag(params)

        // Single tap = toggle start/stop
        floatingView.setOnClickListener {
            toggleRunning()
        }

        // Long press = tutup service
        floatingView.setOnLongClickListener {
            stopSelf()
            true
        }
    }

    private fun setupDrag(params: WindowManager.LayoutParams) {
        var initX = 0; var initY = 0
        var initTouchX = 0f; var initTouchY = 0f
        var moved = false

        floatingView.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initX = params.x; initY = params.y
                    initTouchX = event.rawX; initTouchY = event.rawY
                    moved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - initTouchX).toInt()
                    val dy = (event.rawY - initTouchY).toInt()
                    if (Math.abs(dx) > 5 || Math.abs(dy) > 5) moved = true
                    params.x = initX + dx
                    params.y = initY + dy
                    windowManager.updateViewLayout(floatingView, params)
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) v.performClick()
                    true
                }
                else -> false
            }
        }
    }

    private fun toggleRunning() {
        if (isRunning) {
            stopSending()
        } else {
            startSending()
        }
    }

    private fun startSending() {
        val prefs = getSharedPreferences("autochat", Context.MODE_PRIVATE)
        val messagesJson = prefs.getString("messages", "[]") ?: "[]"
        val delay = prefs.getLong("delay", 2000)
        val loop = prefs.getBoolean("loop", true)

        val type = object : TypeToken<List<String>>() {}.type
        val messages: List<String> = try {
            Gson().fromJson(messagesJson, type)
        } catch (e: Exception) { emptyList() }

        if (messages.isEmpty()) {
            Log.w(TAG, "Daftar pesan kosong!")
            return
        }

        isRunning = true
        sendCount = 0
        currentIndex = 0

        mainHandler.post {
            tvBtn.text = "⏹"
            tvBtn.setBackgroundResource(android.R.drawable.btn_default)
            tvCounter.text = "0/${messages.size}"
        }
        updateNotification("Mengirim... Loop=$loop")

        job = CoroutineScope(Dispatchers.IO).launch {
            do {
                for (i in messages.indices) {
                    if (!isRunning) break

                    currentIndex = i
                    val text = messages[i]
                    sendCount++

                    Log.d(TAG, "Kirim [${i+1}/${messages.size}]: $text")

                    // Update counter UI
                    mainHandler.post {
                        tvCounter.text = "${i+1}/${messages.size}"
                    }

                    // Kirim broadcast ke AutoChatService
                    val intent = Intent(AutoChatService.ACTION_INJECT)
                    intent.setPackage(packageName)
                    intent.putExtra(AutoChatService.EXTRA_TEXT, text)
                    sendBroadcast(intent)

                    delay(delay)
                }
            } while (isRunning && loop)

            // Selesai (non-loop)
            if (isRunning && !loop) {
                withContext(Dispatchers.Main) {
                    stopSending()
                }
            }
        }
    }

    private fun stopSending() {
        isRunning = false
        job?.cancel()
        mainHandler.post {
            tvBtn.text = "▶"
            tvCounter.text = "Stop"
        }
        updateNotification("Berhenti. Total kirim: $sendCount")
        Log.d(TAG, "Dihentikan. Total terkirim: $sendCount")
    }

    override fun onDestroy() {
        stopSending()
        if (::floatingView.isInitialized) {
            try { windowManager.removeView(floatingView) } catch (e: Exception) {}
        }
        super.onDestroy()
    }
}
