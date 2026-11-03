package org.futo.inputmethod.latin.blocklist;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Decides whether a word is on the user's block list.
 *
 * Pure Java with no Android dependencies, so it can be unit tested on a normal JVM.
 * Instances are immutable: build a new matcher whenever the list changes.
 *
 * Matching rules:
 * <ul>
 *   <li>Case-insensitive, Unicode (NFC) normalised, with zero-width characters and apostrophes
 *       ignored (so a joiner hidden inside a Bengali word does not defeat the list).</li>
 *   <li>Digits commonly used as letters are read as those letters ("p0rn" is "porn").</li>
 *   <li>Letters repeated many times are collapsed ("pornnnn" is "porn"), only for entries that
 *       are still at least {@link #MIN_COLLAPSED_LENGTH} characters long after collapsing, so a
 *       short entry such as "xxx" never blocks a lone "x".</li>
 *   <li>"Exact" entries must match the whole word. "Extended" entries also block longer words
 *       that start or end with them (for example "porn" also blocks "pornhub" and "youporn",
 *       but not "aporna", a common Bengali name, which merely contains it).</li>
 * </ul>
 */
public final class BlockedWordMatcher {
    /** Entries shorter than this (after collapsing repeats) are only ever matched exactly. */
    static final int MIN_COLLAPSED_LENGTH = 3;

    private final Set<String> exact = new HashSet<>();
    private final Set<String> collapsedExact = new HashSet<>();
    private final List<String> extended = new ArrayList<>();

    /**
     * @param exactWords words that must match a whole word
     * @param extendedWords words that are also blocked at the start or end of a longer word
     */
    public BlockedWordMatcher(final Collection<String> exactWords,
            final Collection<String> extendedWords) {
        for (final String word : exactWords) {
            final String normalized = normalize(word);
            if (normalized.isEmpty()) continue;
            exact.add(normalized);
            final String collapsed = collapseRuns(normalized);
            if (collapsed.length() >= MIN_COLLAPSED_LENGTH) collapsedExact.add(collapsed);
        }
        for (final String word : extendedWords) {
            final String collapsed = collapseRuns(normalize(word));
            // Too-short entries would block far too many ordinary words, so they are ignored.
            if (collapsed.length() >= MIN_COLLAPSED_LENGTH && !extended.contains(collapsed)) {
                extended.add(collapsed);
            }
        }
    }

    public boolean isEmpty() {
        return exact.isEmpty() && extended.isEmpty();
    }

    /** Returns true if this single word (no spaces) is on the list. */
    public boolean isBlocked(final CharSequence word) {
        if (word == null || word.length() == 0 || isEmpty()) return false;
        final String normalized = normalize(word);
        if (normalized.isEmpty()) return false;
        if (exact.contains(normalized)) return true;
        final String collapsed = collapseRuns(normalized);
        if (collapsed.length() >= MIN_COLLAPSED_LENGTH && collapsedExact.contains(collapsed)) {
            return true;
        }
        for (final String fragment : extended) {
            if (collapsed.startsWith(fragment) || collapsed.endsWith(fragment)) return true;
        }
        return false;
    }

    /**
     * Replaces every blocked word inside {@code text} with {@code mask}.
     * Returns the very same object when nothing needed replacing, so styling (spans) on ordinary
     * text is left untouched.
     */
    public CharSequence maskText(final CharSequence text, final String mask) {
        if (text == null || text.length() == 0 || isEmpty()) return text;
        final int length = text.length();
        StringBuilder out = null;
        int copiedUpTo = 0;
        int i = 0;
        while (i < length) {
            if (!isWordChar(text.charAt(i))) {
                i++;
                continue;
            }
            int j = i;
            while (j < length && isWordChar(text.charAt(j))) j++;
            if (isBlocked(text.subSequence(i, j))) {
                if (out == null) out = new StringBuilder(length);
                out.append(text, copiedUpTo, i).append(mask);
                copiedUpTo = j;
            }
            i = j;
        }
        if (out == null) return text;
        out.append(text, copiedUpTo, length);
        return out.toString();
    }

    /** Letters, digits, and the combining marks Bengali (and other scripts) use inside words. */
    private static boolean isWordChar(final char c) {
        if (Character.isLetterOrDigit(c)) return true;
        if (c == '‌' || c == '‍') return true; // joiners can sit inside a word
        final int type = Character.getType(c);
        return type == Character.NON_SPACING_MARK
                || type == Character.COMBINING_SPACING_MARK
                || type == Character.ENCLOSING_MARK;
    }

    /** Lower-cases, NFC-normalises and removes characters that should not affect matching. */
    public static String normalize(final CharSequence input) {
        final String composed = Normalizer.normalize(input, Normalizer.Form.NFC);
        final StringBuilder sb = new StringBuilder(composed.length());
        for (int i = 0; i < composed.length(); i++) {
            final char c = composed.charAt(i);
            if (c == '​' || c == '‌' || c == '‍' || c == '­'
                    || c == '﻿' || c == '\'' || c == '’') {
                continue;
            }
            sb.append(readDigitAsLetter(Character.toLowerCase(c)));
        }
        return sb.toString().toLowerCase(Locale.ROOT);
    }

    private static char readDigitAsLetter(final char c) {
        switch (c) {
            case '0': return 'o';
            case '1': return 'i';
            case '3': return 'e';
            case '4': return 'a';
            case '5': return 's';
            case '7': return 't';
            default: return c;
        }
    }

    /** Collapses any run of the same character into one ("pornnn" becomes "porn"). */
    static String collapseRuns(final String s) {
        final StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            final char c = s.charAt(i);
            if (sb.length() == 0 || sb.charAt(sb.length() - 1) != c) sb.append(c);
        }
        return sb.toString();
    }
}
