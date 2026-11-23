package android.provider

import android.content.ContentResolver

object Settings {
    object Global {
        const val ANIMATOR_DURATION_SCALE = "animator_duration_scale"
        fun getFloat(cr: ContentResolver, name: String, def: Float): Float = def
    }
    object System {
        const val HAPTIC_FEEDBACK_ENABLED = "haptic_feedback_enabled"
        fun getInt(cr: ContentResolver, name: String, def: Int): Int = def
    }
}
