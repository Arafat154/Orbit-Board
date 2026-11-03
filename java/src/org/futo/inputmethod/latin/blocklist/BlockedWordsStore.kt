package org.futo.inputmethod.latin.blocklist

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import org.futo.inputmethod.latin.SuggestedWords.SuggestedWordInfo
import org.json.JSONArray
import org.json.JSONObject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Holds the block list and the on/off switch, and answers "is this word blocked?" for the
 * typing code. Everything is stored on the phone only (no network).
 *
 * The typing code is Java and runs on the main thread, so the check methods are @JvmStatic and
 * read an immutable snapshot that is swapped whenever the list changes.
 */
object BlockedWordsStore {
    /** What a blocked word is replaced with. */
    const val MASK = "***"

    private const val PREFS_NAME = "orbit_blocked_words"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_USER_WORDS = "user_words"
    private const val KEY_REMOVED_BUILT_IN = "removed_built_in"

    /** What the settings screen shows. */
    data class State(
        val enabled: Boolean = true,
        /** The whole starter library, including entries the user removed (see [removedBuiltIn]). */
        val builtIn: List<BlockedEntry> = BlockedWordsLibrary.builtIn,
        val removedBuiltIn: Set<String> = emptySet(),
        val userWords: List<BlockedEntry> = emptyList(),
    )

    enum class AddResult { ADDED, EMPTY, HAS_SPACES, TOO_SHORT, TOO_SHORT_FOR_INSIDE, DUPLICATE }

    /** What happened when a whole list of words was imported. */
    data class ImportResult(
        val added: Int,
        val alreadyThere: Int,
        /** Too short, or over the size limit. */
        val skipped: Int,
    )

    /** Most words the user list can hold; keeps typing fast. */
    const val MAX_USER_WORDS = 20000

    /**
     * Turns the text of a file into separate words. Words may be separated by new lines, commas,
     * semicolons, tabs or spaces, so a simple list, a CSV or a one-column sheet saved as text all work.
     */
    fun parseWordList(text: String): List<String> {
        return text.removePrefix("\uFEFF")
            .split(Regex("[\\s,;|]+"))
            .map { it.trim().trim('"', '\'', '\u201C', '\u201D', '\u2018', '\u2019') }
            .filter { it.isNotEmpty() }
            .distinct()
    }

    private var prefs: SharedPreferences? = null

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    @Volatile
    private var enabled = true

    @Volatile
    private var matcher = BlockedWordMatcher(emptyList(), emptyList())

    /** Safe to call many times. Call before the list is needed (keyboard start, settings page). */
    @Synchronized
    fun init(context: Context) {
        if (prefs != null) return
        val app = context.applicationContext ?: context
        // Device-protected storage so the list also works before the phone is first unlocked.
        val storageContext = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            app.createDeviceProtectedStorageContext()
        } else {
            app
        }
        val p = storageContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs = p

        _state.value = State(
            enabled = p.getBoolean(KEY_ENABLED, true),
            removedBuiltIn = readStringArray(p.getString(KEY_REMOVED_BUILT_IN, null)).toSet(),
            userWords = readEntries(p.getString(KEY_USER_WORDS, null)),
        )
        publish(_state.value)
    }

    // ---- Checks used while typing (fast, thread-safe) --------------------------------------

    @JvmStatic
    fun isBlocked(word: CharSequence?): Boolean = enabled && matcher.isBlocked(word)

    /** Replaces every blocked word inside [text] with [MASK]. Returns [text] itself if clean. */
    @JvmStatic
    fun maskText(text: CharSequence?): CharSequence? {
        if (text == null || !enabled) return text
        return matcher.maskText(text, MASK)
    }

    /** Removes blocked words from a suggestion list so they are never offered. */
    @JvmStatic
    fun removeBlockedSuggestions(suggestions: MutableList<SuggestedWordInfo>) {
        if (!enabled || matcher.isEmpty()) return
        val iterator = suggestions.iterator()
        while (iterator.hasNext()) {
            if (matcher.isBlocked(iterator.next().mWord)) iterator.remove()
        }
    }

    // ---- Changes made from the settings screen ----------------------------------------------

    @Synchronized
    fun setEnabled(value: Boolean) {
        update { it.copy(enabled = value) }
    }

    @Synchronized
    fun addWord(rawWord: String, matchInside: Boolean): AddResult {
        val word = rawWord.trim()
        if (word.isEmpty()) return AddResult.EMPTY
        if (word.any { it.isWhitespace() }) return AddResult.HAS_SPACES
        val normalized = BlockedWordMatcher.normalize(word)
        if (normalized.length < 2) return AddResult.TOO_SHORT
        if (matchInside && normalized.length < 3) return AddResult.TOO_SHORT_FOR_INSIDE

        val current = _state.value
        val existing = (current.builtIn.filter { it.word !in current.removedBuiltIn } + current.userWords)
            .map { BlockedWordMatcher.normalize(it.word) }
        if (normalized in existing) return AddResult.DUPLICATE

        update { it.copy(userWords = it.userWords + BlockedEntry(word, matchInside)) }
        return AddResult.ADDED
    }

    /** Adds many words at once (for example from a file). Duplicates and bad entries are skipped. */
    @Synchronized
    fun addWords(rawWords: List<String>, matchInside: Boolean): ImportResult {
        val current = _state.value
        val seen = HashSet<String>()
        current.builtIn.filter { it.word !in current.removedBuiltIn }
            .forEach { seen.add(BlockedWordMatcher.normalize(it.word)) }
        current.userWords.forEach { seen.add(BlockedWordMatcher.normalize(it.word)) }

        val toAdd = ArrayList<BlockedEntry>()
        var alreadyThere = 0
        var skipped = 0
        val room = (MAX_USER_WORDS - current.userWords.size).coerceAtLeast(0)
        for (raw in rawWords) {
            val word = raw.trim()
            val normalized = BlockedWordMatcher.normalize(word)
            if (word.isEmpty() || word.any { it.isWhitespace() } || normalized.length < 2 ||
                (matchInside && normalized.length < 3)
            ) {
                skipped++
                continue
            }
            if (!seen.add(normalized)) {
                alreadyThere++
                continue
            }
            if (toAdd.size >= room) {
                skipped++
                continue
            }
            toAdd.add(BlockedEntry(word, matchInside))
        }
        if (toAdd.isNotEmpty()) update { it.copy(userWords = it.userWords + toAdd) }
        return ImportResult(toAdd.size, alreadyThere, skipped)
    }

    @Synchronized
    fun removeUserWord(word: String) {
        update { state -> state.copy(userWords = state.userWords.filterNot { it.word == word }) }
    }

    @Synchronized
    fun removeBuiltInWord(word: String) {
        update { it.copy(removedBuiltIn = it.removedBuiltIn + word) }
    }

    @Synchronized
    fun restoreBuiltInWords() {
        update { it.copy(removedBuiltIn = emptySet()) }
    }

    // ---- Internals --------------------------------------------------------------------------

    private inline fun update(change: (State) -> State) {
        val next = change(_state.value)
        _state.value = next
        publish(next)
        prefs?.edit()
            ?.putBoolean(KEY_ENABLED, next.enabled)
            ?.putString(KEY_USER_WORDS, writeEntries(next.userWords))
            ?.putString(KEY_REMOVED_BUILT_IN, JSONArray(next.removedBuiltIn.toList()).toString())
            ?.apply()
    }

    private fun publish(state: State) {
        val active = state.builtIn.filter { it.word !in state.removedBuiltIn } + state.userWords
        matcher = BlockedWordMatcher(
            active.filterNot { it.matchInside }.map { it.word },
            active.filter { it.matchInside }.map { it.word },
        )
        enabled = state.enabled
    }

    private fun writeEntries(entries: List<BlockedEntry>): String {
        val array = JSONArray()
        for (entry in entries) {
            array.put(JSONObject().put("w", entry.word).put("i", entry.matchInside))
        }
        return array.toString()
    }

    private fun readEntries(json: String?): List<BlockedEntry> {
        if (json.isNullOrEmpty()) return emptyList()
        return try {
            val array = JSONArray(json)
            (0 until array.length()).mapNotNull { index ->
                val obj = array.optJSONObject(index) ?: return@mapNotNull null
                val word = obj.optString("w")
                if (word.isEmpty()) null else BlockedEntry(word, obj.optBoolean("i", false))
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun readStringArray(json: String?): List<String> {
        if (json.isNullOrEmpty()) return emptyList()
        return try {
            val array = JSONArray(json)
            (0 until array.length()).map { array.getString(it) }
        } catch (e: Exception) {
            emptyList()
        }
    }
}
