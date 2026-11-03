package org.futo.inputmethod.latin.uix.theme.custom

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.futo.inputmethod.latin.R
import org.futo.inputmethod.latin.uix.theme.ThemeOption
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import kotlin.math.max

/**
 * A theme made by the user: three colours and, optionally, a photo behind the keys.
 *
 * [rev] goes up on every save and is part of [key]. That makes the keyboard notice that the
 * theme changed even when the same theme stays selected.
 */
data class CustomTheme(
    val id: String,
    val rev: Int,
    val name: String,
    val background: Int,
    val text: Int,
    val accent: Int,
    val hasPhoto: Boolean,
    /** 0 = photo at full strength, 0.8 = mostly covered by the background colour. */
    val dim: Float,
) {
    val key: String get() = "${CustomThemeStore.KEY_PREFIX}$id:$rev"
}

/**
 * Stores the user's themes on the phone only (settings in SharedPreferences, photos as small
 * JPEG files in the app's private folder). The photo picker is used to choose pictures, so no
 * storage permission is needed.
 */
object CustomThemeStore {
    const val KEY_PREFIX = "custom:"
    const val MAX_THEMES = 12
    private const val PREFS_NAME = "orbit_custom_themes"
    private const val PREF_LIST = "themes"
    private const val PHOTO_DIR = "custom_themes"
    private const val MAX_PHOTO_EDGE = 1280

    private var prefs: SharedPreferences? = null
    private val _themes = MutableStateFlow<List<CustomTheme>>(emptyList())
    val themes: StateFlow<List<CustomTheme>> = _themes.asStateFlow()

    @Synchronized
    fun init(context: Context) {
        if (prefs != null) return
        val p = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs = p
        _themes.value = read(p.getString(PREF_LIST, null))
    }

    fun isCustomKey(key: String?): Boolean = key != null && key.startsWith(KEY_PREFIX)

    private fun idOf(key: String): String = key.removePrefix(KEY_PREFIX).substringBefore(':')

    /** Finds the saved theme a key points to (ignoring the revision number). */
    fun find(context: Context, key: String?): CustomTheme? {
        if (!isCustomKey(key)) return null
        init(context)
        val id = idOf(key!!)
        return _themes.value.firstOrNull { it.id == id }
    }

    /** A [ThemeOption] for a saved theme, or null if the key is not one of ours. */
    fun optionFor(context: Context, key: String?): ThemeOption? {
        val theme = find(context, key) ?: return null
        return optionFor(theme)
    }

    fun optionFor(theme: CustomTheme): ThemeOption = ThemeOption(
        dynamic = false,
        key = theme.key,
        name = R.string.custom_theme_default_name,
        available = { true },
        obtainColors = { colorSchemeFor(theme) },
    )

    // ---- Photos ---------------------------------------------------------------------------------

    private fun photoFile(context: Context, id: String, rev: Int): File =
        File(File(context.applicationContext.filesDir, PHOTO_DIR), "${id}_$rev.jpg")

    /** Loads the saved photo for [theme], or null if there is none or it can't be read. */
    fun loadPhoto(context: Context, theme: CustomTheme): Bitmap? {
        if (!theme.hasPhoto) return null
        return try {
            BitmapFactory.decodeFile(photoFile(context, theme.id, theme.rev).absolutePath)
        } catch (e: Exception) {
            null
        }
    }

    /** The saved photo file's bytes (for backups), or null. */
    fun photoBytes(context: Context, theme: CustomTheme): ByteArray? {
        if (!theme.hasPhoto) return null
        return try {
            photoFile(context, theme.id, theme.rev).readBytes()
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Reads a picture chosen by the user and shrinks it so it is cheap to keep and draw.
     * Returns null if it can't be read. Call from a background thread.
     */
    fun decodePhoto(context: Context, uri: Uri): Bitmap? {
        return try {
            val resolver = context.contentResolver
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

            var sample = 1
            while (max(bounds.outWidth, bounds.outHeight) / sample > MAX_PHOTO_EDGE * 2) sample *= 2
            val options = BitmapFactory.Options().apply { inSampleSize = sample }
            var bitmap = resolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, options)
            } ?: return null

            val rotation = try {
                resolver.openInputStream(uri)?.use {
                    when (ExifInterface(it).getAttributeInt(
                        ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL
                    )) {
                        ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                        ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                        ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                        else -> 0f
                    }
                } ?: 0f
            } catch (e: Exception) {
                0f
            }

            val longest = max(bitmap.width, bitmap.height)
            val scale = if (longest > MAX_PHOTO_EDGE) MAX_PHOTO_EDGE.toFloat() / longest else 1f
            if (rotation != 0f || scale != 1f) {
                val matrix = Matrix().apply {
                    postRotate(rotation)
                    postScale(scale, scale)
                }
                bitmap = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
            }
            bitmap
        } catch (e: Exception) {
            null
        }
    }

    // ---- Saving and deleting ----------------------------------------------------------------------

    /**
     * Creates or updates a theme. Pass [existing] to update one.
     * [newPhoto] replaces the photo; with no [newPhoto], [keepPhoto] decides whether the old photo
     * stays. Call from a background thread.
     */
    @Synchronized
    fun save(
        context: Context,
        existing: CustomTheme?,
        name: String,
        background: Int,
        text: Int,
        accent: Int,
        dim: Float,
        newPhoto: Bitmap?,
        keepPhoto: Boolean,
    ): CustomTheme {
        init(context)
        val id = existing?.id ?: UUID.randomUUID().toString().take(8)
        val rev = (existing?.rev ?: 0) + 1
        val hasPhoto = newPhoto != null || (keepPhoto && existing?.hasPhoto == true)

        File(context.applicationContext.filesDir, PHOTO_DIR).mkdirs()
        val target = photoFile(context, id, rev)
        var photoWritten = false
        try {
            if (newPhoto != null) {
                target.outputStream().use { newPhoto.compress(Bitmap.CompressFormat.JPEG, 88, it) }
                photoWritten = true
            } else if (keepPhoto && existing?.hasPhoto == true) {
                val old = photoFile(context, existing.id, existing.rev)
                if (old.exists()) {
                    old.copyTo(target, overwrite = true)
                    photoWritten = true
                }
            }
        } catch (e: Exception) {
            photoWritten = false
        }
        existing?.let { photoFile(context, it.id, it.rev).delete() }

        val saved = CustomTheme(
            id = id,
            rev = rev,
            name = name.trim().ifEmpty { "My theme" }.take(30),
            background = background,
            text = text,
            accent = accent,
            hasPhoto = hasPhoto && photoWritten,
            dim = dim.coerceIn(0f, 0.85f),
        )
        val list = _themes.value
        _themes.value = if (existing == null) list + saved else list.map { if (it.id == id) saved else it }
        persist()
        return saved
    }

    @Synchronized
    fun delete(context: Context, id: String) {
        init(context)
        _themes.value.firstOrNull { it.id == id }?.let { photoFile(context, it.id, it.rev).delete() }
        _themes.value = _themes.value.filterNot { it.id == id }
        persist()
    }

    private fun persist() {
        val array = JSONArray()
        for (t in _themes.value) {
            array.put(
                JSONObject()
                    .put("id", t.id).put("rev", t.rev).put("name", t.name)
                    .put("bg", t.background).put("text", t.text).put("accent", t.accent)
                    .put("photo", t.hasPhoto).put("dim", t.dim.toDouble())
            )
        }
        prefs?.edit()?.putString(PREF_LIST, array.toString())?.apply()
    }

    private fun read(json: String?): List<CustomTheme> {
        if (json.isNullOrEmpty()) return emptyList()
        return try {
            val array = JSONArray(json)
            (0 until array.length()).mapNotNull { i ->
                val o = array.optJSONObject(i) ?: return@mapNotNull null
                val id = o.optString("id")
                if (id.isEmpty()) return@mapNotNull null
                CustomTheme(
                    id = id,
                    rev = o.optInt("rev", 1),
                    name = o.optString("name", "My theme"),
                    background = o.optInt("bg", 0xFF101418.toInt()),
                    text = o.optInt("text", 0xFFE6E6E6.toInt()),
                    accent = o.optInt("accent", 0xFF4F9DFF.toInt()),
                    hasPhoto = o.optBoolean("photo", false),
                    dim = o.optDouble("dim", 0.4).toFloat(),
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    // ---- Colours ----------------------------------------------------------------------------------

    /** Builds a full Material colour scheme out of the three colours the user picked. */
    fun colorSchemeFor(t: CustomTheme): ColorScheme {
        val bg = Color(t.background)
        val text = Color(t.text)
        val accent = Color(t.accent)
        val onAccent = if (accent.luminance() > 0.5f) Color.Black else Color.White
        val base = if (bg.luminance() < 0.5f) darkColorScheme() else lightColorScheme()

        return base.copy(
            primary = accent,
            onPrimary = onAccent,
            primaryContainer = lerp(bg, accent, 0.35f),
            onPrimaryContainer = text,
            secondary = accent,
            onSecondary = onAccent,
            secondaryContainer = lerp(bg, accent, 0.30f),
            onSecondaryContainer = text,
            tertiary = accent,
            onTertiary = onAccent,
            background = bg,
            onBackground = text,
            surface = bg,
            onSurface = text,
            surfaceVariant = lerp(bg, text, 0.12f),
            onSurfaceVariant = lerp(bg, text, 0.75f),
            outline = lerp(bg, text, 0.50f),
            outlineVariant = lerp(bg, text, 0.20f),
            inverseSurface = text,
            inverseOnSurface = bg,
            inversePrimary = accent,
            surfaceDim = lerp(bg, Color.Black, 0.10f),
            surfaceBright = lerp(bg, text, 0.16f),
            surfaceContainerLowest = lerp(bg, Color.Black, 0.10f),
            surfaceContainerLow = lerp(bg, text, 0.04f),
            surfaceContainer = lerp(bg, text, 0.07f),
            surfaceContainerHigh = lerp(bg, text, 0.10f),
            surfaceContainerHighest = lerp(bg, text, 0.14f),
        )
    }
}
