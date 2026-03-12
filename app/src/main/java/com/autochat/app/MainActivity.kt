package com.autochat.app

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.*
import android.view.accessibility.AccessibilityManager
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

class MainActivity : AppCompatActivity() {

    private lateinit var prefs: SharedPreferences
    private lateinit var messageAdapter: ArrayAdapter<String>
    private val messages = mutableListOf<String>()

    // Overlay untuk pilih koordinat
    private var overlayView: View? = null
    private var windowManager: WindowManager? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        prefs = getSharedPreferences("autochat", Context.MODE_PRIVATE)
        loadMessages()
        setupUI()
    }

    override fun onResume() { super.onResume(); updateStatus() }

    private fun loadMessages() {
        val json = prefs.getString("messages", "[]") ?: "[]"
        val type = object : TypeToken<MutableList<String>>() {}.type
        val loaded: MutableList<String> = try { Gson().fromJson(json, type) } catch (e: Exception) { mutableListOf() }
        messages.clear(); messages.addAll(loaded)
    }

    private fun saveMessages() { prefs.edit().putString("messages", Gson().toJson(messages)).apply() }

    private fun setupUI() {
        val listView = findViewById<ListView>(R.id.listMessages)
        val etMessage = findViewById<EditText>(R.id.etMessage)
        val btnAdd = findViewById<Button>(R.id.btnAdd)
        val etDelay = findViewById<EditText>(R.id.etDelay)
        val switchLoop = findViewById<SwitchCompat>(R.id.switchLoop)
        val switchPreTap = findViewById<SwitchCompat>(R.id.switchPreTap)
        val btnPickTap = findViewById<Button>(R.id.btnPickTap)
        val tvTapCoord = findViewById<TextView>(R.id.tvTapCoord)
        val btnStart = findViewById<Button>(R.id.btnStart)

        // Load settings
        etDelay.setText(prefs.getLong("delay", 2000).toString())
        switchLoop.isChecked = prefs.getBoolean("loop", true)
        switchPreTap.isChecked = prefs.getBoolean("pre_tap", false)

        val tapX = prefs.getFloat("tap_x", -1f)
        val tapY = prefs.getFloat("tap_y", -1f)
        if (tapX >= 0) tvTapCoord.text = "Koordinat: (${tapX.toInt()}, ${tapY.toInt()})"

        messageAdapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, messages)
        listView.adapter = messageAdapter

        listView.setOnItemLongClickListener { _, _, pos, _ ->
            AlertDialog.Builder(this).setTitle("Hapus?").setMessage("\"${messages[pos]}\"")
                .setPositiveButton("Hapus") { _, _ -> messages.removeAt(pos); messageAdapter.notifyDataSetChanged(); saveMessages() }
                .setNegativeButton("Batal", null).show(); true
        }

        listView.setOnItemClickListener { _, _, pos, _ ->
            val et = EditText(this); et.setText(messages[pos])
            AlertDialog.Builder(this).setTitle("Edit").setView(et)
                .setPositiveButton("Simpan") { _, _ ->
                    val t = et.text.toString().trim()
                    if (t.isNotEmpty()) { messages[pos] = t; messageAdapter.notifyDataSetChanged(); saveMessages() }
                }.setNegativeButton("Batal", null).show()
        }

        btnAdd.setOnClickListener {
            val t = etMessage.text.toString().trim()
            if (t.isEmpty()) { Toast.makeText(this, "Tulis pesan dulu!", Toast.LENGTH_SHORT).show(); return@setOnClickListener }
            messages.add(t); messageAdapter.notifyDataSetChanged(); etMessage.setText(""); saveMessages()
        }

        findViewById<Button>(R.id.btnAccessibility).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            Toast.makeText(this, "Cari 'AutoChat' → aktifkan", Toast.LENGTH_LONG).show()
        }

        findViewById<Button>(R.id.btnOverlay).setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        }

        etDelay.setOnFocusChangeListener { _, _ ->
            prefs.edit().putLong("delay", etDelay.text.toString().toLongOrNull() ?: 2000).apply()
        }
        switchLoop.setOnCheckedChangeListener { _, c -> prefs.edit().putBoolean("loop", c).apply() }
        switchPreTap.setOnCheckedChangeListener { _, c -> prefs.edit().putBoolean("pre_tap", c).apply() }

        // Tombol pilih titik tap
        btnPickTap.setOnClickListener {
            if (!hasOverlayPermission()) {
                Toast.makeText(this, "Izinkan overlay dulu!", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            Toast.makeText(this, "App akan minimize. Tap titik yang ingin di-tap otomatis!", Toast.LENGTH_LONG).show()
            startActivity(Intent(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_HOME); flags = Intent.FLAG_ACTIVITY_NEW_TASK })
            showTapPickerOverlay(tvTapCoord)
        }

        btnStart.setOnClickListener {
            val delay = etDelay.text.toString().toLongOrNull() ?: 2000
            prefs.edit().putLong("delay", delay).putBoolean("loop", switchLoop.isChecked).apply()
            saveMessages()
            when {
                !isAccessibilityEnabled() -> showDialog("⚠ Accessibility Belum Aktif", "Tekan OK → cari 'AutoChat' → aktifkan") { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
                !hasOverlayPermission() -> showDialog("⚠ Overlay Belum Diizinkan", "Izinkan app tampil di atas app lain") {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
                        startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
                }
                messages.isEmpty() -> Toast.makeText(this, "Tambah pesan dulu!", Toast.LENGTH_SHORT).show()
                else -> {
                    startService(Intent(this, FloatingButtonService::class.java))
                    Toast.makeText(this, "✅ Floating button aktif!", Toast.LENGTH_LONG).show()
                    startActivity(Intent(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_HOME); flags = Intent.FLAG_ACTIVITY_NEW_TASK })
                }
            }
        }
    }

    private fun showTapPickerOverlay(tvTapCoord: TextView) {
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager

        val overlay = View(this)
        overlay.setBackgroundColor(Color.argb(120, 0, 0, 0))
        overlayView = overlay

        val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            layoutType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            android.graphics.PixelFormat.TRANSLUCENT
        )

        overlay.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_DOWN) {
                val x = event.rawX
                val y = event.rawY
                prefs.edit().putFloat("tap_x", x).putFloat("tap_y", y).apply()

                // Hapus overlay
                try { windowManager?.removeView(overlay) } catch (e: Exception) {}
                overlayView = null

                // Update UI & buka app kembali
                val intent = Intent(this, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                    putExtra("tap_set", true)
                    putExtra("tap_x", x)
                    putExtra("tap_y", y)
                }
                startActivity(intent)
                true
            } else false
        }

        windowManager?.addView(overlay, params)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.getBooleanExtra("tap_set", false)) {
            val x = intent.getFloatExtra("tap_x", -1f)
            val y = intent.getFloatExtra("tap_y", -1f)
            val tvTapCoord = findViewById<TextView>(R.id.tvTapCoord)
            tvTapCoord?.text = "Koordinat: (${x.toInt()}, ${y.toInt()})"
            Toast.makeText(this, "✅ Titik tap tersimpan: (${x.toInt()}, ${y.toInt()})", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showDialog(title: String, msg: String, onOk: () -> Unit) {
        AlertDialog.Builder(this).setTitle(title).setMessage(msg)
            .setPositiveButton("OK") { _, _ -> onOk() }.setNegativeButton("Nanti", null).show()
    }

    private fun updateStatus() {
        val tvStatus = findViewById<TextView>(R.id.tvStatus)
        tvStatus.text = "⚙ Accessibility: ${if (isAccessibilityEnabled()) "✅" else "❌"}  🪟 Overlay: ${if (hasOverlayPermission()) "✅" else "❌"}  📋 Pesan: ${messages.size}"
    }

    private fun isAccessibilityEnabled(): Boolean {
        val am = getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        return am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            .any { it.resolveInfo.serviceInfo.packageName == packageName }
    }

    private fun hasOverlayPermission() =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) Settings.canDrawOverlays(this) else true
}
