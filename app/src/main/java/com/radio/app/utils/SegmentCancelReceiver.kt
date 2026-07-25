package com.radio.app.utils

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * v2.4.169: Global broadcast receiver for the segment notification cancel action.
 *
 * The previous implementation registered the receiver inside PlayerActivity, so the
 * cancel button became a no-op when the activity was destroyed (e.g. swiped away
 * while segmentation continued in the background). This receiver is declared in
 * AndroidManifest.xml and lives in the application context, so it works regardless
 * of activity state.
 */
class SegmentCancelReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != SegmentNotificationHelper.SEGMENT_CANCEL_ACTION) return

        // Interrupt the running decode/classify thread.
        AudioSegmentAnalyzer.cancelCurrentAnalysis()

        // Dismiss the notification.
        SegmentNotificationHelper.cancel(context)

        // v2.4.169: Broadcast a local event so any active UI can refresh itself.
        val localIntent = Intent(SegmentNotificationHelper.SEGMENT_CANCEL_ACTION)
        androidx.localbroadcastmanager.content.LocalBroadcastManager
            .getInstance(context.applicationContext)
            .sendBroadcast(localIntent)
    }
}
