package com.radio.app.widgets

import android.content.Context
import android.util.AttributeSet
import android.util.Log
import android.view.MotionEvent
import androidx.appcompat.widget.AppCompatImageButton
import java.io.File

/**
 * v2.4.58: Custom ImageButton that ROOT-FIXES phantom clicks.
 *
 * Root cause: When the app returns to foreground (onResume), Android's accessibility
 * framework sends repeated performClick calls via View.performAccessibilityActionInternal.
 * These are NOT key events (dispatchKeyEvent can't catch them) and NOT real touches.
 *
 * Fix: Override performClick() to require a real touch event (ACTION_DOWN) before
 * allowing the click to proceed. Accessibility-triggered clicks are blocked at the source.
 */
class PhantomSafeImageButton @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = androidx.appcompat.R.attr.imageButtonStyle
) : AppCompatImageButton(context, attrs, defStyleAttr) {

    companion object {
        private const val TAG = "PhantomSafeBtn"
    }

    // Set to true by onTouchEvent when a real finger touches the screen.
    // Checked and consumed by performClick.
    @Volatile
    private var realTouchPending = false

    @Volatile
    private var debugLogEnabled = false

    fun setDebugLog(enabled: Boolean) {
        debugLogEnabled = enabled
    }

    private fun debugLog(msg: String) {
        if (debugLogEnabled) {
            Log.i(TAG, msg)
            try {
                val logDir = File(context.getExternalFilesDir(null), "logs/jitter")
                if (!logDir.exists()) logDir.mkdirs()
                val logFile = File(logDir, "jitter.log")
                val ts = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", java.util.Locale.US).format(java.util.Date())
                logFile.appendText("[$ts] $msg\n")
            } catch (_: Exception) {}
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_DOWN) {
            realTouchPending = true
            debugLog("${com.radio.app.RadioApplication.appVersionTag()} onTouchEvent ACTION_DOWN - realTouchPending=true")
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean {
        if (!realTouchPending) {
            // This is a phantom click from performAccessibilityActionInternal.
            // Block it completely.
            debugLog("${com.radio.app.RadioApplication.appVersionTag()} performClick BLOCKED (no real touch pending) - phantom click eliminated")
            return false
        }
        // Real touch confirmed - consume the flag and allow the click
        realTouchPending = false
        debugLog("${com.radio.app.RadioApplication.appVersionTag()} performClick ALLOWED (real touch confirmed)")
        return super.performClick()
    }
}
