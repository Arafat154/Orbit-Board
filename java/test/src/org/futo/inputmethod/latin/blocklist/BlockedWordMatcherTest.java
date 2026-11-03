package org.futo.inputmethod.latin.blocklist;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;

import org.junit.Test;

public class BlockedWordMatcherTest {
    private final BlockedWordMatcher matcher = new BlockedWordMatcher(
            Arrays.asList("xxx", "xvideos", "redtube", "nude", "nudes", "ন্যুড", "sex"),
            Arrays.asList("porn", "পর্ন"));

    @Test
    public void blocksCaseAndDigitTricks() {
        assertTrue(matcher.isBlocked("porn"));
        assertTrue(matcher.isBlocked("PORN"));
        assertTrue(matcher.isBlocked("p0rn"));
        assertTrue(matcher.isBlocked("pornnnn"));
        assertTrue(matcher.isBlocked("pooorn"));
    }

    @Test
    public void extendedEntriesBlockWordsStartingOrEndingWithThem() {
        assertTrue(matcher.isBlocked("pornhub"));
        assertTrue(matcher.isBlocked("pornography"));
        assertTrue(matcher.isBlocked("youporn"));
    }

    @Test
    public void extendedEntriesDoNotBlockWordsThatOnlyContainThem() {
        // "Aporna" / "Oporna" are common Bengali names typed in Latin letters.
        assertFalse(matcher.isBlocked("aporna"));
        assertFalse(matcher.isBlocked("oporna"));
        assertFalse(matcher.isBlocked("অপর্ণা"));
    }

    @Test
    public void exactEntriesDoNotBlockLongerWords() {
        assertTrue(matcher.isBlocked("sex"));
        assertFalse(matcher.isBlocked("sexual"));
        assertFalse(matcher.isBlocked("sussex"));
        assertFalse(matcher.isBlocked("essex"));
    }

    @Test
    public void shortEntryNeverBlocksSingleLetter() {
        assertTrue(matcher.isBlocked("xxx"));
        assertFalse(matcher.isBlocked("x"));
        assertFalse(matcher.isBlocked("xx"));
    }

    @Test
    public void innocentWordsAreNotBlocked() {
        for (final String word : new String[] {"born", "corn", "popcorn", "horn", "hello",
                "nudge", "noodle", "exit", "six", "tax"}) {
            assertFalse(word, matcher.isBlocked(word));
        }
    }

    @Test
    public void bengaliWordsAndJoinerTricks() {
        assertTrue(matcher.isBlocked("পর্ন"));
        assertTrue(matcher.isBlocked("পর্নো"));
        assertTrue(matcher.isBlocked("পর্নোগ্রাফি"));
        assertTrue(matcher.isBlocked("প‌র্ন"));
        assertTrue(matcher.isBlocked("পর্‍ন"));
        assertTrue(matcher.isBlocked("ন্যুড"));
        assertFalse(matcher.isBlocked("অপর্ণা"));
        assertFalse(matcher.isBlocked("বাংলা"));
    }

    @Test
    public void emptyInputAndEmptyList() {
        assertFalse(matcher.isBlocked(null));
        assertFalse(matcher.isBlocked(""));
        assertFalse(new BlockedWordMatcher(new ArrayList<String>(), new ArrayList<String>())
                .isBlocked("porn"));
    }

    @Test
    public void tooShortExtendedEntriesAreIgnored() {
        assertFalse(new BlockedWordMatcher(new ArrayList<String>(), Arrays.asList("ab"))
                .isBlocked("cabbage"));
    }

    @Test
    public void masksBlockedWordsInsideText() {
        assertEquals("watch *** now", matcher.maskText("watch porn now", "***").toString());
        assertEquals("***.", matcher.maskText("porn.", "***").toString());
        assertEquals("*** and ***!", matcher.maskText("xxx and p0rn!", "***").toString());
        assertEquals("এটা *** ভিডিও", matcher.maskText("এটা পর্ন ভিডিও", "***").toString());
        assertEquals("***", matcher.maskText("porn", "***").toString());
        assertEquals("popcorn and corn", matcher.maskText("popcorn and corn", "***").toString());
    }

    @Test
    public void cleanTextIsReturnedUntouched() {
        final String clean = "this is a perfectly normal sentence";
        assertSame(clean, matcher.maskText(clean, "***"));
    }
}
