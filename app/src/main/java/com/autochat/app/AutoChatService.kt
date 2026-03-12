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

class AutoChatService : AccessibilityService() {

    companion object {
        const val TAG = "AutoChatService"
        const val ACTION_INJECT = "com.autochat.app.INJECT"
        const val ACTION_TAP = "com.autochat.app.TAP"
        const val EXTRA_TEXT = "text"
        const val EXTRA_TAP_X = "tap_x"
        const val EXTRA_TAP_Y = "tap_y"
        const val EXTRA_PRE_TAP = "pre_tap"
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                ACTION_INJECT -> {
                    val text = intent.getStringExtra(EXTRA_TEXT) ?: return
                    val preTap = intent.getBooleanExtra(EXTRA_PRE_TAP, false)
                    val tapX = intent.getFloatExtra(EXTRA_TAP_X, -1f)
                    val tapY = intent.getFloatExtra(EXTRA_TAP_Y, -1f)
                    performInject(text, preTap, tapX, tapY)
                }
                ACTION_TAP -> {
                    val x = intent.getFloatExtra(EXTRA_TAP_X, -1f)
                    val y = intent.getFloatExtra(EXTRA_TAP_Y, -1f)
                    if (x >= 0 && y >= 0) tapCoordinate(x, y)
                }
            }
        }
    }

    override fun onServiceConnected() {
        val filter = IntentFilter().apply {
            addAction(ACTION_INJECT)
            addAction(ACTION_TAP)
        }
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

    fun tapCoordinate(x: Float, y: Float, callback: (() -> Unit)? = null) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            Log.e(TAG, "GestureDescription butuh API 24+")
            callback?.invoke()
            return
        }
        val path = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(path, 0, 100)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription) {
                Log.d(TAG, "Tap OK: $x,$y")
                callback?.invoke()
            }
            override fun onCancelled(gestureDescription: GestureDescription) {
                Log.w(TAG, "Tap cancelled")
                callback?.invoke()
            }
        }, null)
    }

    private fun performInject(text: String, preTap: Boolean, tapX: Float, tapY: Float) {
        Thread {
            try {
                // Jika ada pre-tap (misal TikTok "Ketik...")
                if (preTap && tapX >= 0 && tapY >= 0) {
                    Log.d(TAG, "Pre-tap: $tapX,$tapY")
                    tapCoordinate(tapX, tapY)
                    Thread.sleep(600) // Tunggu keyboard/input muncul
                }

                val root = rootInActiveWindow ?: return@Thread
                val inputNode = findInputNode(root)
                if (inputNode == null) {
                    Log.e(TAG, "Input field tidak ditemukan")
                    root.recycle()
                    return@Thread
                }

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
        val knownIds = listOf("send", "btn_send", "action_send", "sendButton",
            "send_button", "conversation_entry_action_button", "chat_send_button")
        for (idPart in knownIds) {
            val nodes = mutableListOf<AccessibilityNodeInfo>()
            collectByIdContains(root, idPart, nodes)
            for (node in nodes) {
                if (node.isClickable && node.isEnabled) {
                    node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    return true
                }
            }
        }
        val rightmost = findRightmostButtonNearInput(root, inputNode)
        if (rightmost != null) {
            rightmost.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            return true
        }
        return inputNode.performAction(66)
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
            val vertDiff = Math.abs(r.centerY() - inputRect.centerY())
            if (vertDiff > 150) continue
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
