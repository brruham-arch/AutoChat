package com.autochat.app

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.util.concurrent.CountDownLatch

class AutoChatService : AccessibilityService() {

    companion object {
        const val TAG = "AutoChatService"
        const val ACTION_INJECT = "com.autochat.app.INJECT"
        const val EXTRA_TEXT = "text"
        const val EXTRA_TAP_X = "tap_x"
        const val EXTRA_TAP_Y = "tap_y"
        const val EXTRA_PRE_TAP = "pre_tap"
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == ACTION_INJECT) {
                val text = intent.getStringExtra(EXTRA_TEXT) ?: return
                val preTap = intent.getBooleanExtra(EXTRA_PRE_TAP, false)
                val tapX = intent.getFloatExtra(EXTRA_TAP_X, -1f)
                val tapY = intent.getFloatExtra(EXTRA_TAP_Y, -1f)
                performInject(text, preTap, tapX, tapY)
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

    /** Tap koordinat, BLOCK thread sampai gesture selesai */
    private fun tapCoordinateSync(x: Float, y: Float) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return
        val latch = CountDownLatch(1)
        val path = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(path, 0, 100)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(g: GestureDescription) { latch.countDown() }
            override fun onCancelled(g: GestureDescription) { latch.countDown() }
        }, null)
        latch.await() // tunggu sampai gesture benar-benar selesai
    }

    private fun performInject(text: String, preTap: Boolean, tapX: Float, tapY: Float) {
        Thread {
            try {
                // 1. TAP - buka input
                if (preTap && tapX >= 0 && tapY >= 0) {
                    tapCoordinateSync(tapX, tapY)
                    Log.d(TAG, "Pre-tap done: ($tapX, $tapY)")
                    Thread.sleep(500) // tunggu keyboard/input muncul
                }

                // 2. PASTE - isi teks ke input
                val root = rootInActiveWindow ?: return@Thread
                val inputNode = findInputNode(root)
                if (inputNode == null) {
                    Log.e(TAG, "Input tidak ditemukan")
                    root.recycle(); return@Thread
                }

                inputNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                Thread.sleep(100)
                inputNode.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
                Thread.sleep(100)

                val args = Bundle()
                args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
                inputNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
                Log.d(TAG, "Teks diset: $text")
                Thread.sleep(300)

                // 3. SEND
                val freshRoot = rootInActiveWindow ?: root
                val sent = trySend(freshRoot, inputNode)
                Log.d(TAG, "Sent: $sent")

                root.recycle()
                if (freshRoot !== root) freshRoot.recycle()

                // 4. DELAY ditangani oleh FloatingButtonService (setelah broadcast ini selesai)

            } catch (e: Exception) {
                Log.e(TAG, "Error: ${e.message}")
            }
        }.start()
    }

    private fun trySend(root: AccessibilityNodeInfo, inputNode: AccessibilityNodeInfo): Boolean {
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
        val rightmost = findRightmostButtonNearInput(root, inputNode)
        if (rightmost != null) {
            rightmost.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            Log.d(TAG, "Send via rightmost")
            return true
        }
        val result = inputNode.performAction(66)
        Log.d(TAG, "Send via IME: $result")
        return result
    }

    private fun findRightmostButtonNearInput(root: AccessibilityNodeInfo, inputNode: AccessibilityNodeInfo): AccessibilityNodeInfo? {
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
            if (r.centerX() <= inputRect.right) continue
            if (Math.abs(r.centerY() - inputRect.centerY()) > 150) continue
            if (r.width() > 250 || r.height() > 250) continue
            if (r.right > bestRight) { bestRight = r.right; best = node }
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
