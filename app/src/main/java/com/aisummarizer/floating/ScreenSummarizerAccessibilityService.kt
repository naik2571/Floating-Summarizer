package com.aisummarizer.floating

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Intelligent Accessibility Screen Reader with Spatial Bounding-Box Filtering.
 * Discards status bar (top 6%) and navigation bar (bottom 7%) boilerplate,
 * deduplicates overlapping node rectangles, and preserves clean paragraph flow.
 */
class ScreenSummarizerAccessibilityService : AccessibilityService() {

    companion object {
        const val TAG = "SummarizerAccService"
        var instance: ScreenSummarizerAccessibilityService? = null
            private set

        /**
         * Inspects the currently active window and extracts all readable text nodes,
         * filtering out status bar, navigation bar, and UI boilerplate.
         */
        fun extractActiveScreenText(): String {
            val service = instance ?: return ""
            val root = service.rootInActiveWindow ?: return ""

            val metrics = DisplayMetrics()
            try {
                val wm = service.getSystemService(WINDOW_SERVICE) as WindowManager
                @Suppress("DEPRECATION")
                wm.defaultDisplay.getRealMetrics(metrics)
            } catch (_: Exception) {}

            val screenHeight = if (metrics.heightPixels > 0) metrics.heightPixels else 2400
            val topThreshold = screenHeight * 0.06f // Filter status bar (top 6%)
            val bottomThreshold = screenHeight * 0.93f // Filter navigation bar (bottom 7%)

            val textList = mutableListOf<String>()
            val visitedBounds = mutableSetOf<String>()

            traverseNode(root, textList, visitedBounds, topThreshold, bottomThreshold)

            val result = textList.distinct().joinToString("\n")
            Log.i(TAG, "Extracted ${textList.size} semantic text blocks (${result.length} chars)")
            return result
        }

        private fun traverseNode(
            node: AccessibilityNodeInfo?,
            list: MutableList<String>,
            visited: MutableSet<String>,
            topThreshold: Float,
            bottomThreshold: Float
        ) {
            if (node == null) return

            // Privacy: Ignore password fields
            if (node.isPassword) return

            // Only inspect visible nodes
            if (!node.isVisibleToUser) return

            val rect = Rect()
            node.getBoundsInScreen(rect)

            // Discard status bar and navigation bar zones
            if (rect.bottom < topThreshold || rect.top > bottomThreshold) {
                return
            }

            val boundKey = "${rect.left},${rect.top},${rect.right},${rect.bottom}"

            val text = node.text?.toString()?.trim()
            if (!text.isNullOrBlank() && text.length >= 3 && !visited.contains(boundKey)) {
                visited.add(boundKey)
                list.add(text)
            } else {
                val contentDesc = node.contentDescription?.toString()?.trim()
                if (!contentDesc.isNullOrBlank() && contentDesc.length > 20 && !visited.contains(boundKey)) {
                    visited.add(boundKey)
                    list.add(contentDesc)
                }
            }

            for (i in 0 until node.childCount) {
                traverseNode(node.getChild(i), list, visited, topThreshold, bottomThreshold)
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.i(TAG, "ScreenSummarizerAccessibilityService connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Passive consumption
    }

    override fun onInterrupt() {
        Log.w(TAG, "ScreenSummarizerAccessibilityService interrupted")
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        Log.i(TAG, "ScreenSummarizerAccessibilityService destroyed")
    }
}
