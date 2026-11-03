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
 * The starter library that ships with the keyboard. The user can remove any of these and add
 * their own words from Settings; this list is only the starting point.
 *
 * Only single words are supported (a phrase would have to be added as separate words).
 */
object BlockedWordsLibrary {
    val builtIn: List<BlockedEntry> = listOf(
        // English
        BlockedEntry("porn", matchInside = true), // also blocks porno, pornhub, pornography...
        BlockedEntry("xxx"),
        BlockedEntry("xvideos"),
        BlockedEntry("xnxx"),
        BlockedEntry("xhamster"),
        BlockedEntry("redtube"),
        BlockedEntry("hentai"),
        BlockedEntry("nude"),
        BlockedEntry("nudes"),

        // Bengali
        BlockedEntry("পর্ন", matchInside = true), // also blocks পর্নো, পর্নোগ্রাফি...
        BlockedEntry("ন্যুড"),
    )
}
