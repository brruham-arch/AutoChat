package com.autochat.app

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class AutoChatService : AccessibilityService() {

    companion object {
        const val TAG = "AutoChatService"
        const val ACTION_INJECT = "com.autochat.app.INJECT"
        const val EXTRA_TEXT = "text"

        // Keywords untuk mencari tombol send di berbagai app
        val SEND_KEYWORDS = listOf(
            "send", "kirim", "submit", "post", "share",
            "btn_send", "action_send", "send_button",
            "ic_send", "message_send"
        )
        val SEND_DESCRIPTIONS = listOf(
            "send", "kirim", "Send", "Kirim", "Submit", "Post"
        )
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == ACTION_INJECT) {
                val text = intent.getStringExtra(EXTRA_TEXT) ?: return
                performInject(text)
            }
        }
    }

    override fun onServiceConnected() {
        Log.d(TAG, "Accessibility Service connected")
        val filter = IntentFilter(ACTION_INJECT)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(receiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(receiver, filter)
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        // Tidak perlu handle events
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        try { unregisterReceiver(receiver) } catch (e: Exception) {}
        super.onDestroy()
    }

    private fun performInject(text: String) {
        Thread {
            try {
                val root = rootInActiveWindow
                if (root == null) {
                    Log.e(TAG, "Root window null - pastikan app target sedang terbuka")
                    return@Thread
                }

                // 1. Cari EditText yang bisa diisi
                val inputNode = findInputNode(root)
                if (inputNode == null) {
                    Log.e(TAG, "Tidak ditemukan input field")
                    root.recycle()
                    return@Thread
                }

                // 2. Fokus ke input
                inputNode.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
                Thread.sleep(100)

                // 3. Isi teks
                val args = Bundle()
                args.putCharSequence(
                    AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                    text
                )
                inputNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
                Log.d(TAG, "Teks berhasil diisi: $text")

                Thread.sleep(300)

                // 4. Klik tombol send
                val sent = clickSendButton(root)
                if (!sent) {
                    // Fallback: tekan IME action (Enter/Send pada keyboard)
                    inputNode.performAction(AccessibilityNodeInfo.ACTION_NEXT_AT_MOVEMENT_GRANULARITY)
                    Log.d(TAG, "Fallback: IME action")
                }

                root.recycle()
            } catch (e: Exception) {
                Log.e(TAG, "Error inject: ${e.message}")
            }
        }.start()
    }

    /**
     * Cari EditText: prioritaskan yang sedang focused, lalu yang paling bawah
     * (biasanya chat input ada di bawah)
     */
    private fun findInputNode(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        // Coba yang focused dulu
        val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        if (focused != null && focused.isEditable && focused.isEnabled) {
            return focused
        }

        // Kumpulkan semua editable node
        val editNodes = mutableListOf<AccessibilityNodeInfo>()
        collectEditNodes(root, editNodes)

        if (editNodes.isEmpty()) return null

        // Ambil yang terakhir (paling bawah, biasanya chat input)
        return editNodes.last()
    }

    private fun collectEditNodes(node: AccessibilityNodeInfo, result: MutableList<AccessibilityNodeInfo>) {
        if (node.isEditable && node.isEnabled) {
            result.add(node)
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            collectEditNodes(child, result)
        }
    }

    /**
     * Cari dan klik tombol send menggunakan berbagai strategi
     */
    private fun clickSendButton(root: AccessibilityNodeInfo): Boolean {
        // Strategi 1: Cari by text
        for (keyword in SEND_DESCRIPTIONS) {
            val nodes = root.findAccessibilityNodeInfosByText(keyword)
            for (node in nodes) {
                if (node.isClickable && node.isEnabled) {
                    node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    Log.d(TAG, "Send ditemukan by text: $keyword")
                    return true
                }
                // Coba parent jika node sendiri tidak clickable
                val parent = node.parent
                if (parent != null && parent.isClickable && parent.isEnabled) {
                    parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    return true
                }
            }
        }

        // Strategi 2: Traverse tree, cari button yang punya keyword send di ID/desc
        val result = findSendNodeRecursive(root)
        if (result != null) {
            result.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            Log.d(TAG, "Send ditemukan by recursive search")
            return true
        }

        Log.w(TAG, "Tombol send tidak ditemukan, coba IME action")
        return false
    }

    private fun findSendNodeRecursive(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isClickable && node.isEnabled) {
            val viewId = node.viewIdResourceName?.lowercase() ?: ""
            val desc = node.contentDescription?.toString()?.lowercase() ?: ""
            val text = node.text?.toString()?.lowercase() ?: ""

            val allText = "$viewId $desc $text"
            if (SEND_KEYWORDS.any { allText.contains(it) }) {
                return node
            }
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val result = findSendNodeRecursive(child)
            if (result != null) return result
        }
        return null
    }
}
