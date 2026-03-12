package com.autochat.app

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Rect
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
        val filter = IntentFilter(ACTION_INJECT)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(receiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(receiver, filter)
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {}
    override fun onInterrupt() {}

    override fun onDestroy() {
        try { unregisterReceiver(receiver) } catch (e: Exception) {}
        super.onDestroy()
    }

    private fun performInject(text: String) {
        Thread {
            try {
                val root = rootInActiveWindow ?: return@Thread
                val inputNode = findInputNode(root)
                if (inputNode == null) { root.recycle(); return@Thread }

                inputNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                Thread.sleep(150)
                inputNode.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
                Thread.sleep(100)

                val args = Bundle()
                args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
                inputNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
                Thread.sleep(400)

                val freshRoot = rootInActiveWindow ?: root
                trySend(freshRoot, inputNode)
                root.recycle()
                if (freshRoot !== root) freshRoot.recycle()
            } catch (e: Exception) {
                Log.e(TAG, "Error: ${e.message}")
            }
        }.start()
    }

    private fun trySend(root: AccessibilityNodeInfo, inputNode: AccessibilityNodeInfo): Boolean {
        // 1. Cari by resource ID yang dikenal
        val knownIds = listOf("send", "btn_send", "action_send", "sendButton",
            "send_button", "conversation_entry_action_button", "chat_send_button")
        for (idPart in knownIds) {
            val nodes = mutableListOf<AccessibilityNodeInfo>()
            collectByIdContains(root, idPart, nodes)
            for (node in nodes) {
                if (node.isClickable && node.isEnabled) {
                    node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    Log.d(TAG, "Send via ID: $idPart")
                    return true
                }
            }
        }

        // 2. Cari tombol paling KANAN yang sejajar input
        // (bukan yang pertama, tapi yang paling ujung kanan layar)
        val rightmost = findRightmostButtonNearInput(root, inputNode)
        if (rightmost != null) {
            rightmost.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            Log.d(TAG, "Send via rightmost button")
            return true
        }

        // 3. Fallback IME action
        val result = inputNode.performAction(66)
        Log.d(TAG, "Send via IME: $result")
        return result
    }

    /**
     * Ambil tombol paling KANAN yang sejajar vertikal dengan input field.
     * Tombol send selalu paling ujung kanan (WA hijau, TikTok panah merah).
     */
    private fun findRightmostButtonNearInput(
        root: AccessibilityNodeInfo,
        inputNode: AccessibilityNodeInfo
    ): AccessibilityNodeInfo? {
        val inputRect = Rect()
        inputNode.getBoundsInScreen(inputRect)

        val candidates = mutableListOf<AccessibilityNodeInfo>()
        collectClickable(root, candidates)

        var best: AccessibilityNodeInfo? = null
        var bestRight = -1

        for (node in candidates) {
            if (node == inputNode) continue
            val r = Rect()
            node.getBoundsInScreen(r)

            // Harus di kanan input
            if (r.centerX() <= inputRect.right) continue
            // Harus sejajar vertikal (dalam 150px)
            val vertDiff = Math.abs(r.centerY() - inputRect.centerY())
            if (vertDiff > 150) continue
            // Ukuran wajar tombol
            if (r.width() > 250 || r.height() > 250) continue
            // Ambil yang paling kanan (nilai right terbesar)
            if (r.right > bestRight) {
                bestRight = r.right
                best = node
            }
        }
        return best
    }

    private fun collectByIdContains(node: AccessibilityNodeInfo, idPart: String, result: MutableList<AccessibilityNodeInfo>) {
        val resId = node.viewIdResourceName ?: ""
        if (resId.contains(idPart, ignoreCase = true)) result.add(node)
        for (i in 0 until node.childCount) { val child = node.getChild(i) ?: continue; collectByIdContains(child, idPart, result) }
    }

    private fun collectClickable(node: AccessibilityNodeInfo, result: MutableList<AccessibilityNodeInfo>) {
        if (node.isClickable && node.isEnabled) result.add(node)
        for (i in 0 until node.childCount) { val child = node.getChild(i) ?: continue; collectClickable(child, result) }
    }

    private fun findInputNode(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        if (focused != null && focused.isEditable && focused.isEnabled) return focused
        val editNodes = mutableListOf<AccessibilityNodeInfo>()
        collectEditNodes(root, editNodes)
        return editNodes.lastOrNull()
    }

    private fun collectEditNodes(node: AccessibilityNodeInfo, result: MutableList<AccessibilityNodeInfo>) {
        if (node.isEditable && node.isEnabled) result.add(node)
        for (i in 0 until node.childCount) { val child = node.getChild(i) ?: continue; collectEditNodes(child, result) }
    }
}
