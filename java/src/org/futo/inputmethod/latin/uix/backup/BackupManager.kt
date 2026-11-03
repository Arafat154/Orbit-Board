package org.futo.inputmethod.latin.uix.backup

import android.content.Context
import android.graphics.BitmapFactory
import android.util.Base64
import org.futo.inputmethod.latin.blocklist.BlockedWordsStore
import org.futo.inputmethod.latin.uix.theme.custom.CustomThemeStore
import org.json.JSONArray
import org.json.JSONObject

/**
 * Saves the blocked-word list and the custom themes (with their photos) to one file, and reads
 * such a file back.
 *
 * Restoring only ever ADDS: words already in the list are kept, and themes are added next to the
 * existing ones. That way a restore can never be used to quietly remove blocked words without
 * the 30-second wait.
 */
object BackupManager {
    private const val FORMAT = "orbitboard-backup"
    private const val VERSION = 1

    data class RestoreResult(val wordsAdded: Int, val themesAdded: Int)

    fun createBackup(context: Context): String {
        BlockedWordsStore.init(context)
        CustomThemeStore.init(context)

        val words = JSONArray()
        for (entry in BlockedWordsStore.state.value.userWords) {
            words.put(JSONObject().put("w", entry.word).put("i", entry.matchInside))
        }

        val themes = JSONArray()
        for (t in CustomThemeStore.themes.value) {
            val o = JSONObject()
                .put("name", t.name).put("bg", t.background).put("text", t.text)
                .put("accent", t.accent).put("dim", t.dim.toDouble())
            CustomThemeStore.photoBytes(context, t)?.let {
                o.put("photo", Base64.encodeToString(it, Base64.NO_WRAP))
            }
            themes.put(o)
        }

        return JSONObject()
            .put("format", FORMAT)
            .put("version", VERSION)
            .put("words", words)
            .put("themes", themes)
            .toString()
    }

    /** Throws if the text is not a backup made by this app. Call from a background thread. */
    fun restore(context: Context, json: String): RestoreResult {
        BlockedWordsStore.init(context)
        CustomThemeStore.init(context)

        val root = JSONObject(json)
        require(root.optString("format") == FORMAT) { "This is not an Orbit Board backup file." }

        // Words
        var wordsAdded = 0
        val words = root.optJSONArray("words") ?: JSONArray()
        val exact = ArrayList<String>()
        val inside = ArrayList<String>()
        for (i in 0 until words.length()) {
            val o = words.optJSONObject(i) ?: continue
            val w = o.optString("w")
            if (w.isEmpty()) continue
            if (o.optBoolean("i", false)) inside.add(w) else exact.add(w)
        }
        wordsAdded += BlockedWordsStore.addWords(exact, false).added
        wordsAdded += BlockedWordsStore.addWords(inside, true).added

        // Themes
        var themesAdded = 0
        val themes = root.optJSONArray("themes") ?: JSONArray()
        for (i in 0 until themes.length()) {
            val o = themes.optJSONObject(i) ?: continue
            if (CustomThemeStore.themes.value.size >= CustomThemeStore.MAX_THEMES) break

            val name = o.optString("name", "My theme")
            val bg = o.optInt("bg")
            val text = o.optInt("text")
            val accent = o.optInt("accent")
            val alreadyHave = CustomThemeStore.themes.value.any {
                it.name == name && it.background == bg && it.text == text && it.accent == accent
            }
            if (alreadyHave) continue

            val photo = o.optString("photo").takeIf { it.isNotEmpty() }?.let {
                try {
                    val bytes = Base64.decode(it, Base64.NO_WRAP)
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                } catch (e: Exception) {
                    null
                }
            }
            CustomThemeStore.save(
                context = context,
                existing = null,
                name = name,
                background = bg,
                text = text,
                accent = accent,
                dim = o.optDouble("dim", 0.4).toFloat(),
                newPhoto = photo,
                keepPhoto = false,
            )
            themesAdded++
        }
        return RestoreResult(wordsAdded, themesAdded)
    }
}
