package com.iefan.readout.utils

import android.app.Activity
import android.content.Context
import com.google.android.play.core.review.ReviewManagerFactory

object InAppReviewHelper {
    private const val PREFS_NAME = "readout_preferences"
    private const val KEY_HAS_PROMPTED = "has_prompted_for_review"
    private const val KEY_COMPLETED_COUNT = "completed_documents_count"

    /**
     * Increments the count of completed listening sessions.
     * Prompt rating popup if they completed at least 2 sessions.
     */
    fun checkAndPromptReview(activity: Activity) {
        val prefs = activity.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val hasPrompted = prefs.getBoolean(KEY_HAS_PROMPTED, false)
        if (hasPrompted) return

        val count = prefs.getInt(KEY_COMPLETED_COUNT, 0) + 1
        prefs.edit().putInt(KEY_COMPLETED_COUNT, count).apply()

        // Prompt review on the 2nd completed document
        if (count >= 2) {
            val manager = ReviewManagerFactory.create(activity)
            val request = manager.requestReviewFlow()
            request.addOnCompleteListener { task ->
                if (task.isSuccessful) {
                    val reviewInfo = task.result
                    val flow = manager.launchReviewFlow(activity, reviewInfo)
                    flow.addOnCompleteListener {
                        // Flow finished, record that we prompted
                        prefs.edit().putBoolean(KEY_HAS_PROMPTED, true).apply()
                    }
                }
            }
        }
    }
}
