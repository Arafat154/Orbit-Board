package org.futo.inputmethod.latin.blocklist

/**
 * One word in the block list.
 *
 * @param matchInside when true, longer words that start or end with this word are blocked too
 *                    (for example "porn" also blocks "pornhub" and "youporn"). Otherwise only the
 *                    exact word is blocked.
 */
data class BlockedEntry(val word: String, val matchInside: Boolean = false)

/**
 * Words that ship with the keyboard. This build ships with none: the list starts empty, like a
 * new install, and the person adds or imports their own words in Settings.
 */
object BlockedWordsLibrary {
    val builtIn: List<BlockedEntry> = emptyList()
}
