package org.futo.inputmethod.latin.uix

import android.content.Context
import android.view.Gravity

/** Helpers (callable from Java) for the one-handed keyboard mode. */
object OneHandedMode {
    /** How wide the keyboard is in one-handed mode, compared with the full width. */
    private const val NARROW_FRACTION = 0.78f

    private fun mode(context: Context): String = try {
        context.getSettingBlocking(OneHandedModeSetting)
    } catch (e: Exception) {
        "off"
    }

    @JvmStatic
    fun widthFactor(context: Context): Float =
        if (mode(context) == "off") 1.0f else NARROW_FRACTION

    /** Where the keyboard sits: bottom, and pushed to the left or right edge when one-handed. */
    @JvmStatic
    fun frameGravity(context: Context): Int = when (mode(context)) {
        "left" -> Gravity.BOTTOM or Gravity.LEFT
        "right" -> Gravity.BOTTOM or Gravity.RIGHT
        else -> Gravity.BOTTOM
    }

    /** off -> right -> left -> off. Used by the toolbar button. */
    fun next(current: String): String = when (current) {
        "off" -> "right"
        "right" -> "left"
        else -> "off"
    }
}
