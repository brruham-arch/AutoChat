package com.autochat.app

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        prefs = getSharedPreferences("autochat", Context.MODE_PRIVATE)
        loadMessages()
        setupUI()
    }

    override fun onResume() {
        super.onResume()
        updateStatus()
    }

    private fun loadMessages() {
        val json = prefs.getString("messages", "[]") ?: "[]"
        val type = object : TypeToken<MutableList<String>>() {}.type
        val loaded: MutableList<String> = try {
            Gson().fromJson(json, type)
        } catch (e: Exception) { mutableListOf() }
        messages.clear()
        messages.addAll(loaded)
    }

    private fun saveMessages() {
        prefs.edit().putString("messages", Gson().toJson(messages)).apply()
    }

    private fun setupUI() {
        val listView     = findViewById<ListView>(R.id.listMessages)
        val etMessage    = findViewById<EditText>(R.id.etMessage)
        val btnAdd       = findViewById<Button>(R.id.btnAdd)
        val etDelay      = findViewById<EditText>(R.id.etDelay)
        val switchLoop   = findViewById<SwitchCompat>(R.id.switchLoop)
        val btnStart     = findViewById<Button>(R.id.btnStart)
        val btnAccessibility = findViewById<Button>(R.id.btnAccessibility)
        val btnOverlay   = findViewById<Button>(R.id.btnOverlay)

        // Load saved settings
        etDelay.setText(prefs.getLong("delay", 2000).toString())
        switchLoop.isChecked = prefs.getBoolean("loop", true)

        // Setup list
        messageAdapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, messages)
        listView.adapter = messageAdapter

        // Long press untuk hapus
        listView.setOnItemLongClickListener { _, _, position, _ ->
            AlertDialog.Builder(this)
                .setTitle("Hapus pesan?")
                .setMessage("\"${messages[position]}\"")
                .setPositiveButton("Hapus") { _, _ ->
                    messages.removeAt(position)
                    messageAdapter.notifyDataSetChanged()
                    saveMessages()
                    Toast.makeText(this, "Pesan dihapus", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("Batal", null)
                .show()
            true
        }

        // Tap untuk edit
        listView.setOnItemClickListener { _, _, position, _ ->
            val et = EditText(this)
            et.setText(messages[position])
            AlertDialog.Builder(this)
                .setTitle("Edit pesan")
                .setView(et)
                .setPositiveButton("Simpan") { _, _ ->
                    val newText = et.text.toString().trim()
                    if (newText.isNotEmpty()) {
                        messages[position] = newText
                        messageAdapter.notifyDataSetChanged()
                        saveMessages()
                    }
                }
                .setNegativeButton("Batal", null)
                .show()
        }

        // Tambah pesan
        btnAdd.setOnClickListener {
            val text = etMessage.text.toString().trim()
            if (text.isEmpty()) {
                Toast.makeText(this, "Tulis pesan dulu!", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            messages.add(text)
            messageAdapter.notifyDataSetChanged()
            etMessage.setText("")
            saveMessages()
        }

        // Buka Accessibility Settings
        btnAccessibility.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            Toast.makeText(this, "Cari 'AutoChat' → aktifkan", Toast.LENGTH_LONG).show()
        }

        // Buka Overlay Permission
        btnOverlay.setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                startActivity(Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                ))
            }
        }

        // Simpan delay & loop saat berubah
        etDelay.setOnFocusChangeListener { _, _ ->
            val delay = etDelay.text.toString().toLongOrNull() ?: 2000
            prefs.edit().putLong("delay", delay).apply()
        }
        switchLoop.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean("loop", checked).apply()
        }

        // Start floating button
        btnStart.setOnClickListener {
            val delay = etDelay.text.toString().toLongOrNull() ?: 2000
            prefs.edit()
                .putLong("delay", delay)
                .putBoolean("loop", switchLoop.isChecked)
                .apply()
            saveMessages()

            when {
                !isAccessibilityEnabled() -> {
                    AlertDialog.Builder(this)
                        .setTitle("⚠ Accessibility Belum Aktif")
                        .setMessage("1. Tekan OK untuk buka Accessibility Settings\n2. Cari 'AutoChat'\n3. Aktifkan servicenya\n4. Kembali ke app ini")
                        .setPositiveButton("Buka Settings") { _, _ ->
                            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                        }
                        .setNegativeButton("Nanti", null)
                        .show()
                }
                !hasOverlayPermission() -> {
                    AlertDialog.Builder(this)
                        .setTitle("⚠ Overlay Permission Belum Ada")
                        .setMessage("Izinkan app tampil di atas app lain")
                        .setPositiveButton("Izinkan") { _, _ ->
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                                startActivity(Intent(
                                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                    Uri.parse("package:$packageName")
                                ))
                            }
                        }
                        .setNegativeButton("Nanti", null)
                        .show()
                }
                messages.isEmpty() -> {
                    Toast.makeText(this, "Tambah pesan dulu!", Toast.LENGTH_SHORT).show()
                }
                else -> {
                    startService(Intent(this, FloatingButtonService::class.java))
                    Toast.makeText(this, "✅ Floating button aktif!\nKembali ke app target.", Toast.LENGTH_LONG).show()
                    // Minimize ke home
                    startActivity(Intent(Intent.ACTION_MAIN).apply {
                        addCategory(Intent.CATEGORY_HOME)
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    })
                }
            }
        }
    }

    private fun updateStatus() {
        val tvStatus = findViewById<TextView>(R.id.tvStatus)
        val accessOk = isAccessibilityEnabled()
        val overlayOk = hasOverlayPermission()

        tvStatus.text = buildString {
            append("⚙ Accessibility: ${if (accessOk) "✅" else "❌"}  ")
            append("🪟 Overlay: ${if (overlayOk) "✅" else "❌"}  ")
            append("📋 Pesan: ${messages.size}")
        }
    }

    private fun isAccessibilityEnabled(): Boolean {
        val am = getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        val services = am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
        return services.any { it.resolveInfo.serviceInfo.packageName == packageName }
    }

    private fun hasOverlayPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(this)
        } else true
    }
}
